package dev.localphotos.shitu

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.VisualFeatures
import dev.localphotos.shitu.data.*
import dev.localphotos.shitu.lab.SampleExporter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

class LargeCollectionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(i:Int,uri:String="file:///no-image-$i",folder:String="Pictures/Large/")=Photo(key="large:$i",uri=uri,name="$i.png",folder=folder,modified=1,size=1,width=224,height=224,added=i.toLong())
    @Test fun selectFiveHundredConsecutivelyAndUnlimitedMarksAndClearRemainScoped() {
        val name="large-selection.db";context.deleteDatabase(name)
        PhotoDatabase(context,name).use { db ->
            repeat(13) { batch -> db.upsertBatch((1..100).map { image(batch*100+it) },1) }
            db.upsertBatch(listOf(image(2000,folder="Pictures/Excluded/")),1);db.setFolderExcluded("Pictures/Excluded/",true)
            val first=db.sampleIds(500,emptySet(),PhotoFilter.ALL,SearchOptions())
            val second=db.sampleIds(500,first.toSet(),PhotoFilter.ALL,SearchOptions())
            assertEquals(500,first.size);assertEquals(500,second.size);assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
            assertEquals((1300L downTo 801L).toList(),first.map { db.get(it)!!.added })
            assertEquals((800L downTo 301L).toList(),second.map { db.get(it)!!.added })
            val oldest=db.sampleIds(500,emptySet(),PhotoFilter.ALL,SearchOptions(order=ResultOrder.OLDEST))
            assertEquals((1L..500L).toList(),oldest.map { db.get(it)!!.added })
            val ids=first+second;db.mark(ids,true)
            assertEquals(1000,db.stats().marked)
            val limited=db.sampleIds(500,emptySet(),PhotoFilter.ALL,SearchOptions(after=900,markedOnly=true))
            assertTrue(limited.all { db.get(it)!!.added>=900 && db.get(it)!!.marked })
            ids.forEach { db.complete(it,"retained-original","[]",1) }
            assertEquals(1000,db.recognitionCount(ClearScope(ids=ids+ids.take(10))));assertEquals(1000,db.clearRecognition(ClearScope(ids=ids)))
            assertEquals(1000,db.stats().marked);assertEquals(0,db.stats().done)
        };context.deleteDatabase(name)
    }
    @Test fun exportMoreThanFiveHundredAndMoreThanTwoHundredMiBWithFeatureRecordsAndProgress()=runBlocking<Unit> {
        val name="large-export.db";context.deleteDatabase(name)
        val folder=File(context.cacheDir,"large-export-test").apply { mkdirs() }
        val small=File(folder,"tiny.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        val large=File(folder,"large.bin");RandomAccessFile(large,"rw").use { it.setLength(201L*1024*1024) }
        var result:SampleExporter.Result?=null
        PhotoDatabase(context,name).use { db ->
            try {
                db.upsertBatch((1..501).map { image(it,Uri.fromFile(if(it==501) large else small).toString()).copy(name=if(it==501) "large.bin" else "$it.png") },1)
                val photos=db.recent(null,0,600);val id=photos.last().id
                db.saveVisual(db.get(id)!!,VisualFeatures(FloatArray(1280).apply { this[0]=1f },0,ByteArray(768),224,224),1)
                var done=0;var total=0;var bytes=0L
                result=SampleExporter(context,db).export(photos.map { it.id },true,false,"","{}") { d,t,b ->done=d;total=t;bytes=b }
                assertEquals(501,result!!.images);assertEquals(0,result!!.skipped);assertEquals(501,done);assertEquals(501,total);assertTrue(bytes>200L*1024*1024)
                ZipFile(result!!.file).use { zip ->
                    val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    assertEquals(501,manifest.getJSONArray("samples").length())
                    assertEquals(201L*1024*1024,zip.getEntry("samples/0001/image.bin").size)
                    val record=zip.getEntry("samples/0501/visual-features.json")
                    assertNotNull(record);assertEquals(1280*4,android.util.Base64.decode(JSONObject(zip.getInputStream(record).bufferedReader().readText()).getString("embeddingF32le"),0).size)
                }
            } finally { result?.file?.delete();folder.deleteRecursively() }
        };context.deleteDatabase(name)
    }
    @Test fun cancellingLargeCopyCleansPartialZipAndStagedOriginal()=runBlocking<Unit> {
        val name="large-cancel.db";context.deleteDatabase(name)
        val source=File(context.cacheDir,"cancel-source.bin");RandomAccessFile(source,"rw").use { it.setLength(16L*1024*1024) }
        PhotoDatabase(context,name).use { db ->
            db.upsertBatch(listOf(image(1,Uri.fromFile(source).toString())),1)
            val id=db.recent(null,0,1).single().id
            try {
                var cancelled=false
                try { SampleExporter(context,db).export(listOf(id),true,false,"","{}") { _,_,bytes -> if(bytes>0) throw CancellationException("test cancellation") } }
                catch(_:CancellationException) { cancelled=true }
                assertTrue(cancelled)
                assertFalse(File(context.cacheDir,"samples").listFiles().orEmpty().any { it.name.endsWith(".partial") || it.name.startsWith("work-") })
                assertTrue(source.exists())
            } finally { source.delete() }
        };context.deleteDatabase(name)
    }
}
