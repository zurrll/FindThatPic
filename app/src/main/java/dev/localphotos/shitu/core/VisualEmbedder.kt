package dev.localphotos.shitu.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** One bounded decode and one model inference per image. No OCR and no image rewriting. */
class VisualEmbedder(private val context:Context) {
    private val mutex=Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session:OrtSession?=null
    suspend fun encode(uri:Uri):VisualFeatures=withContext(Dispatchers.IO) {
        (context.applicationContext as dev.localphotos.shitu.PhotoApp).models.run(ModelKind.SIMILAR) { mutex.withLock {
            currentCoroutineContext().ensureActive()
            val source=ImageDecoder.createSource(context.contentResolver,uri)
            // ImageDecoder applies encoded orientation; decodeBitmap uses the first animation frame.
            val image=ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
                val scale=minOf(1.0,512.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize((info.size.width*scale).roundToInt().coerceAtLeast(1),(info.size.height*scale).roundToInt().coerceAtLeast(1))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
            try {
                val modelInput=Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888)
                val layout=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
                val gray=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
                try {
                    val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                    val scale=minOf(224f/image.width,224f/image.height)
                    val w=image.width*scale;val h=image.height*scale
                    Canvas(modelInput).apply {
                        drawColor(Color.rgb(124,116,104))
                        drawBitmap(image,null,RectF((224-w)/2,(224-h)/2,(224+w)/2,(224+h)/2),paint)
                    }
                    // Whole-image layout/hash use a white background for transparency, without letterbox padding.
                    for(target in listOf(layout,gray)) Canvas(target).apply {
                        drawColor(Color.WHITE);drawBitmap(image,null,Rect(0,0,target.width,target.height),paint)
                    }
                    val pixels=IntArray(224*224);modelInput.getPixels(pixels,0,224,0,0,224,224)
                    val input=FloatArray(3*pixels.size)
                    val mean=floatArrayOf(0.485f,0.456f,0.406f);val std=floatArrayOf(0.229f,0.224f,0.225f)
                    for(i in pixels.indices) for(c in 0..2) input[c*pixels.size+i]=(((pixels[i] shr (16-8*c)) and 255)/255f-mean[c])/std[c]
                    currentCoroutineContext().ensureActive()
                    val active=session?:load().also { session=it }
                    val vector=OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,224,224)).use { tensor ->
                        active.run(mapOf("pixel_values" to tensor)).use { result ->
                            val output=result[0] as OnnxTensor
                            check(output.info.shape.contentEquals(longArrayOf(1,VisualFeatures.DIMENSIONS.toLong()))) { "画面特征模型输出不兼容" }
                            FloatArray(VisualFeatures.DIMENSIONS).also { output.floatBuffer.get(it);TextTools.unit(it) }
                        }
                    }
                    check(vector.all { it.isFinite() } && vector.any { it!=0f }) { "画面特征无效" }
                    val colors=IntArray(256);layout.getPixels(colors,0,16,0,0,16,16)
                    val rgb=ByteArray(768) { i -> ((colors[i/3] shr (16-8*(i%3))) and 255).toByte() }
                    val grays=IntArray(1024);gray.getPixels(grays,0,32,0,0,32,32)
                    val luma=FloatArray(1024) { i -> val p=grays[i];0.299f*((p shr 16) and 255)+0.587f*((p shr 8) and 255)+0.114f*(p and 255) }
                    currentCoroutineContext().ensureActive()
                    VisualFeatures(vector,VisualMath.hash(luma),rgb,image.width,image.height)
                } finally { modelInput.recycle();layout.recycle();gray.recycle() }
            } finally { image.recycle() }
        } }
    }
    private fun load():OrtSession {
        val target=File(context.noBackupFilesDir,"models/mnv4-588380af-features.onnx")
        if(!target.exists()) {
            target.parentFile!!.mkdirs()
            val temp=File(target.parentFile,"visual.tmp")
            context.assets.open("models/mobilenetv4-small-features.onnx").use { input -> temp.outputStream().use(input::copyTo) }
            check(temp.renameTo(target)) { "无法准备本地画面模型" }
        }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(target.absolutePath,options)
        }
    }
    suspend fun unload()=mutex.withLock { session?.close();session=null }
}
