package com.nova.companion.data.objectbox

import android.content.Context
import android.util.Log
import io.objectbox.BoxStore

/**
 * Singleton ObjectBox store for vector search.
 * Initialized once from Application.onCreate() or lazily from SemanticSearch.
 */
object NovaObjectBox {

    private const val TAG = "NovaObjectBox"

    lateinit var store: BoxStore
        private set

    val isInitialized: Boolean
        get() = ::store.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .name("nova-vectors")
            .build()
        Log.i(TAG, "ObjectBox vector store initialized (${store.sizeOnDisk() / 1024}KB)")
    }

    /** Convenience accessor for the VectorMemory box */
    val vectorMemoryBox get() = store.boxFor(VectorMemory::class.java)
}
