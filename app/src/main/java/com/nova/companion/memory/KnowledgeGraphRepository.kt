package com.nova.companion.memory

import android.content.Context
import android.util.Log
import com.nova.companion.data.dao.KnowledgeGraphDao
import com.nova.companion.data.entity.GraphEdge
import com.nova.companion.data.entity.GraphNode
import com.nova.companion.data.entity.toByteArray
import com.nova.companion.data.entity.toFloatArray
import java.util.concurrent.TimeUnit

/**
 * Repository that wraps KnowledgeGraphDao with higher-level operations:
 * - Triplet insertion (node1, edge, node2)
 * - Hybrid search: vector similarity on nodes → graph traversal on edges
 * - Sub-network extraction for prompt injection
 * - Graph decay and pruning
 */
class KnowledgeGraphRepository(
    private val dao: KnowledgeGraphDao,
    private val appContext: Context? = null
) {

    companion object {
        private const val TAG = "KnowledgeGraph"
    }

    // ── Triplet insertion ────────────────────────────────────

    /**
     * Insert a Node-Edge-Node triplet into the graph.
     * Upserts both nodes (bumps mention count if they exist) and the edge.
     * Optionally generates embeddings for new nodes.
     */
    suspend fun insertTriplet(
        node1Label: String,
        node1Type: String,
        edgeRelation: String,
        node2Label: String,
        node2Type: String,
        contextSnippet: String = ""
    ) {
        try {
            // Generate embeddings for nodes if online
            val emb1 = generateNodeEmbedding(node1Label)
            val emb2 = generateNodeEmbedding(node2Label)

            val nodeId1 = dao.upsertNode(
                label = node1Label.trim(),
                type = node1Type.trim().lowercase(),
                embedding = emb1
            )
            val nodeId2 = dao.upsertNode(
                label = node2Label.trim(),
                type = node2Type.trim().lowercase(),
                embedding = emb2
            )

            if (nodeId1 > 0 && nodeId2 > 0) {
                dao.upsertEdge(
                    sourceNodeId = nodeId1,
                    targetNodeId = nodeId2,
                    relationType = edgeRelation.trim().lowercase(),
                    context = contextSnippet.take(200)
                )
                Log.d(TAG, "Triplet: [$node1Label] --($edgeRelation)--> [$node2Label]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert triplet: $node1Label -> $edgeRelation -> $node2Label", e)
        }
    }

    private suspend fun generateNodeEmbedding(label: String): ByteArray? {
        if (appContext == null) return null
        return try {
            val embedding = SemanticSearch.embed(label, appContext)
            embedding?.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed for node: $label", e)
            null
        }
    }

    // ── Hybrid search: vector → graph ────────────────────────

    /**
     * Core hybrid search:
     * 1. Embed the query.
     * 2. Find the top-K nodes by cosine similarity to the query.
     * 3. For each matched node, traverse edges to gather the sub-network.
     * 4. Return a formatted context string suitable for LLM injection.
     */
    suspend fun queryGraph(query: String, topK: Int = 5): GraphQueryResult {
        // Step 1: Find relevant nodes via vector similarity
        val seedNodes = findSeedNodes(query, topK)
        if (seedNodes.isEmpty()) {
            // Fallback: try keyword match on node labels
            return queryGraphByKeyword(query, topK)
        }

        // Step 2: Expand sub-network from seed nodes
        return expandSubNetwork(seedNodes)
    }

    /**
     * Vector similarity search over node embeddings.
     * Returns the top-K nodes most similar to the query.
     */
    private suspend fun findSeedNodes(query: String, topK: Int): List<GraphNode> {
        if (appContext == null) return emptyList()

        val queryEmbedding = SemanticSearch.embed(query, appContext) ?: return emptyList()
        val allNodes = dao.getNodesWithEmbeddings()
        if (allNodes.isEmpty()) return emptyList()

        return allNodes
            .mapNotNull { node ->
                try {
                    val nodeEmbedding = node.embedding!!.toFloatArray()
                    val similarity = SemanticSearch.cosineSimilarity(queryEmbedding, nodeEmbedding)
                    if (similarity > 0.35f) node to similarity else null
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * Keyword-based fallback when vector search isn't available (offline).
     */
    private suspend fun queryGraphByKeyword(query: String, topK: Int): GraphQueryResult {
        val keywords = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 2 }
            .distinct()
            .take(5)

        val matchedNodes = mutableSetOf<GraphNode>()
        for (keyword in keywords) {
            val nodes = dao.searchNodesByLabel(keyword, topK)
            matchedNodes.addAll(nodes)
        }

        if (matchedNodes.isEmpty()) {
            return GraphQueryResult(emptyList(), emptyList(), emptyList())
        }

        return expandSubNetwork(matchedNodes.toList().take(topK))
    }

    /**
     * Given seed nodes, expand the sub-network by traversing edges.
     * Returns seed nodes + their edges + connected neighbor nodes.
     */
    private suspend fun expandSubNetwork(seedNodes: List<GraphNode>): GraphQueryResult {
        val allEdges = mutableListOf<GraphEdge>()
        val neighborNodes = mutableSetOf<GraphNode>()

        for (seed in seedNodes) {
            val edges = dao.getSubNetwork(seed.id, limit = 10)
            allEdges.addAll(edges)

            val neighbors = dao.getNeighbors(seed.id, limit = 10)
            neighborNodes.addAll(neighbors)
        }

        // Touch seed nodes (update lastMentioned + mentionCount)
        for (node in seedNodes) {
            dao.touchNode(node.id)
        }

        return GraphQueryResult(
            seedNodes = seedNodes,
            edges = allEdges.distinctBy { it.id },
            neighborNodes = neighborNodes.toList()
        )
    }

    // ── Context formatting ───────────────────────────────────

    /**
     * Build a formatted string from graph query results for injection into the LLM prompt.
     *
     * Format:
     *   KNOWLEDGE GRAPH:
     *   [User] --likes--> [Oat Milk Latte]
     *   [Sarah] --is sibling of--> [User]
     *   [User] --founded--> [Blayzex]
     *
     *   Key entities: User (person), Sarah (person), Blayzex (brand)
     */
    suspend fun buildGraphContext(query: String, maxTriplets: Int = 12): String {
        val result = queryGraph(query)

        if (result.edges.isEmpty()) return ""

        val parts = mutableListOf<String>()

        // Format edges as triplet lines
        val tripletLines = mutableListOf<String>()
        val nodeCache = mutableMapOf<Long, GraphNode>()

        // Cache all relevant nodes
        for (node in result.seedNodes + result.neighborNodes) {
            nodeCache[node.id] = node
        }

        for (edge in result.edges.take(maxTriplets)) {
            val source = nodeCache[edge.sourceNodeId] ?: dao.getNodeById(edge.sourceNodeId)
            val target = nodeCache[edge.targetNodeId] ?: dao.getNodeById(edge.targetNodeId)

            if (source != null && target != null) {
                nodeCache[source.id] = source
                nodeCache[target.id] = target
                tripletLines.add("[${source.label}] --${edge.relationType}--> [${target.label}]")
            }
        }

        if (tripletLines.isNotEmpty()) {
            parts.add("KNOWLEDGE GRAPH:\n${tripletLines.joinToString("\n")}")
        }

        // Add key entity summary
        val keyEntities = nodeCache.values
            .sortedByDescending { it.mentionCount }
            .take(8)
            .joinToString(", ") { "${it.label} (${it.type})" }
        if (keyEntities.isNotBlank()) {
            parts.add("Key entities: $keyEntities")
        }

        return parts.joinToString("\n\n")
    }

    // ── Graph maintenance ────────────────────────────────────

    /**
     * Decay edge weights for relationships not reinforced in the last 30 days.
     * Prune edges that have decayed below threshold.
     * Prune orphaned nodes (no edges, mentioned only once).
     */
    suspend fun runMaintenance() {
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        dao.decayEdges(thirtyDaysAgo)
        val prunedEdges = dao.pruneWeakEdges()
        val prunedNodes = dao.pruneOrphanedNodes()
        Log.d(TAG, "Graph maintenance: pruned $prunedEdges edges, $prunedNodes orphaned nodes")
    }

    /**
     * Get graph stats for debug/settings display.
     */
    suspend fun getStats(): GraphStats {
        return GraphStats(
            nodeCount = dao.nodeCount(),
            edgeCount = dao.edgeCount()
        )
    }

    data class GraphStats(val nodeCount: Int, val edgeCount: Int)
}

/**
 * Result of a graph query: seed nodes that matched, the edges radiating from them,
 * and the neighbor nodes connected via those edges.
 */
data class GraphQueryResult(
    val seedNodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val neighborNodes: List<GraphNode>
)
