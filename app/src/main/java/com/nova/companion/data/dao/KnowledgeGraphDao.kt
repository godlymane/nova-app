package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.GraphEdge
import com.nova.companion.data.entity.GraphNode

@Dao
interface KnowledgeGraphDao {

    // ── Node operations ──────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNode(node: GraphNode): Long

    @Update
    suspend fun updateNode(node: GraphNode)

    @Delete
    suspend fun deleteNode(node: GraphNode)

    @Query("SELECT * FROM graph_nodes WHERE id = :id")
    suspend fun getNodeById(id: Long): GraphNode?

    @Query("SELECT * FROM graph_nodes WHERE label = :label AND type = :type LIMIT 1")
    suspend fun findNode(label: String, type: String): GraphNode?

    @Query("SELECT * FROM graph_nodes WHERE label LIKE '%' || :query || '%' ORDER BY mentionCount DESC LIMIT :limit")
    suspend fun searchNodesByLabel(query: String, limit: Int = 10): List<GraphNode>

    @Query("SELECT * FROM graph_nodes WHERE type = :type ORDER BY mentionCount DESC LIMIT :limit")
    suspend fun getNodesByType(type: String, limit: Int = 50): List<GraphNode>

    @Query("SELECT * FROM graph_nodes ORDER BY lastMentioned DESC LIMIT :limit")
    suspend fun getRecentNodes(limit: Int = 20): List<GraphNode>

    @Query("SELECT * FROM graph_nodes ORDER BY mentionCount DESC LIMIT :limit")
    suspend fun getMostMentionedNodes(limit: Int = 20): List<GraphNode>

    @Query("SELECT * FROM graph_nodes WHERE embedding IS NOT NULL")
    suspend fun getNodesWithEmbeddings(): List<GraphNode>

    @Query("SELECT COUNT(*) FROM graph_nodes")
    suspend fun nodeCount(): Int

    /** Upsert: if label+type exists, bump mentionCount and update lastMentioned */
    @Transaction
    suspend fun upsertNode(label: String, type: String, embedding: ByteArray? = null): Long {
        val existing = findNode(label, type)
        return if (existing != null) {
            updateNode(
                existing.copy(
                    mentionCount = existing.mentionCount + 1,
                    lastMentioned = System.currentTimeMillis(),
                    embedding = embedding ?: existing.embedding
                )
            )
            existing.id
        } else {
            insertNode(
                GraphNode(
                    label = label,
                    type = type,
                    embedding = embedding
                )
            )
        }
    }

    @Query("UPDATE graph_nodes SET lastMentioned = :now, mentionCount = mentionCount + 1 WHERE id = :nodeId")
    suspend fun touchNode(nodeId: Long, now: Long = System.currentTimeMillis())

    // ── Edge operations ──────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEdge(edge: GraphEdge): Long

    @Update
    suspend fun updateEdge(edge: GraphEdge)

    @Delete
    suspend fun deleteEdge(edge: GraphEdge)

    @Query("SELECT * FROM graph_edges WHERE sourceNodeId = :nodeId OR targetNodeId = :nodeId ORDER BY weight DESC")
    suspend fun getEdgesForNode(nodeId: Long): List<GraphEdge>

    @Query("SELECT * FROM graph_edges WHERE sourceNodeId = :sourceId AND targetNodeId = :targetId AND relationType = :relation LIMIT 1")
    suspend fun findEdge(sourceId: Long, targetId: Long, relation: String): GraphEdge?

    @Query("SELECT * FROM graph_edges WHERE sourceNodeId = :sourceId AND targetNodeId = :targetId LIMIT 1")
    suspend fun findEdgeBetween(sourceId: Long, targetId: Long): GraphEdge?

    @Query("SELECT COUNT(*) FROM graph_edges")
    suspend fun edgeCount(): Int

