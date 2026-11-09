package dev.localphotos.shitu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(key:String,folder:String="Pictures/Samples/")=Photo(key=key,uri="file:///$key",name="$key.png",folder=folder,
        modified=1,size=20,width=800,height=1000,added=100)
    @Test fun versionFiveMigrationAndMetadataRefreshKeepNotesAndMarks() {
        val name="collection-upgrade.db";context.deleteDatabase(name)
        var db=PhotoDatabase(context,name)
        val original=image("sample")
        db.upsertBatch(listOf(original),1)
        val id=db.recent(null,0,1).single().id
        db.complete(id,"cached needle","[]",8);db.saveVectors(id,listOf(floatArrayOf(1f,0f)))
        db.review(id,"other","保留说明","保留预期","旧查询","{\"query\":\"旧查询\"}");db.close()
        context.openOrCreateDatabase(name,0,null).use { old -> removeCollectionColumns(old);old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=5 }
        db=PhotoDatabase(context,name)
        try {
            assertEquals("cached needle",db.get(id)!!.text);assertTrue(db.get(id)!!.semantic)
            db.saveFeedback(id,"一个填写框就够了","新查询","{\"query\":\"新查询\"}");db.mark(listOf(id),true)
            db.upsertBatch(listOf(original.copy(name="renamed.png",taken=123)),2)
            db.close();db=PhotoDatabase(context,name)
            assertTrue(db.get(id)!!.marked);assertTrue(db.get(id)!!.semantic)
            assertEquals("保留预期",db.details(id)!!.expectedText)
            assertEquals("一个填写框就够了",db.get(id)!!.reviewNote)
            assertEquals(id,db.recent(null,0,10,options=SearchOptions(markedOnly=true)).single().id)
            db.mark(listOf(id),false)
            assertTrue(db.recent(null,0,10,options=SearchOptions(markedOnly=true)).isEmpty())
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun excludedParentHidesChildrenAcrossBrowsingSearchAndWorkWithoutClearingCache() {
        val name="collection-excluded.db";context.deleteDatabase(name)
        PhotoDatabase(context,name).use { db ->
            val path="Pictures/a_%'/"
            db.upsertBatch(listOf(image("inside","${path}Child/"),image("outside","Pictures/a_XX/")),1)
            val photos=db.recent(null,0,10)
            photos.forEach { db.complete(it.id,"needle content","[]",8);db.saveVectors(it.id,listOf(floatArrayOf(1f,0f))) }
            val inside=photos.first { it.key=="inside" }.id;val outside=photos.first { it.key=="outside" }.id
            db.setFolderExcluded(path,true)
            assertFalse(db.isEligible(inside));assertTrue(db.isEligible(outside))
            assertEquals(listOf(outside),db.recent(null,0,10).map { it.id })
            assertEquals(listOf(outside),db.lexical("needle",null,0).map { it.first.id })
            assertEquals(listOf(outside),db.nearest(floatArrayOf(1f,0f),null,0).map { it.first.id })
            db.requeue(listOf(inside));assertEquals("done",db.get(inside)!!.state)
            assertFalse(db.complete(inside,"stale OCR","[]",0))
            assertEquals("needle content",db.get(inside)!!.text)
            db.setFolderExcluded(path,false)
            assertEquals(2,db.lexical("needle",null,0).size);assertTrue(db.get(inside)!!.semantic)
        }
        context.deleteDatabase(name)
    }
    @Test fun clearingSelectionAndAllRecordsRemovesDerivedDataButKeepsUserAnnotations() {
        val name="collection-clear.db";context.deleteDatabase(name)
        PhotoDatabase(context,name).use { db ->
            db.upsertBatch(listOf(image("one"),image("two","Download/")),1)
            val photos=db.recent(null,0,10);val one=photos.first { it.key=="one" };val two=photos.first { it.key=="two" }
            photos.forEach { db.complete(it.id,"needle","[]",12,debug="{\"rawText\":\"needle\"}");db.saveVectors(it.id,listOf(floatArrayOf(1f,0f))) }
            db.mark(listOf(one.id),true);db.saveFeedback(one.id,"保留说明","needle","{\"query\":\"needle\"}")
            db.requeue(listOf(one.id));db.complete(one.id,"new needle","[]",12)
            assertEquals(1,db.clearRecognition(ClearScope(ids=listOf(one.id))))
            val cleared=db.details(one.id)!!
            assertEquals("pending",cleared.photo.state);assertEquals("",cleared.photo.text)
            assertEquals("",cleared.previousText);assertEquals("{}",cleared.debug);assertEquals("",cleared.matchDebug)
            assertTrue(cleared.photo.marked);assertEquals("保留说明",cleared.photo.reviewNote)
            assertEquals(listOf(two.id),db.lexical("needle",null,0).map { it.first.id })
            assertEquals(listOf(two.id),db.nearest(floatArrayOf(1f,0f),null,0).map { it.first.id })
            db.setFolderExcluded("Download/",true)
            assertEquals(1,db.recognitionCount(ClearScope()))
            assertEquals(1,db.clearRecognition(ClearScope()))
            assertEquals("",db.get(two.id)!!.text)
            db.setFolderExcluded("Download/",false)
            assertTrue(db.lexical("needle",null,0).isEmpty());assertTrue(db.nearest(floatArrayOf(1f,0f),null,0).isEmpty())
        }
        context.deleteDatabase(name)
    }
}
