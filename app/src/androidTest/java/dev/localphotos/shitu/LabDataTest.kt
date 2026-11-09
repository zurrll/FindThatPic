package dev.localphotos.shitu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import dev.localphotos.shitu.data.PhotoFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
        old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=1;old.close()
        val db=PhotoDatabase(context,name)
        try {
            assertEquals("旧图 disco\nvery",db.recent(null,0,1).single().text)
            assertEquals(1,db.lexical("discovery",null,0).size)
            assertEquals(1,db.recent(null,0,10,PhotoFilter.LEGACY).size)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
