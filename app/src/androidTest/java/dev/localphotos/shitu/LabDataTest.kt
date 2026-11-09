package dev.localphotos.shitu

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.OcrEngine
import dev.localphotos.shitu.core.TextTools
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import dev.localphotos.shitu.data.PhotoFilter
import dev.localphotos.shitu.lab.SampleExporter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class LabDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo(key:String,uri:String="file:///unavailable")=Photo(key=key,uri=uri,name="private-name.png",folder="Pictures/Private/",modified=1,size=100,width=1080,height=6200,added=100)
    @Test fun brokenWordsFuzzyRecallReviewsAndPreviousResultSurviveReopen() {
        val name="lab-data-test.db";context.deleteDatabase(name)
        var db=PhotoDatabase(context,name)
        try {
            db.upsertBatch(listOf(photo("external:1"),photo("external:2")),1)
            val records=db.recent(null,0,10);val a=records.first().id;val b=records.last().id
            db.complete(a,"disco\nvery\n费\n用","[]",12)
            db.complete(b,"discovcry","[]",15)
            assertEquals(setOf(a,b),db.lexical("discovery",null,0).map { it.first.id }.toSet())
            assertEquals(a,db.lexical("费用",null,0).single().first.id)
            assertTrue(db.lexical("AB-123",null,0).isEmpty())
            db.review(a,"broken","边缘断词","discovery","discovery")
            assertEquals(a,db.recent(null,0,10,PhotoFilter.ISSUES).single().id)
            db.close();db=PhotoDatabase(context,name)
            assertEquals("边缘断词",db.details(a)!!.photo.reviewNote)
            db.requeue(listOf(a))
            assertEquals("disco\nvery\n费\n用",db.details(a)!!.previousText)
            assertTrue(db.lexical("费用",null,0).isEmpty())
            db.complete(a,"discovery 费用","[]",20,version="layout-test")
            assertEquals("broken",db.get(a)!!.reviewStatus)
            assertTrue(db.lexical("discovery",null,0).first().second>=10)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingOcr() {
        val name="lab-upgrade-test.db";context.deleteDatabase(name)
        val old=context.openOrCreateDatabase(name,0,null)
        old.execSQL("""CREATE TABLE photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,media_key TEXT UNIQUE NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,folder TEXT NOT NULL,modified INTEGER NOT NULL,size INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,added INTEGER NOT NULL,generation INTEGER NOT NULL,fingerprint TEXT NOT NULL,seen INTEGER NOT NULL,available INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL DEFAULT 'pending',ocr_text TEXT NOT NULL DEFAULT '',blocks TEXT NOT NULL DEFAULT '[]',error TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,ocr_ms INTEGER NOT NULL DEFAULT 0,semantic INTEGER NOT NULL DEFAULT 0)""")
        old.execSQL("CREATE VIRTUAL TABLE photo_fts USING fts4(tokens, tokenize=unicode61)")
        old.execSQL("CREATE TABLE vectors(photo_id INTEGER NOT NULL,chunk INTEGER NOT NULL,embedding BLOB NOT NULL,PRIMARY KEY(photo_id,chunk))")
        old.execSQL("INSERT INTO photos(media_key,uri,name,folder,modified,size,width,height,added,generation,fingerprint,seen,state,ocr_text) VALUES ('external:old','file:///old','old.png','Pictures/',1,20,100,100,10,0,'1:20:100:100:0',1,'done','旧图 disco'||char(10)||'very')")
        old.version=1;old.close()
        val db=PhotoDatabase(context,name)
        try {
            assertEquals("旧图 disco\nvery",db.recent(null,0,1).single().text)
            assertEquals(1,db.lexical("discovery",null,0).size)
            assertEquals(1,db.recent(null,0,10,PhotoFilter.LEGACY).size)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun sampleZipContainsOriginalBytesAndAnnotationsWithoutSourcePathsByDefault()=runBlocking {
        val name="lab-export-test.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name)
        val original=File(context.cacheDir,"lab-export-original.png").apply { writeBytes(byteArrayOf(1,2,3,4,5)) }
        var exported:File?=null
        try {
            db.upsertBatch(listOf(photo("external:export",Uri.fromFile(original).toString()),photo("external:second",Uri.fromFile(original).toString()),photo("external:unavailable")),1)
            val records=db.recent(null,0,10)
            val id=records.first { it.key=="external:export" }.id
            val second=records.first { it.key=="external:second" }.id
            val unavailable=records.first { it.key=="external:unavailable" }.id
            db.complete(id,"费用","[]",12,debug="{\"mode\":\"LAYOUT\"}",version="layout-test")
            db.review(id,"missed","搜不到费用","费用","费用")
            db.saveDescription(db.get(id)!!,dev.localphotos.shitu.core.DescriptionFeatures(FloatArray(512).apply { this[0]=1f },224,224),2)
            val result=SampleExporter(context,db).export(listOf(id,second,unavailable),true,false,"回归样本","{}")
            exported=result.file
            assertEquals(2,result.images);assertEquals(1,result.skipped)
            ZipFile(result.file).use { zip ->
                val manifest=zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                assertFalse(manifest.contains("private-name"));assertFalse(manifest.contains("Pictures/Private"));assertFalse(manifest.contains("file:///"))
                val row=JSONObject(manifest).getJSONArray("samples").getJSONObject(0)
                assertArrayEquals(original.readBytes(),zip.getInputStream(zip.getEntry(row.getString("image"))).use { it.readBytes() })
                val review=zip.getInputStream(zip.getEntry("samples/0001/review.json")).bufferedReader().use { it.readText() }
                assertTrue(review.contains("搜不到费用"));assertTrue(review.contains("missed"))
                assertEquals(64,row.getString("sha256").length)
                val descriptor=JSONObject(zip.getInputStream(zip.getEntry(row.getString("descriptionFeaturesFile"))).bufferedReader().readText())
                assertEquals(dev.localphotos.shitu.core.DescriptionFeatures.VERSION,descriptor.getString("modelVersion"))
                assertEquals(2048,android.util.Base64.decode(descriptor.getString("embeddingF32le"),android.util.Base64.DEFAULT).size)
            }
            result.file.delete();exported=null
            val textOnly=SampleExporter(context,db).export(listOf(id),false,false,"", "{}")
            exported=textOnly.file
            ZipFile(textOnly.file).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.contains("/image.") }) }
        } finally { exported?.delete();original.delete();db.close();context.deleteDatabase(name) }
    }
    @Test fun layoutOcrPreservesRepeatedLinesAndReportsRealTileTimings()=runBlocking {
        val file=File(context.cacheDir,"lab-seams.png")
        val image=Bitmap.createBitmap(1080,4000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=62f }
        canvas.drawText("discovery",70f,700f,paint)
        canvas.drawText("重复内容",70f,1200f,paint)
        canvas.drawText("重复内容",70f,2800f,paint)
        canvas.drawText("边界附近 武康路",70f,1790f,paint)
        canvas.drawText("尾部费用",70f,3900f,paint)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        try {
            OcrEngine(context).use { engine ->
                val output=engine.recognize(Uri.fromFile(file))
                val debug=JSONObject(output.debug)
                assertTrue(output.text,output.text.lowercase().contains("discovery"))
                assertTrue(output.text,output.text.contains("武康路"))
                assertTrue(output.text,TextTools.normalize(output.text).contains("费用"))
                assertEquals(output.text,2,Regex("重复内容").findAll(TextTools.normalize(output.text)).count())
                assertTrue(debug.getJSONArray("tiles").length()>1)
                assertTrue(debug.getLong("totalMs")>0)
                assertTrue(debug.getJSONArray("rawLines").length()>=debug.getInt("mergedLineCount"))
            }
        } finally { file.delete() }
    }
    @Test fun realExifRotationMatchesCanonicalLineCoordinates()=runBlocking {
        val file=File(context.cacheDir,"lab-rotation.jpg")
        val upright=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(upright);canvas.drawColor(Color.WHITE)
        canvas.drawText("discovery",60f,250f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=80f })
        val raw=Bitmap.createBitmap(upright,0,0,1000,700,Matrix().apply { setRotate(-90f) },true)
        file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG,95,it) };upright.recycle();raw.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes() }
        try {
            OcrEngine(context).use { engine ->
                val output=engine.recognize(Uri.fromFile(file))
                assertTrue(output.text,output.text.lowercase().contains("discovery"))
                val debug=JSONObject(output.debug)
                assertEquals(1000,debug.getInt("uprightWidth"));assertEquals(700,debug.getInt("uprightHeight"))
                val lines=org.json.JSONArray(output.blocks)
                val box=lines.getJSONObject(0).getJSONArray("box")
                assertTrue(box.getDouble(0)>=0 && box.getDouble(2)<=1000)
                assertTrue(box.getDouble(1)>=0 && box.getDouble(3)<=700)
            }
        } finally { file.delete() }
    }

}