    /** Upsert: if edge exists, reinforce weight; otherwise create it */
    @Transaction
    suspend fun upsertEdge(
        sourceNodeId: Long,
        targetNodeId: Long,
        relationType: String,
        context: String = ""
    ): Long {
        val existing = findEdge(sourceNodeId, targetNodeId, relationType)
        return if (existing != null) {
            updateEdge(
                existing.copy(
                    weight = existing.weight + 0.5f,
                    lastReinforced = System.currentTimeMillis(),
                    context = if (context.isNotBlank()) context else existing.context
                )
            )
            existing.id
        } else {
            insertEdge(
                GraphEdge(
                    sourceNodeId = sourceNodeId,
                    targetNodeId = targetNodeId,
                    relationType = relationType,
                    context = context
                )
            )
        }
    }

    // ── Graph traversal ──────────────────────────────────────

    /** Get all nodes directly connected to a given node (1-hop neighbors) */
    @Query("""
        SELECT DISTINCT n.* FROM graph_nodes n
        INNER JOIN graph_edges e ON (
            (e.sourceNodeId = :nodeId AND n.id = e.targetNodeId) OR
            (e.targetNodeId = :nodeId AND n.id = e.sourceNodeId)
        )
        ORDER BY n.mentionCount DESC
        LIMIT :limit
    """)
    suspend fun getNeighbors(nodeId: Long, limit: Int = 20): List<GraphNode>

    /** Get edges + neighbor nodes radiating from a node — the "sub-network" */
    @Query("""
        SELECT e.* FROM graph_edges e
        WHERE e.sourceNodeId = :nodeId OR e.targetNodeId = :nodeId
        ORDER BY e.weight DESC
        LIMIT :limit
    """)
    suspend fun getSubNetwork(nodeId: Long, limit: Int = 30): List<GraphEdge>

    /** 2-hop traversal: find all nodes reachable within 2 edges from the seed node */
    @Query("""
        SELECT DISTINCT n.* FROM graph_nodes n
        WHERE n.id IN (
            SELECT CASE
                WHEN e1.sourceNodeId = :nodeId THEN e1.targetNodeId
                ELSE e1.sourceNodeId
            END
            FROM graph_edges e1
            WHERE e1.sourceNodeId = :nodeId OR e1.targetNodeId = :nodeId
            UNION
            SELECT CASE
                WHEN e2.sourceNodeId = hop1.neighborId THEN e2.targetNodeId
                ELSE e2.sourceNodeId
            END
            FROM graph_edges e2
            INNER JOIN (
                SELECT CASE
                    WHEN e.sourceNodeId = :nodeId THEN e.targetNodeId
                    ELSE e.sourceNodeId
                END AS neighborId
                FROM graph_edges e
                WHERE e.sourceNodeId = :nodeId OR e.targetNodeId = :nodeId
            ) AS hop1 ON (e2.sourceNodeId = hop1.neighborId OR e2.targetNodeId = hop1.neighborId)
        )
        AND n.id != :nodeId
        ORDER BY n.mentionCount DESC
        LIMIT :limit
    """)
    suspend fun get2HopNeighbors(nodeId: Long, limit: Int = 30): List<GraphNode>

    /** Find the shortest path between two nodes (edges connecting them, up to 2 hops) */
    @Query("""
        SELECT e.* FROM graph_edges e
        WHERE (e.sourceNodeId = :nodeA AND e.targetNodeId = :nodeB)
           OR (e.sourceNodeId = :nodeB AND e.targetNodeId = :nodeA)
        ORDER BY e.weight DESC
    """)
    suspend fun getDirectEdges(nodeA: Long, nodeB: Long): List<GraphEdge>

    /** Decay: reduce weight for edges not reinforced in the given period */
    @Query("UPDATE graph_edges SET weight = weight * 0.9 WHERE lastReinforced < :cutoff AND weight > 0.1")
    suspend fun decayEdges(cutoff: Long)

    /** Prune dead edges with negligible weight */
    @Query("DELETE FROM graph_edges WHERE weight < 0.1")
    suspend fun pruneWeakEdges(): Int

    /** Prune orphaned nodes with no edges */
    @Query("""
        DELETE FROM graph_nodes WHERE id NOT IN (
            SELECT sourceNodeId FROM graph_edges
            UNION
            SELECT targetNodeId FROM graph_edges
        ) AND mentionCount <= 1
    """)
    suspend fun pruneOrphanedNodes(): Int
}
