package dev.localphotos.shitu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=PhotoDatabase(context,"test-index.db")
    @After fun clean() { db.close();context.deleteDatabase("test-index.db") }
    private fun image(key:String,folder:String="Pictures/Test/",modified:Long=1)=Photo(key=key,uri="content://media/external/images/media/${key.hashCode()}",name="same.png",folder=folder,modified=modified,size=100,width=800,height=1000,added=1000)
    @Test fun foldersSameNameShortChineseAndChangedContent() {
        db.upsertBatch(listOf(image("external:1"),image("external:2","Download/")),1)
        val photos=db.recent(null,0,10)
        val one=photos.first { it.key=="external:1" };val two=photos.first { it.key=="external:2" }
        db.complete(one.id,"装修費用明细\n订单 AB-123","[]",50)
        db.complete(two.id,"租房合同","[]",50)
        assertEquals(2,db.stats().total)
        assertEquals(one.id,db.lexical("费用",null,0).first().first.id)
        assertEquals(one.id,db.lexical("费",null,0).first().first.id)
        assertEquals(one.id,db.lexical("AB-123",null,0).first().first.id)
        assertTrue(db.lexical("费用","Download/",0).isEmpty())
        assertTrue(db.lexical("%' OR 1=1 --",null,0).isEmpty())
        db.upsertBatch(listOf(image("external:1",modified=2)),2)
        assertTrue(db.lexical("费用",null,0).isEmpty())
        assertEquals("pending",db.get(one.id)!!.state)
        db.finishDiscovery(2)
        assertEquals(1,db.stats().total)
        assertEquals("租房合同",db.get(two.id)!!.text)
    }
    @Test fun vectorAndFailedWorkSurviveDiscoveryAndCanBeRetried() {
        val p=image("external:3")
        db.upsertBatch(listOf(p),1)
        val id=db.recent(null,0,1).single().id
        db.complete(id,"全屋费用明细","[]",10)
        db.saveVectors(id,listOf(floatArrayOf(1f,0f)))
        db.upsertBatch(listOf(p),2)
        assertEquals(1,db.stats().semantic)
        assertEquals(id,db.nearest(floatArrayOf(1f,0f),null,0).single().first.id)
        db.fail(id,"file_unavailable")
        assertEquals(1,db.stats().failed)
        db.retryFailures()
        assertEquals(id,db.pending(null,100,false).single().id)
    }
    @Test fun modifiedPhotoRejectsInFlightOldOcrResult() {
        db.upsertBatch(listOf(image("external:9")),1)
        val old=db.recent(null,0,1).single()
        db.upsertBatch(listOf(image("external:9",modified=2)),2)
        assertFalse(db.complete(old.id,"旧截图内容","[]",5,old.fingerprint))
        db.fail(old.id,"ocr_failed",old.fingerprint)
        assertTrue(db.lexical("截图",null,0).isEmpty())
        assertEquals("pending",db.get(old.id)!!.state)
    }
}
