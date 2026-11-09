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
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class OcrModelUnavailable(cause: Exception) : IllegalStateException("OCR 模型无法加载", cause)

internal data class PaddleLine(val text: String, val box: OcrBox, val confidence: Float)

internal class PaddleTimings {
    var load = 0L
    var input = 0L
    var detect = 0L
    var boxes = 0L
    var recognize = 0L
    var decode = 0L
    var boxCount = 0
    var recognitionCalls = 0

    fun add(other: PaddleTimings) {
        load += other.load
        input += other.input
        detect += other.detect
        boxes += other.boxes
        recognize += other.recognize
        decode += other.decode
        boxCount += other.boxCount
        recognitionCalls += other.recognitionCalls
    }

    fun json() = JSONObject()
        .put("loadMs", load / 1_000_000).put("inputMs", input / 1_000_000)
        .put("detectMs", detect / 1_000_000).put("boxesMs", boxes / 1_000_000)
        .put("recognizeMs", recognize / 1_000_000).put("decodeTextMs", decode / 1_000_000)
        .put("boxCount", boxCount).put("recognitionCalls", recognitionCalls)
}

internal class PaddleOcr(private val context: Context, private val maxBatch: Int = 4) : AutoCloseable {
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null
    private var inputBuffer: FloatBuffer? = null
    private var pixelBuffer = IntArray(0)
    private val detectionValues = Array(3) { channel ->
        val mean = floatArrayOf(.485f, .456f, .406f)[channel]
        val std = floatArrayOf(.229f, .224f, .225f)[channel]
        FloatArray(256) { (it / 255f - mean) / std }
    }
    private val recognitionValues = FloatArray(256) { it / 127.5f - 1f }

    private data class Crop(val index: Int, val box: FloatArray, val width: Int,
        val stride: Int, val transform: Matrix)
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

