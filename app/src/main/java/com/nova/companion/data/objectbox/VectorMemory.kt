package com.nova.companion.data.objectbox

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

/**
 * ObjectBox entity solely for HNSW vector search.
 * Room remains the source of truth for all Memory persistence.
 * This entity mirrors the embedding + minimal metadata needed for nearest-neighbor queries.
 */
@Entity
data class VectorMemory(
    @Id var id: Long = 0,

    /** Room Memory.id — foreign key back to the canonical store */
    var roomMemoryId: Long = 0,

    /** Memory content text (for result display without Room round-trip) */
    var content: String = "",

    /** Category tag: fact, preference, event, person, etc. */
    var category: String = "",

    /** OpenAI text-embedding-3-small output — 1536 dimensions */
    @HnswIndex(dimensions = 1536)
    var embedding: FloatArray = FloatArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorMemory) return false
        return roomMemoryId == other.roomMemoryId
    }

    override fun hashCode(): Int = roomMemoryId.hashCode()
}
