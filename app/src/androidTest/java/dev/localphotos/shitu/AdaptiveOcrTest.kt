package dev.localphotos.shitu

import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdaptiveOcrTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    @Test fun highResolutionJpegUsesBoundedCallsAndCanonicalCoordinates()=runBlocking {
        val file=File(context.cacheDir,"adaptive-photo.jpg")
        for(rotated in listOf(false,true)) {
            val image=Bitmap.createBitmap(if(rotated) 3000 else 4000,if(rotated) 4000 else 3000,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image);canvas.drawColor(Color.rgb(201,187,164))
            if(rotated) { canvas.translate(0f,4000f);canvas.rotate(-90f) }
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color=Color.rgb(104,123,87);canvas.drawRect(0f,1800f,4000f,3000f,paint)
            paint.color=Color.WHITE;canvas.drawRect(200f,300f,2900f,950f,paint)
            paint.color=Color.BLACK;paint.textSize=112f;canvas.drawText("discovery 费用明细",300f,600f,paint)
            paint.textSize=28f;canvas.drawText("small label AB-789",300f,800f,paint)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,92,it) };image.recycle()
            if(rotated) ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
            try {
                OcrEngine(context).use { engine ->
                    val output=engine.recognize(Uri.fromFile(file))
                    val debug=JSONObject(output.debug);val tiles=debug.getJSONArray("tiles")
                    assertTrue(output.text,output.text.lowercase().contains("discovery"))
                    assertEquals(1,debug.getInt("baseCount"));assertTrue(tiles.length() in 1..3)
                    assertEquals(2,tiles.getJSONObject(0).getInt("sampleSize"))
                    assertEquals(4000,debug.getInt("uprightWidth"));assertEquals(3000,debug.getInt("uprightHeight"))
                    val lines=org.json.JSONArray(output.blocks)
                    val line=(0 until lines.length()).map { lines.getJSONObject(it) }.first { it.getString("text").lowercase().contains("discovery") }
                    val box=line.getJSONArray("box")
                    assertTrue(box.toString(),box.getDouble(0) in 200.0..500.0 && box.getDouble(1) in 400.0..650.0)
                }
            } finally { file.delete() }
        }
    }
    @Test fun searchDecisionSurvivesReviewAndVersionThreeUpgrade() {
        val name="match-upgrade.db";context.deleteDatabase(name)
        var db=PhotoDatabase(context,name)
        db.upsertBatch(listOf(Photo(key="test",uri="file:///test",name="test.png",folder="Test/",modified=1,size=100,width=1080,height=2400,added=1)),1)
        val id=db.recent(null,0,1).single().id
        db.complete(id,"保留文字","[]",0);db.saveVectors(id,listOf(floatArrayOf(1f,0f)),passages=listOf("保留文字"));db.close()
        context.openOrCreateDatabase(name,0,null).use { old -> removeMetadataColumns(old);old.execSQL("ALTER TABLE photos DROP COLUMN match_debug");old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=3 }
        db=PhotoDatabase(context,name)
        try {
            assertTrue(db.get(id)!!.semantic);assertEquals("保留文字",db.get(id)!!.text)
            val trace="{\"query\":\"复现词\",\"similarity\":0.43,\"cutoff\":0.4}"
            db.review(id,"wrong","不相关","","复现词",trace);db.close();db=PhotoDatabase(context,name)
            assertEquals(trace,db.details(id)!!.matchDebug)
            db.review(id,"wrong","补充备注","","复现词")
            assertEquals(trace,db.details(id)!!.matchDebug)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