    suspend fun lines(bitmap: Bitmap, timings: PaddleTimings): List<PaddleLine> {
        var tick = SystemClock.elapsedRealtimeNanos()
        prepare()
        timings.load += SystemClock.elapsedRealtimeNanos() - tick
        val scope = currentCoroutineContext()
        scope.ensureActive()
        tick = SystemClock.elapsedRealtimeNanos()
        val scale = minOf(1.0, 960.0 / maxOf(bitmap.width, bitmap.height))
        fun aligned(edge: Int) = maxOf(32, (edge * scale / 32).roundToInt() * 32)
        val width = aligned(bitmap.width)
        val height = aligned(bitmap.height)
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val input = buffer(3 * width * height)
        try {
            Canvas(scaled).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            }
            writePixels(scaled, input, width, 0, detectorInput = true)
        } finally {
            scaled.recycle()
        }
        timings.input += SystemClock.elapsedRealtimeNanos() - tick
        tick = SystemClock.elapsedRealtimeNanos()
        val boxes = infer(checkNotNull(detector), input, 1, width, height) { output ->
            timings.detect += SystemClock.elapsedRealtimeNanos() - tick
            val start = SystemClock.elapsedRealtimeNanos()
            check(output.info.shape.contentEquals(longArrayOf(1, 1, height.toLong(), width.toLong())))
            val probabilities = FloatArray(width * height).also { output.floatBuffer.get(it) }
            PaddleBoxes.detect(probabilities, width, height, scope::ensureActive).also {
                timings.boxes += SystemClock.elapsedRealtimeNanos() - start
            }
        }
        timings.boxCount += boxes.size
        val sx = bitmap.width.toFloat() / width
        val sy = bitmap.height.toFloat() / height
        val crops = boxes.mapIndexedNotNull { index, box ->
            scope.ensureActive()
            for (i in box.indices step 2) {
                box[i] = (box[i] * sx).coerceIn(0f, bitmap.width.toFloat())
                box[i + 1] = (box[i + 1] * sy).coerceIn(0f, bitmap.height.toFloat())
            }
            cropFor(index, box)
        }
        val lines = arrayOfNulls<PaddleLine>(boxes.size)
        for ((stride, sameWidth) in crops.groupBy(Crop::stride)) {
            // Batch only equal tensor widths and bound the combined output size.
            val batchSize = (2048 / stride).coerceIn(1, maxBatch)
            for (start in sameWidth.indices step batchSize) {
                scope.ensureActive()
                val batch = sameWidth.subList(start, minOf(start + batchSize, sameWidth.size))
                recognize(bitmap, batch, stride, lines, timings)
            }
        }
        return lines.filterNotNull()
    }

    private fun cropFor(index: Int, box: FloatArray): Crop? {
        fun distance(a: Int, b: Int) = hypot(box[a] - box[b], box[a + 1] - box[b + 1])
        val width = maxOf(distance(0, 2), distance(4, 6))
        val height = maxOf(distance(0, 6), distance(2, 4))
        if (width < 2f || height < 2f) return null
        val rotate = height / width >= 1.5f
        val ratio = if (rotate) height / width else width / height
        val lineWidth = ceil(48 * ratio).toInt().coerceIn(1, 3200)
        val stride = ((lineWidth + 7) / 8 * 8).coerceAtLeast(32)
        val source = if (rotate) FloatArray(8) { box[(it + 2) % 8] } else box
        val w = lineWidth.toFloat()
        val transform = Matrix()
        if (!transform.setPolyToPoly(source, 0, floatArrayOf(0f, 0f, w, 0f, w, 48f, 0f, 48f), 0, 4)) return null
        return Crop(index, box, lineWidth, stride, transform)
    }

    private fun recognize(bitmap: Bitmap, batch: List<Crop>, stride: Int,
        lines: Array<PaddleLine?>, timings: PaddleTimings) {
        var tick = SystemClock.elapsedRealtimeNanos()
        val lineSize = 3 * stride * 48
        val input = buffer(batch.size * lineSize)
        for ((i, spec) in batch.withIndex()) {
            val crop = Bitmap.createBitmap(spec.width, 48, Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, spec.transform, paint)
                }
                writePixels(crop, input, stride, i * lineSize, detectorInput = false)
            } finally {
                crop.recycle()
            }
        }
        timings.input += SystemClock.elapsedRealtimeNanos() - tick
        tick = SystemClock.elapsedRealtimeNanos()
        timings.recognitionCalls++
        infer(checkNotNull(recognizer), input, batch.size, stride, 48) { output ->
            timings.recognize += SystemClock.elapsedRealtimeNanos() - tick
            val start = SystemClock.elapsedRealtimeNanos()
            val shape = output.info.shape
            check(shape.size == 3 && shape[0] == batch.size.toLong() && shape[2] == alphabet.size.toLong())
            val scores = output.floatBuffer
            for (spec in batch) {
                val (text, confidence) = decode(scores, shape[1].toInt())
                if (text.isBlank() || confidence < 0.30f) continue
                val box = spec.box
                val xs = box.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = box.filterIndexed { i, _ -> i % 2 == 1 }
                lines[spec.index] = PaddleLine(text.trim(), OcrBox(xs.min(), ys.min(), xs.max(), ys.max()), confidence)
            }
            timings.decode += SystemClock.elapsedRealtimeNanos() - start
        }
    }

    private fun decode(scores: FloatBuffer, steps: Int): Pair<String, Float> {
        val text = StringBuilder()
        var previous = -1
        var sum = 0f
        var count = 0
        repeat(steps) {
            var best = -Float.MAX_VALUE
            var index = 0
            for (c in alphabet.indices) {
                val value = scores.get()
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

    private fun buffer(size: Int): FloatBuffer {
        val input = inputBuffer?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                .asFloatBuffer().also { inputBuffer = it }
        input.clear()
        input.limit(size)
        return input
    }

    private fun writePixels(bitmap: Bitmap, input: FloatBuffer, stride: Int, offset: Int, detectorInput: Boolean) {
        val width = bitmap.width
        val height = bitmap.height
        if (pixelBuffer.size < width * height) pixelBuffer = IntArray(width * height)
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
        val plane = stride * height
        for (channel in 0..2) {
            val shift = if (detectorInput) 8 * channel else 16 - 8 * channel
            val values = if (detectorInput) detectionValues[channel] else recognitionValues
            input.position(offset + channel * plane)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) input.put(values[(pixelBuffer[row + x] ushr shift) and 255])
                repeat(stride - width) { input.put(0f) }
            }
        }
        input.rewind()
    }

    private fun <T> infer(session: OrtSession, input: FloatBuffer, batch: Int, width: Int, height: Int,
        read: (OnnxTensor) -> T): T =
        OnnxTensor.createTensor(env, input, longArrayOf(batch.toLong(), 3, height.toLong(), width.toLong())).use { tensor ->
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
