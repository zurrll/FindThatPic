package dev.localphotos.shitu

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.LocalEmbedder
import dev.localphotos.shitu.core.SearchEngine
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reconstruct historical schemas rather than pretending new columns already existed. */
internal fun removeCollectionColumns(db:SQLiteDatabase) {
    db.execSQL("DROP TABLE visual_features")
    db.execSQL("DROP INDEX photo_marked");db.execSQL("DROP TABLE excluded_folders")
    db.execSQL("ALTER TABLE photos DROP COLUMN marked");db.execSQL("ALTER TABLE photos DROP COLUMN marked_at")
}
internal fun removeMetadataColumns(db:SQLiteDatabase) {
    removeCollectionColumns(db)
    db.execSQL("DROP INDEX photo_taken");db.execSQL("DROP INDEX photo_source")
    for(column in listOf("taken","mime","source","search_name")) db.execSQL("ALTER TABLE photos DROP COLUMN $column")
}

@RunWith(AndroidJUnit4::class)
class MetadataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(key:String,folder:String,added:Long,taken:Long,mime:String="image/png")=Photo(
        key=key,uri="file:///$key",name="$key.png",folder=folder,modified=1,size=10,width=100,height=400,
        added=added,taken=taken,mime=mime)
    @Test fun combinedScopeAppliesBeforeEveryRetrievalAndJobSelection() {
        val name="metadata-scopes.db";context.deleteDatabase(name)
        PhotoDatabase(context,name).use { db ->
            val images=listOf(image("a","DCIM/Screenshots/",300,100),image("b","Pictures/Screenshots/",200,150),
                image("c","DCIM/Camera/",100,120),image("d","DCIM/Screenshots/",400,0),
                image("e","DCIM/Screenshots/",500,130,"image/gif"),image("f","DCIM/Screenshots/",600,200))
            db.upsertBatch(images,1)
            db.recent(null,0,20).forEach { db.complete(it.id,"通用搜索测试","[]",1);db.saveVectors(it.id,listOf(floatArrayOf(1f,0f))) }
            val options=SearchOptions(folders=setOf("DCIM/Screenshots/","Pictures/Screenshots/"),source=SourceKind.SCREENSHOTS,
                dateField=DateField.TAKEN,after=100,before=200,format=ImageFormat.PNG,longOnly=true,order=ResultOrder.OLDEST)
            assertEquals(listOf("a","b"),db.recent(null,0,20,options=options).map { it.key })
            assertEquals(setOf("a","b"),db.lexical("通用",null,0,options=options).map { it.first.key }.toSet())
            assertEquals(setOf("a","b"),db.nearestPassages(floatArrayOf(1f,0f),null,0,options=options).map { it.photo.key }.toSet())
            assertEquals(listOf("d"),db.recent(null,0,20,options=options.copy(after=0,before=0,undatedOnly=true)).map { it.key })
            assertEquals(listOf("e"),db.recent(null,0,20,options=options.copy(format=ImageFormat.GIF)).map { it.key })
            assertTrue(db.recent(null,0,20,options=options.copy(source=SourceKind.CAMERA)).isEmpty())
            val a=db.recent(null,0,20).first { it.key=="a" }.id
            db.requeue(listOf(a))
            assertEquals(listOf(a),db.pendingIds(null,100,false,options))
            assertTrue(db.pendingIds(null,100,true,options,meaningsOnly=true).isEmpty())
            db.complete(a,"通用搜索测试","[]",1)
            assertEquals(listOf(a),db.pendingIds(null,100,true,options,meaningsOnly=true))
        }
        context.deleteDatabase(name)
    }
    @Test fun metadataRefreshAndVersionFourMigrationPreserveExistingResults() {
        val name="metadata-upgrade.db";context.deleteDatabase(name)
        val original=image("unusual_ＡＢ%目录","Download/",10,0)
        var db=PhotoDatabase(context,name)
        db.upsertBatch(listOf(original),1)
        val id=db.recent(null,0,1).single().id
        db.complete(id,"保留识别结果","[]",8);db.saveVectors(id,listOf(floatArrayOf(1f,0f)))
        db.review(id,"good","保留备注","","","{\"query\":\"保留\"}");db.close()
        context.openOrCreateDatabase(name,0,null).use { old -> removeMetadataColumns(old);old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=4 }
        db=PhotoDatabase(context,name)
        try {
            assertEquals("保留识别结果",db.get(id)!!.text)
            assertTrue(db.get(id)!!.semantic)
            assertEquals("保留备注",db.details(id)!!.photo.reviewNote)
            assertEquals("{\"query\":\"保留\"}",db.details(id)!!.matchDebug)
            assertEquals(id,db.lexical("ab%目录",null,0).single().first.id)
            db.upsertBatch(listOf(original.copy(taken=123,mime="image/gif",folder="Pictures/Screenshots/")),2)
            assertEquals(123L,db.get(id)!!.taken);assertEquals("image/gif",db.get(id)!!.mime)
            assertTrue(db.get(id)!!.semantic);assertEquals("保留识别结果",db.get(id)!!.text)
            assertEquals(1,db.nearest(floatArrayOf(1f,0f),null,0).size)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun unifiedSearchShowsKeywordsBeforeMeaningsAndOriginalOnlySkipsModel()=runBlocking {
        val name="unified-search.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name);val embedder=LocalEmbedder(context)
        try {
            db.upsertBatch(listOf(image("filename","Pictures/Test/",1,0),image("tail","Pictures/Test/",2,0)),1)
            val photos=db.recent(null,0,10)
            val first=photos.first { it.key=="filename" };val other=photos.first { it.key=="tail" }
            db.complete(first.id,"原始查询 目录","[]",0)
            db.complete(other.id,"无关词\n另一段","[]",0)
            var early:List<Hit>?=null
            val search=SearchEngine(db,embedder)
            // No meaning index: do not load a model just to return literal results.
            assertEquals(first.id,search.search("原始查询",null,0,true,onKeywords={early=it}).single().photo.id)
            assertEquals(first.id,early!!.single().photo.id);assertFalse(embedder.isLoaded)
            assertEquals(first.id,search.search("filename",null,0,true,options=SearchOptions(originalOnly=true)).single().photo.id)
            assertFalse(embedder.isLoaded)
            val vector=embedder.encode("原始查询",query=true)
            db.saveVectors(other.id,listOf(vector),passages=listOf("另一段"))
            early=null
            val results=search.search("原始查询",null,0,true,onKeywords={early=it})
            assertEquals(listOf(first.id),early!!.map { it.photo.id })
            assertEquals(listOf(first.id,other.id),results.map { it.photo.id })
            embedder.unload()
            assertEquals(listOf(first.id),search.search("原始查询",null,0,true,options=SearchOptions(originalOnly=true)).map { it.photo.id })
            assertFalse(embedder.isLoaded)
        } finally { embedder.unload();db.close();context.deleteDatabase(name) }
    }
}
