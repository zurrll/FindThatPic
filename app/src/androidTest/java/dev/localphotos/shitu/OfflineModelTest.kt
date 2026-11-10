package dev.localphotos.shitu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.LocalEmbedder
import dev.localphotos.shitu.core.OcrEngine
import dev.localphotos.shitu.core.PaddleOcr
import dev.localphotos.shitu.core.PaddleTimings
import dev.localphotos.shitu.core.TextTools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineModelTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun batchedOcrKeepsTextAndCoordinatesInTheOriginalOrder()=runBlocking {
        val image=Bitmap.createBitmap(480,520,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=40f }
            repeat(6) { canvas.drawText("测试 TEXT",60f,65f+75f*it,paint) }
            val serialTimes=PaddleTimings();val batchTimes=PaddleTimings()
            val serial=PaddleOcr(context,maxBatch=1).use { it.lines(image,serialTimes) }
            val batched=PaddleOcr(context).use { it.lines(image,batchTimes) }
            assertEquals(6,serial.size)
            assertEquals(serial.map { it.text to it.box },batched.map { it.text to it.box })
            assertTrue(batched.zip(serial).all { (a,b) -> kotlin.math.abs(a.confidence-b.confidence)<0.0001f })
            assertTrue(batchTimes.recognitionCalls<serialTimes.recognitionCalls)
            val batches=batchTimes.json(includeBatches=true).getJSONArray("recognitionBatches")
            assertEquals(batchTimes.recognitionCalls,batches.length())
            assertEquals(6,(0 until batches.length()).sumOf { batches.getJSONObject(it).getInt("lines") })
            assertTrue((0 until batches.length()).all {
                val batch=batches.getJSONObject(it)
                batch.getInt("inputWidth")>0 && batch.getInt("steps")>0
            })
        } finally { image.recycle() }
    }
    @Test fun chineseOcrReadsBothEndsOfLongScreenshotOffline()=runBlocking {
        val image=Bitmap.createBitmap(1080,6200,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=58f }
        canvas.drawText("装修费用明细",60f,200f,paint)
        canvas.drawText("订单 AB-123",60f,330f,paint)
        canvas.drawText("尾部校验 武康路",60f,6000f,paint)
        val file=File(context.cacheDir,"long-ocr.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        try {
            OcrEngine(context).use { ocr ->
                val result=ocr.recognize(Uri.fromFile(file))
                val text=result.text
                assertEquals(4,JSONObject(result.debug).getInt("recognitionThreads"))
                assertTrue(text,text.contains("装修"))
                assertTrue(text,text.contains("武康路"))
                assertTrue(text,text.contains("AB-123"))
            }
        } finally { file.delete() }
    }
    @Test fun localChineseEmbeddingRanksRelatedMeaningAboveUnrelatedText()=runBlocking {
        val embedder=LocalEmbedder(context)
        try {
            val query=embedder.encode("装修预算",query=true)
            val related=embedder.encode("全屋费用明细：柜体、瓷砖、水电和人工支出。")
            val unrelated=embedder.encode("今天去公园遛狗，看到一只可爱的小猫。")
            assertEquals(512,query.size)
            val good=TextTools.dot(query,related);val bad=TextTools.dot(query,unrelated)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("stream","\nEMBEDDING_PROBE related=$good unrelated=$bad\n") })
            assertTrue("related=$good unrelated=$bad",good>bad+0.05f)
        } finally { embedder.unload() }
    }
}
