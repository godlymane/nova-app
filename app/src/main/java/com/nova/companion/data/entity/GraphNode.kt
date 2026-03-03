package com.nova.companion.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A node in Nova's knowledge graph.
 *
 * Nodes represent entities: people, places, things, concepts, preferences.
 * Each node has a label (the display name), a type (person, place, food, etc.),
 * and an optional embedding vector for semantic search.
 *
 * Examples:
 *   GraphNode(label="Deva", type="person")
 *   GraphNode(label="Oat Milk Latte", type="food")
 *   GraphNode(label="Blayzex", type="brand")
 *   GraphNode(label="Bangalore", type="location")
 */
@Entity(
    tableName = "graph_nodes",
    indices = [
        Index(value = ["label", "type"], unique = true)
    ]
)
data class GraphNode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,                   // "Sarah", "Oat Milk Latte", "Blayzex"
    val type: String,                    // person, place, food, brand, concept, activity, preference, event
    val createdAt: Long = System.currentTimeMillis(),
    val lastMentioned: Long = System.currentTimeMillis(),
    val mentionCount: Int = 1,
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null     // 1536-dim embedding for vector search
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphNode) return false
        return id == other.id && label == other.label && type == other.type
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}
