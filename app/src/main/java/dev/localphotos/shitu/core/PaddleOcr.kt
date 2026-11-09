package dev.localphotos.shitu.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class OcrModelUnavailable(cause: Exception) : IllegalStateException("OCR 模型无法加载", cause)

internal data class PaddleLine(val text: String, val box: OcrBox, val confidence: Float)

internal class PaddleOcr(private val context: Context) : AutoCloseable {
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null
    private val alphabet by lazy {
        val json = context.assets.open("models/ppocrv6-tiny-charset.json").bufferedReader().use { JSONArray(it.readText()) }
        List(json.length(), json::getString)
    }

    private fun load(asset: String, revision: String): OrtSession {
        val directory = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val target = File(directory, "$revision-$asset")
        if (!target.isFile) {
            val temp = File.createTempFile("ppocr-", ".tmp", directory)
            try {
                context.assets.open("models/$asset").use { input -> temp.outputStream().use(input::copyTo) }
                check(temp.renameTo(target)) { "无法保存 OCR 模型" }
            } finally {
                temp.delete()
            }
        }
        return OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(2)
            it.setInterOpNumThreads(1)
            it.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(target.path, it)
        }
    }

    private fun prepare() {
        try {
            check(alphabet.size == 6906)
            if (detector == null) detector = load("ppocrv6-tiny-det.onnx", "193bab7a")
            if (recognizer == null) recognizer = load("ppocrv6-tiny-rec.onnx", "9ef676d6")
        } catch (e: Exception) {
            throw OcrModelUnavailable(e)
        }
    }

    suspend fun lines(bitmap: Bitmap): List<PaddleLine> {
        prepare()
        val scope = currentCoroutineContext()
        scope.ensureActive()
        val scale = minOf(1.0, 960.0 / maxOf(bitmap.width, bitmap.height))
        fun aligned(edge: Int) = maxOf(32, (edge * scale / 32).roundToInt() * 32)
        val width = aligned(bitmap.width)
        val height = aligned(bitmap.height)
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val input = try {
            Canvas(scaled).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            }
            tensorPixels(scaled, detectorInput = true)
        } finally {
            scaled.recycle()
        }
        val boxes = infer(checkNotNull(detector), input, width, height) { output ->
            check(output.info.shape.contentEquals(longArrayOf(1, 1, height.toLong(), width.toLong())))
            val probabilities = FloatArray(width * height).also { output.floatBuffer.get(it) }
            PaddleBoxes.detect(probabilities, width, height, scope::ensureActive)
        }
        val sx = bitmap.width.toFloat() / width
        val sy = bitmap.height.toFloat() / height
        return buildList {
            for (box in boxes) {
                scope.ensureActive()
                for (i in box.indices step 2) {
                    box[i] = (box[i] * sx).coerceIn(0f, bitmap.width.toFloat())
                    box[i + 1] = (box[i + 1] * sy).coerceIn(0f, bitmap.height.toFloat())
                }
                recognize(bitmap, box)?.let(::add)
            }
        }
    }

    private fun recognize(bitmap: Bitmap, box: FloatArray): PaddleLine? {
        fun distance(a: Int, b: Int) = hypot(box[a] - box[b], box[a + 1] - box[b + 1])
        val width = maxOf(distance(0, 2), distance(4, 6))
        val height = maxOf(distance(0, 6), distance(2, 4))
        if (width < 2f || height < 2f) return null
        val rotate = height / width >= 1.5f
        val ratio = if (rotate) height / width else width / height
        val lineWidth = ceil(48 * ratio).toInt().coerceIn(1, 3200)
        val tensorWidth = ((lineWidth + 7) / 8 * 8).coerceAtLeast(32)
        val source = if (rotate) FloatArray(8) { box[(it + 2) % 8] } else box
        val w = lineWidth.toFloat()
        val transform = Matrix()
        if (!transform.setPolyToPoly(source, 0, floatArrayOf(0f, 0f, w, 0f, w, 48f, 0f, 48f), 0, 4)) return null

        val crop = Bitmap.createBitmap(lineWidth, 48, Bitmap.Config.ARGB_8888)
        val pixels = try {
            Canvas(crop).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, transform, paint)
            }
            tensorPixels(crop, detectorInput = false, stride = tensorWidth)
        } finally {
            crop.recycle()
        }
        val (text, confidence) = infer(checkNotNull(recognizer), pixels, tensorWidth, 48, ::decode)
        if (text.isBlank() || confidence < 0.30f) return null
        val xs = box.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = box.filterIndexed { i, _ -> i % 2 == 1 }
        return PaddleLine(text.trim(), OcrBox(xs.min(), ys.min(), xs.max(), ys.max()), confidence)
    }

    private fun decode(output: OnnxTensor): Pair<String, Float> {
        val shape = output.info.shape
        check(shape.size == 3 && shape[0] == 1L && shape[2] == alphabet.size.toLong())
        val buffer = output.floatBuffer
        val text = StringBuilder()
        var previous = -1
        var sum = 0f
        var count = 0
        repeat(shape[1].toInt()) {
            var best = -Float.MAX_VALUE
            var index = 0
            for (c in alphabet.indices) {
                val value = buffer.get()
                if (value > best) {
                    index = c
                    best = value
                }
            }
            if (index != 0 && index != previous) {
                text.append(alphabet[index])
                sum += best
                count++
            }
            previous = index
        }
        return text.toString() to if (count == 0) 0f else sum / count
    }

    private fun tensorPixels(bitmap: Bitmap, detectorInput: Boolean, stride: Int = bitmap.width): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val plane = stride * height
        val mean = floatArrayOf(.485f, .456f, .406f)
        val std = floatArrayOf(.229f, .224f, .225f)
        return FloatArray(3 * plane).also { input ->
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                for (c in 0..2) {
                    val shift = if (detectorInput) 8 * c else 16 - 8 * c
                    val channel = (pixel ushr shift) and 255
                    input[c * plane + y * stride + x] =
                        if (detectorInput) (channel / 255f - mean[c]) / std[c] else channel / 127.5f - 1f
                }
            }
        }
    }

    private fun <T> infer(session: OrtSession, input: FloatArray, width: Int, height: Int, read: (OnnxTensor) -> T): T =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            session.run(mapOf("x" to tensor)).use { read(it[0] as OnnxTensor) }
        }

    override fun close() {
        try {
            detector?.close()
        } finally {
            recognizer?.close()
            detector = null
            recognizer = null
        }
    }
}
