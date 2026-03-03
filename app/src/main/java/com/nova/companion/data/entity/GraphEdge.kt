package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An edge (relationship) in Nova's knowledge graph.
 *
 * Edges connect two nodes with a labeled relationship.
 * Weight indicates confidence/frequency (higher = more certain).
 *
 * Examples:
 *   GraphEdge(sourceNodeId=1, targetNodeId=2, relationType="likes")      // User likes Oat Milk Latte
 *   GraphEdge(sourceNodeId=3, targetNodeId=1, relationType="is sibling of") // Sarah is sibling of User
 *   GraphEdge(sourceNodeId=1, targetNodeId=4, relationType="founded")    // User founded Blayzex
 */
@Entity(
    tableName = "graph_edges",
    foreignKeys = [
        ForeignKey(
            entity = GraphNode::class,
            parentColumns = ["id"],
            childColumns = ["sourceNodeId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GraphNode::class,
            parentColumns = ["id"],
            childColumns = ["targetNodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceNodeId"]),
        Index(value = ["targetNodeId"]),
        Index(value = ["sourceNodeId", "targetNodeId", "relationType"], unique = true)
    ]
)
data class GraphEdge(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceNodeId: Long,
    val targetNodeId: Long,
    val relationType: String,            // "likes", "is sibling of", "works at", "founded"
    val weight: Float = 1.0f,            // confidence/frequency — boosted on repeated mentions
    val context: String = "",            // original snippet that produced this edge
    val createdAt: Long = System.currentTimeMillis(),
    val lastReinforced: Long = System.currentTimeMillis()
)
