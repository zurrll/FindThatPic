package dev.localphotos.shitu

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
class VisualRetrievalTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private fun image(key:String,uri:String="file:///not-decoded-$key",folder:String="Pictures/Visual/",modified:Long=1)=Photo(key=key,uri=uri,name="$key.png",folder=folder,modified=modified,size=100,width=224,height=224,added=10,taken=10,mime="image/png")
    private fun fake(axis:Int=0)=VisualFeatures(FloatArray(1280).apply { this[axis]=1f },123,ByteArray(768) { 90 },224,224)
    @Test fun realModelReferenceCompressionCropAndExifOrientation()=runBlocking {
        val folder=File(context.cacheDir,"visual-test-model").apply { mkdirs() }
        val encoder=VisualEmbedder(context)
        try {
            val astronaut=File(folder,"astronaut.png");instrument.context.assets.open("visual/astronaut.png").use { input -> astronaut.outputStream().use(input::copyTo) }
            val cat=File(folder,"chelsea.png");instrument.context.assets.open("visual/chelsea.png").use { input -> cat.outputStream().use(input::copyTo) }
            val a=encoder.encode(Uri.fromFile(astronaut))
            val reference=JSONObject(instrument.context.assets.open("visual/reference.json").bufferedReader().readText()).getJSONArray("embedding")
            val expected=FloatArray(reference.length()) { reference.getDouble(it).toFloat() }
            assertEquals(1280,a.embedding.size);assertTrue(TextTools.dot(expected,a.embedding)>0.99999f)
            val source=BitmapFactory.decodeFile(astronaut.path)
            val smaller=Bitmap.createScaledBitmap(source,140,140,true)
            val compressed=File(folder,"compressed.jpg");compressed.outputStream().use { smaller.compress(Bitmap.CompressFormat.JPEG,50,it) };smaller.recycle()
            val crop=Bitmap.createBitmap(source,12,12,200,200)
            val cropped=File(folder,"crop.png");cropped.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) };crop.recycle()
            val rotated=Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(-90f) },true)
            val exif=File(folder,"oriented.jpg");exif.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG,95,it) };rotated.recycle();source.recycle()
            ExifInterface(exif).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes() }
            val b=encoder.encode(Uri.fromFile(compressed));val c=encoder.encode(Uri.fromFile(cat));val d=encoder.encode(Uri.fromFile(cropped));val e=encoder.encode(Uri.fromFile(exif))
            val copyScore=VisualMath.score(a,b);val catScore=VisualMath.score(a,c);val cropScore=VisualMath.score(a,d)
            assertTrue(copyScore.cosine>catScore.cosine);assertTrue("fingerprint should keep the resized/compressed candidate",copyScore.close);assertTrue(cropScore.cosine>catScore.cosine)
            assertTrue("orientation must be honored",VisualMath.score(a,e).cosine>0.98f)
            val name="visual-real-retrieval.db";context.deleteDatabase(name)
            PhotoDatabase(context,name).use { db ->
                db.upsertBatch(listOf("reference","compressed","unrelated","cropped","oriented").mapIndexed { i,key -> image(key).copy(taken=10+i*86400L) },1)
                val photos=db.recent(null,0,10).associateBy { it.key }
                listOf("reference" to a,"compressed" to b,"unrelated" to c,"cropped" to d,"oriented" to e).forEach { (key,features) -> db.saveVisual(photos.getValue(key),features,1) }
                val matches=VisualSearch(db,encoder).find(photos.getValue("reference").id)
                assertEquals(setOf("compressed","cropped","oriented"),matches.map { it.photo.key }.toSet())
                assertEquals("外观很接近",matches.first { it.photo.key=="compressed" }.reason)
            }
            context.deleteDatabase(name)
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nVISUAL_REAL_FIXTURES compressed=${copyScore.cosine} hash=${copyScore.hashDistance} appearance=${copyScore.appearance} cropped=${cropScore.cosine} unrelated=${catScore.cosine} exif=${VisualMath.score(a,e).cosine}\n") })
        } finally { encoder.unload();folder.deleteRecursively() }
    }
    @Test fun visualCacheIsIndependentFingerprintGuardedAndExclusionsApply()=runBlocking {
        val name="visual-cache-test.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name);val encoder=VisualEmbedder(context)
        try {
            val a=image("a");val b=image("b",folder="Pictures/Hidden/")
            db.upsertBatch(listOf(a,b),1)
            val ids=db.recent(null,0,10).associateBy { it.key };val one=ids.getValue("a");val two=ids.getValue("b")
            db.complete(one.id,"保留 OCR 和含义","[]",2);db.saveVectors(one.id,listOf(floatArrayOf(1f,0f)));db.mark(listOf(one.id),true)
            db.saveVisual(one,fake(),12);db.saveVisual(two,fake(),10)
            assertEquals(2,db.visualStats().ready)
            db.clearRecognition(ClearScope(ids=listOf(one.id)))
            assertNotNull(db.visual(one.id));assertTrue(db.get(one.id)!!.marked)
            assertEquals(1,VisualSearch(db,encoder).find(one.id).size)
            assertTrue(db.visualStats(SearchOptions(longOnly=true)).total==0)
            db.setFolderExcluded("Pictures/Hidden/",true)
            assertEquals(1,db.visualStats().ready);assertTrue(VisualSearch(db,encoder).find(one.id).isEmpty())
            assertFalse(db.saveVisual(two,fake(),1));db.setFolderExcluded("Pictures/Hidden/",false)
            db.upsertBatch(listOf(a.copy(taken=200,name="renamed.png")),2)
            assertNotNull(db.visual(one.id))
            assertTrue(db.saveVisual(two,null,3,"image_unavailable"))
            assertTrue(db.visualAttempted(two.id));assertEquals(1,db.visualStats().failed)
            assertTrue(db.pendingVisualIds(100).isEmpty());assertEquals(listOf(two.id),db.pendingVisualIds(100,retry=true))
            db.saveVisual(two,fake(),2)
            db.upsertBatch(listOf(a.copy(modified=2)),3)
            assertNull(db.visual(one.id));assertFalse(db.saveVisual(one,fake(),1))
            assertEquals(listOf(one.id),db.pendingVisualIds(100))
            db.writableDatabase.execSQL("UPDATE visual_features SET version='old-model'")
            assertEquals(0,db.visualStats().ready);assertNull(db.visual(two.id))
            assertEquals(2,db.pendingVisualIds(100).size)
        } finally { encoder.unload();db.close();context.deleteDatabase(name) }
    }
    @Test fun versionSixMigrationPreservesAllTextDataAndAddsAnEmptyVisualIndex() {
        val name="visual-migration.db";context.deleteDatabase(name)
        var db=PhotoDatabase(context,name)
        db.upsertBatch(listOf(image("old")),1)
        val p=db.recent(null,0,1).single()
        db.complete(p.id,"原有文字","[]",2);db.saveVectors(p.id,listOf(floatArrayOf(1f,0f)))
        db.mark(listOf(p.id),true);db.saveFeedback(p.id,"原有说明","查询","{}");db.setFolderExcluded("Download/Hidden/",true);db.close()
        context.openOrCreateDatabase(name,0,null).use { old -> old.execSQL("DROP TABLE visual_features");old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=6 }
        db=PhotoDatabase(context,name)
        try {
            assertEquals("原有文字",db.get(p.id)!!.text);assertTrue(db.get(p.id)!!.semantic);assertTrue(db.get(p.id)!!.marked)
            assertEquals("原有说明",db.get(p.id)!!.reviewNote);assertEquals(setOf("Download/Hidden/"),db.excludedFolders())
            assertEquals(0,db.visualStats().ready);assertEquals(1,db.pendingVisualIds(100).size)
            assertEquals(p.id,db.lexical("原有",null,0).single().first.id)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun tenThousandFeatureRowsCanBeSearchedWithoutDecodingAnyOriginal()=runBlocking {
        val name="visual-scale.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name);val encoder=VisualEmbedder(context)
        try {
            repeat(100) { batch -> db.upsertBatch((0..99).map { image("scale-${batch*100+it}").copy(added=(batch*100+it).toLong()) },1) }
            val rows=db.recent(null,0,10000)
            db.writableDatabase.beginTransaction()
            try {
                rows.forEachIndexed { i,p -> db.saveVisual(p,if(i==9999) fake(1) else fake(),1) }
                db.writableDatabase.setTransactionSuccessful()
            } finally { db.writableDatabase.endTransaction() }
            val start=SystemClock.elapsedRealtime()
            val hits=VisualSearch(db,encoder).find(rows.first().id)
            val ms=SystemClock.elapsedRealtime()-start
            assertEquals(40,hits.size);assertFalse(hits.any { it.photo.id in setOf(rows.first().id,rows.last().id) })
            assertEquals(10000,db.visualStats().ready)
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nVISUAL_SCALE cached_entries=10000 query_ms=$ms; no image file exists, no inference required\n") })
        } finally { encoder.unload();db.close();context.deleteDatabase(name) }
    }
}
