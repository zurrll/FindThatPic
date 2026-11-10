package dev.localphotos.shitu.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

// Raw runs and generated alternatives remain inspectable instead of silently replacing evidence.
data class OcrOutput(val text:String,val blocks:String,val variants:String="",val debug:String="{}",val version:String=OcrEngine.VERSION)
class OcrEngine(private val context:Context):AutoCloseable {
    private val recognizer=PaddleOcr(context)
    companion object {
        const val VERSION="ppocrv6-tiny:adaptive-v4:det960-top1-v1"
        internal const val RECOGNITION_THREADS=4
        fun engineLabel(version:String)=when {
            version.startsWith("ppocrv6-tiny:")->"PP-OCRv6 tiny"
            version.startsWith("mlkit-cn16.0.1:")->"ML Kit Chinese 16.0.1"
            else->"未记录"
        }
    }
    @Suppress("DEPRECATION")
    suspend fun recognize(uri:Uri):OcrOutput {
        val started=SystemClock.elapsedRealtime()
        val resolver=context.contentResolver
        val orientation=resolver.openInputStream(uri)?.use {
            try { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) } catch(_:Exception) { 1 }
        }?:throw java.io.FileNotFoundException()
        val descriptor=resolver.openFileDescriptor(uri,"r")?:throw java.io.FileNotFoundException()
        descriptor.use { fd ->
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFileDescriptor(fd.fileDescriptor,null,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0) { "unsupported_image" }
            val width=bounds.outWidth;val height=bounds.outHeight
            val plan=OcrPlan.base(width,height)
            val decoder=try { BitmapRegionDecoder.newInstance(fd.fileDescriptor,false) } catch(_:Exception) { null }
            val lines=mutableListOf<OcrLine>();val tiles=JSONArray();var refinementPlanMs=0L
            val timings=PaddleTimings();var totalDecodeMs=0L
            var mergedBase:List<OcrLine>?=null
            suspend fun tile(spec:OcrRegion) {
                currentCoroutineContext().ensureActive()
                val region=Rect(spec.left,spec.top,spec.right,spec.bottom)
                val decodeStart=SystemClock.elapsedRealtime()
                val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=spec.sampleSize }
                val bitmap=if(decoder!=null) decoder.decodeRegion(region,options)
                    else BitmapFactory.decodeFileDescriptor(fd.fileDescriptor,null,options)
                requireNotNull(bitmap) { "decode_failed" }
                val decodedWidth=bitmap.width;val decodedHeight=bitmap.height
                val decodeMs=SystemClock.elapsedRealtime()-decodeStart
                totalDecodeMs+=decodeMs
                val ocrStart=SystemClock.elapsedRealtime()
                val tileId=tiles.length()
                val tileTimings=PaddleTimings()
                lines+=process(bitmap,orientation,region,width,height,tileId,tileTimings)
                timings.add(tileTimings)
                tiles.put(JSONObject().put("id",tileId).put("rawBox",boxJson(spec.box))
                    .put("box",boxJson(OcrLayout.transform(spec.box,width,height,orientation)))
                    .put("kind",spec.kind).put("reason",spec.reason).put("sampleSize",spec.sampleSize)
                    .put("decodedWidth",decodedWidth).put("decodedHeight",decodedHeight).put("timings",tileTimings.json())
                    .put("rescue",spec.kind in listOf("detail","seam")).put("decodeMs",decodeMs).put("ocrMs",SystemClock.elapsedRealtime()-ocrStart))
                require(lines.size<=8000 && lines.sumOf { it.text.length }<=250_000) { "image_too_large" }
            }
            try {
                if(decoder==null) {
                    require(plan.size==1 && plan.single().kind=="overview") { "region_decode_unavailable" }
                    tile(plan.single())
                } else {
                    for(region in plan) tile(region)
                    val planStart=SystemClock.elapsedRealtime()
                    val baseLines=OcrLayout.merge(lines)
                    val refinements=OcrPlan.refinements(baseLines,plan,width,height,orientation)
                    refinementPlanMs=SystemClock.elapsedRealtime()-planStart
                    if(refinements.isEmpty()) mergedBase=baseLines
                    for(region in refinements) tile(region)
                }
            } finally { decoder?.recycle() }
            currentCoroutineContext().ensureActive()
            val mergeStart=SystemClock.elapsedRealtime()
            val merged=mergedBase?:OcrLayout.merge(lines)
            val text=merged.joinToString("\n") { it.text }
            val blocks=JSONArray().apply { merged.forEach { put(lineJson(it)) } }
            val raw=lines.joinToString("\n") { it.text }
            val variants=OcrLayout.searchVariants(merged)
            val debug=JSONObject().put("version",VERSION).put("mode","adaptive").put("recognitionThreads",RECOGNITION_THREADS).put("width",width).put("height",height)
                .put("uprightWidth",if(orientation in 5..8) height else width).put("uprightHeight",if(orientation in 5..8) width else height)
                .put("orientation",orientation).put("strategy",if(plan.singleOrNull()?.kind=="overview") "whole-sampled" else "tiles")
                .put("baseCount",plan.size).put("refinementCount",tiles.length()-plan.size).put("refinementBudget",OcrPlan.MAX_REFINEMENTS)
                .put("tileSize",1800).put("overlap",240)
                .put("tiles",tiles).put("rawText",raw).put("rawLines",JSONArray().apply { lines.forEach { put(lineJson(it)) } })
                .put("rawLineCount",lines.size).put("mergedLineCount",merged.size).put("mergeMs",refinementPlanMs+SystemClock.elapsedRealtime()-mergeStart)
                .put("timings",timings.json(includeBatches=true).put("decodeImageMs",totalDecodeMs))
                .put("totalMs",SystemClock.elapsedRealtime()-started)
                .put("notes",JSONArray(listOf("跨行连接仅作为低权重检索候选，不修改原文", "阅读顺序为几何近似，多栏和复杂表格仍可能错序", "动图不选最佳帧，仅静态解码", "普通大图可能采样；长宽比至少 3 的长图超过 1800 像素后按原分辨率分段")))
            return OcrOutput(text,blocks.toString(),variants,debug.toString(),VERSION)
        }
    }
    private suspend fun process(original:Bitmap,orientation:Int,region:Rect,width:Int,height:Int,tile:Int,timings:PaddleTimings):List<OcrLine> {
        var rotated:Bitmap?=null
        val decodedWidth=original.width;val decodedHeight=original.height
        try {
            val matrix=Matrix().apply {
                when(orientation) {
                    2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
                    5->{ setRotate(90f);postScale(-1f,1f) };6->setRotate(90f)
                    7->{ setRotate(-90f);postScale(-1f,1f) };8->setRotate(-90f)
                }
            }
            rotated=if(matrix.isIdentity) original else Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
            return recognizer.lines(rotated,timings).map { line ->
                val decoded=OcrLayout.transform(line.box,decodedWidth,decodedHeight,orientation,inverse=true)
                val sx=region.width().toFloat()/decodedWidth;val sy=region.height().toFloat()/decodedHeight
                val local=OcrBox(decoded.left*sx,decoded.top*sy,decoded.right*sx,decoded.bottom*sy)
                val clipped=(region.left>0 && local.left<16) || (region.top>0 && local.top<16) ||
                    (region.right<width && local.right>region.width()-16) || (region.bottom<height && local.bottom>region.height()-16)
                OcrLine(line.text,OcrLayout.transform(local.shift(region.left.toFloat(),region.top.toFloat()),width,height,orientation),tile,clipped,line.confidence)
            }
        } finally {
            if(rotated!=null && rotated!==original) rotated.recycle()
            original.recycle()
        }
    }
    private fun boxJson(b:OcrBox)=JSONArray(listOf(b.left,b.top,b.right,b.bottom))
    private fun lineJson(line:OcrLine)=JSONObject().put("text",line.text).put("box",boxJson(line.box)).put("tile",line.tile)
        .put("clipped",line.clipped).put("confidence",line.confidence.toDouble())
    override fun close()=recognizer.close()
}
