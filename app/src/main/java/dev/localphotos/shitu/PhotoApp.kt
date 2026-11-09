package dev.localphotos.shitu

import android.app.Application
import dev.localphotos.shitu.core.LocalEmbedder
import dev.localphotos.shitu.core.SearchEngine
import dev.localphotos.shitu.data.IndexProgress
import dev.localphotos.shitu.data.MediaLibrary
import dev.localphotos.shitu.data.PhotoDatabase
import dev.localphotos.shitu.work.IndexCoordinator
import dev.localphotos.shitu.work.PerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

class PhotoApp:Application() {
    val models by lazy { dev.localphotos.shitu.core.ModelGate { kind ->
        if(kind!=dev.localphotos.shitu.core.ModelKind.TEXT) embedder.unload()
        if(kind!=dev.localphotos.shitu.core.ModelKind.SIMILAR) visualEmbedder.unload()
        if(kind!=dev.localphotos.shitu.core.ModelKind.DESCRIPTION_IMAGE) descriptionEmbedder.unloadImage()
        if(kind!=dev.localphotos.shitu.core.ModelKind.DESCRIPTION_TEXT) descriptionEmbedder.unloadText()
    } }
    val database by lazy { PhotoDatabase(this) }
    val library by lazy { MediaLibrary(this,database) }
    val embedder by lazy { LocalEmbedder(this) }
    val search by lazy { SearchEngine(database,embedder) }
    val indexer by lazy { IndexCoordinator(this) }
    val visualEmbedder by lazy { dev.localphotos.shitu.core.VisualEmbedder(this) }
    val visualSearch by lazy { dev.localphotos.shitu.core.VisualSearch(database,visualEmbedder) }
    val visualIndexer by lazy { dev.localphotos.shitu.work.VisualIndex(this) }
    val descriptionEmbedder by lazy { dev.localphotos.shitu.core.DescriptionEmbedder(this) }
    val descriptionSearch by lazy { dev.localphotos.shitu.core.DescriptionSearch(database,descriptionEmbedder) }
    val preparation by lazy { dev.localphotos.shitu.work.Preparation(this) }
    val preparationJobMutex=Mutex()
    val userQueries=java.util.concurrent.atomic.AtomicInteger()
    val visualProgress=MutableStateFlow(IndexProgress())
    val visualJobMutex=Mutex()
    val metrics by lazy { PerformanceMonitor(this) }
    val progress=MutableStateFlow(IndexProgress())
    val batchMutex=Mutex()
    val indexMutex=Mutex()
    val jobMutex=Mutex()
}
