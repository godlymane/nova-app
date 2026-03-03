package com.nova.companion.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,       // fitness, business, emotional, coding, personal, goals
    val content: String,
    val importance: Int = 5,    // 1-10 scale
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null  // Semantic search embedding (1536-dim float array as bytes)
) {
    /** Skip embedding in equals/hashCode — it's a large blob */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Memory) return false
        return id == other.id && category == other.category && content == other.content &&
                importance == other.importance && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}

/** Convert a FloatArray to ByteArray for Room BLOB storage */
fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

/** Convert a ByteArray back to FloatArray from Room BLOB storage */
fun ByteArray.toFloatArray(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buffer.getFloat() }
}
