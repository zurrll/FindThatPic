package dev.localphotos.shitu.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ModelKind { OCR, TEXT, SIMILAR, DESCRIPTION_IMAGE, DESCRIPTION_TEXT }

/** Serialize inference, and release other encoder sessions before changing model families. */
class ModelGate(private val releaseOthers:suspend (ModelKind?)->Unit) {
    private val mutex=Mutex()
    private var active:ModelKind?=null
    suspend fun <T> run(kind:ModelKind,block:suspend ()->T):T=mutex.withLock {
        if(active!=kind) { releaseOthers(kind);active=kind }
        block()
    }
    suspend fun release()=mutex.withLock { releaseOthers(null);active=null }
}
