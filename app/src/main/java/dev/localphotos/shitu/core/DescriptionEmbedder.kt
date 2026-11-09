package dev.localphotos.shitu.core

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.net.Uri
import dev.localphotos.shitu.PhotoApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt
import android.os.SystemClock
import dev.localphotos.shitu.work.SearchTrace

class DescriptionEmbedder(private val context:Context) {
    private val mutex=Mutex()
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var imageSession:OrtSession?=null
    private var textSession:OrtSession?=null
    private val queries=object:LinkedHashMap<String,FloatArray>(32,.75f,true) {}
    private val app get()=context.applicationContext as PhotoApp
    private val tokenizer by lazy {
        WordPiece(context.assets.open("models/cnclip-vocab.txt").bufferedReader().useLines { lines -> lines.withIndex().associate { it.value to it.index } },lowercase=true)
    }
    suspend fun image(uri:Uri):DescriptionFeatures=withContext(Dispatchers.IO) {
        app.models.run(ModelKind.DESCRIPTION_IMAGE) { mutex.withLock {
            currentCoroutineContext().ensureActive()
            val source=ImageDecoder.createSource(context.contentResolver,uri)
            val bitmap=ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
                val scale=minOf(1.0,1536.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize((info.size.width*scale).roundToInt().coerceAtLeast(1),(info.size.height*scale).roundToInt().coerceAtLeast(1))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
            try {
                val sourcePixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(sourcePixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val pixels=DescriptionPixels.fit(sourcePixels,bitmap.width,bitmap.height)
                val input=FloatArray(3*224*224);val mean=floatArrayOf(.48145466f,.4578275f,.40821073f);val std=floatArrayOf(.26862954f,.26130258f,.27577711f)
                for(i in pixels.indices) for(c in 0..2) input[c*pixels.size+i]=(((pixels[i] ushr (16-8*c)) and 255)*(1f/255f)-mean[c])/std[c]
                currentCoroutineContext().ensureActive()
                val active=imageSession?:load("cnclip-rn50-image-int8.onnx","2b8a8f03","e1208d2e-cnclip-image-int8.onnx").also { imageSession=it }
                val vector=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,3,224,224)).use { tensor ->
                    active.run(mapOf("pixel_values" to tensor)).use { result -> read(result[0] as OnnxTensor) }
                }
                DescriptionFeatures(vector,bitmap.width,bitmap.height)
            } finally { bitmap.recycle() }
        } }
    }
    suspend fun text(query:String,trace:SearchTrace?=null):FloatArray=withContext(Dispatchers.Default) {
        require(query.isNotBlank())
        synchronized(queries) { queries[query.trim()] }?.let { trace?.put("query_cache",true);return@withContext it }
        trace?.put("query_cache",false)
        val waiting=SystemClock.elapsedRealtime()
        val result=app.models.run(ModelKind.DESCRIPTION_TEXT) { mutex.withLock {
            trace?.elapsed("model_wait",SystemClock.elapsedRealtime()-waiting)
            currentCoroutineContext().ensureActive()
            trace?.put("model_was_loaded",textSession!=null)
            val active=textSession?:run {
                val loadText={ load("cnclip-rn50-text-int8.onnx","0cc0996b","5daf248e-cnclip-text-int8.onnx") }
                (trace?.stage("model_load") { loadText() }?:loadText()).also { textSession=it }
            }
            val encoding=SystemClock.elapsedRealtime()
            val tokens=tokenizer.encode(query,52);val ids=LongArray(52);tokens.copyInto(ids)
            val mask=LongArray(52) { if(it<tokens.size) 1 else 0 }
            trace?.put("query_tokens",tokens.size)
            try { OnnxTensor.createTensor(env,LongBuffer.wrap(ids),longArrayOf(1,52)).use { input ->
                OnnxTensor.createTensor(env,LongBuffer.wrap(mask),longArrayOf(1,52)).use { attention ->
                    active.run(mapOf("input_ids" to input,"attention_mask" to attention)).use { result -> read(result[0] as OnnxTensor) }
                }
            } } finally { trace?.elapsed("query_encode",SystemClock.elapsedRealtime()-encoding) }
        } }
        synchronized(queries) { queries[query.trim()]=result;if(queries.size>32) queries.remove(queries.keys.first()) }
        result
    }
    private fun read(output:OnnxTensor):FloatArray {
        check(output.info.shape.contentEquals(longArrayOf(1,DescriptionFeatures.DIMENSIONS.toLong()))) { "画面描述模型输出不兼容" }
        val vector=FloatArray(DescriptionFeatures.DIMENSIONS);output.floatBuffer.get(vector)
        check(vector.all { it.isFinite() } && vector.any { it!=0f }) { "画面描述特征无效" }
        return TextTools.unit(vector)
    }
    private fun load(name:String,revision:String,obsolete:String):OrtSession {
        val folder=File(context.noBackupFilesDir,"models")
        File(folder,obsolete).delete()
        val target=File(folder,"$revision-$name")
        if(!target.exists()) {
            target.parentFile!!.mkdirs();val temp=File(target.parentFile,"$revision.tmp")
            context.assets.open("models/$name").use { input -> temp.outputStream().use(input::copyTo) }
            check(temp.renameTo(target)) { "无法准备画面描述模型" }
        }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(target.path,options)
        }
    }
    suspend fun unloadImage()=mutex.withLock { imageSession?.close();imageSession=null }
    suspend fun unloadText()=mutex.withLock { textSession?.close();textSession=null }
}
