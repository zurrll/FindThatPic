package dev.localphotos.shitu.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import android.os.SystemClock
import dev.localphotos.shitu.work.SearchTrace

class LocalEmbedder(private val context:Context) {
    private val mutex=Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session:OrtSession?=null
    val isLoaded get()=session!=null
    private val tokenizer by lazy {
        val vocab=context.assets.open("models/vocab.txt").bufferedReader().useLines { lines -> lines.withIndex().associate { it.value to it.index } }
        WordPiece(vocab)
    }
    private val queries=object:LinkedHashMap<String,FloatArray>(32,.75f,true) {}
    @Volatile var searching=false
    suspend fun encode(text:String,query:Boolean=false,trace:SearchTrace?=null):FloatArray = withContext(Dispatchers.Default) {
        if(query) synchronized(queries) { queries[text] }?.let { trace?.put("query_cache",true);return@withContext it }
        if(query) trace?.put("query_cache",false)
        val waiting=SystemClock.elapsedRealtime()
        (context.applicationContext as dev.localphotos.shitu.PhotoApp).models.run(ModelKind.TEXT) { mutex.withLock {
            trace?.elapsed("model_wait",SystemClock.elapsedRealtime()-waiting)
            trace?.put("model_was_loaded",session!=null)
            val active=session?:if(trace!=null) trace.stage("model_load") { load().also { session=it } } else load().also { session=it }
            val encoding=SystemClock.elapsedRealtime()
            val ids=tokenizer.encode((if(query) "为这个句子生成表示以用于检索相关文章：" else "")+text)
            val shape=longArrayOf(1,ids.size.toLong())
            val tensors=mutableMapOf<String,OnnxTensor>()
            try {
                tensors["input_ids"]=OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),shape)
                tensors["attention_mask"]=OnnxTensor.createTensor(environment,LongBuffer.wrap(LongArray(ids.size){1}),shape)
                if("token_type_ids" in active.inputNames) tensors["token_type_ids"]=OnnxTensor.createTensor(environment,LongBuffer.wrap(LongArray(ids.size)),shape)
                active.run(tensors).use { result ->
                    val output=result.get("last_hidden_state").orElse(result[0]) as OnnxTensor
                    val dimensions=output.info.shape
                    check(dimensions.size==3 && dimensions[0]==1L && dimensions.last()==512L) { "Unexpected embedding output" }
                    val buffer=output.floatBuffer
                    val width=dimensions.last().toInt()
                    val cls=FloatArray(width)
                    buffer.get(cls)
                    TextTools.unit(cls).also { vector -> if(query) synchronized(queries) { queries[text]=vector;if(queries.size>32) queries.remove(queries.keys.first()) } }
                }
            } finally { tensors.values.forEach { it.close() };trace?.elapsed("query_encode",SystemClock.elapsedRealtime()-encoding) }
        } }
    }
    private fun load():OrtSession {
        val target=File(context.noBackupFilesDir,"models/bge-small-zh-75c43b0-int8.onnx")
        if(!target.exists()) {
            target.parentFile!!.mkdirs()
            val temp=File(target.parentFile,"model.tmp")
            context.assets.open("models/bge-small-zh-int8.onnx").use { input -> temp.outputStream().use(input::copyTo) }
            check(temp.renameTo(target)) { "无法准备本地文字模型" }
        }
        return OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(target.absolutePath,opts)
        }
    }
    suspend fun unload() = mutex.withLock { session?.close();session=null }
}
