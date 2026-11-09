package dev.localphotos.shitu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticDataTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private fun photo(key:String,folder:String="Pictures/Test/")=Photo(key=key,uri="file:///$key",name="$key.png",folder=folder,modified=1,size=100,width=1080,height=2400,added=1)
    @Test fun tokenizerAndVectorsAgreeWithPinnedUpstreamReference()=runBlocking {
        val fixture=JSONObject(instrument.context.assets.open("tokenizer-reference.json").bufferedReader().use { it.readText() })
        val vocab=context.assets.open("models/vocab.txt").bufferedReader().useLines { it.withIndex().associate { it.value to it.index } }
        val tokenizer=WordPiece(vocab);val embedder=LocalEmbedder(context)
        try {
            val cases=fixture.getJSONArray("cases")
            for(i in 0 until cases.length()) {
                val c=cases.getJSONObject(i);val text=c.getString("text")
                val ids=c.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
                assertArrayEquals(text,ids,tokenizer.encode(text))
                val expected=c.getJSONArray("vector").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val actual=embedder.encode(text)
                assertTrue("Reference vector mismatch: $text",TextTools.dot(expected,actual)>0.99999f)
            }
        } finally { embedder.unload() }
    }
    @Test fun matchedTailPassageIsDisplayedAndScopeStillApplies()=runBlocking {
        val name="semantic-tail.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name);val embedder=LocalEmbedder(context)
        try {
            db.upsertBatch(listOf(photo("tail"),photo("distractor")),1)
            val id=db.recent(null,0,10).first { it.key=="tail" }.id
            val head="会议签到与值日安排。".repeat(80)
            val tail="换新手机后保留微信对话：在旧手机设置中选择聊天记录迁移与备份。"
            db.complete(id,head+tail,"[]",0)
            val query="聊天怎么搬到另一部手机"
            val vector=embedder.encode(TextTools.embeddingText(query),query=true)
            db.saveVectors(id,listOf(vector.map { -it }.toFloatArray(),vector),passages=listOf(head.take(200),tail))
            val search=SearchEngine(db,embedder)
            val hits=search.search(query,null,0,true)
            assertEquals(id,hits.first().photo.id)
            assertTrue(hits.first().snippet, hits.first().snippet.contains("迁移"))
            assertFalse(hits.first().snippet.contains("签到"))
            // A minority word after 480 characters must survive in either search mode.
            for(semantic in listOf(false,true)) {
                val literal=search.search("迁移",null,0,semantic)
                assertEquals(id,literal.first().photo.id)
                assertTrue(literal.first().snippet.contains("迁移"))
            }
            assertTrue(search.search(query,"Other/",0,true).isEmpty())
            assertTrue(search.search(query,null,2,true).isEmpty())
            assertTrue(search.search(query,null,0,true,filter=PhotoFilter.ISSUES).isEmpty())
            db.review(id,"missed","","",query)
            assertEquals(id,search.search(query,null,0,true,filter=PhotoFilter.ISSUES).first().photo.id)
            val other=db.recent(null,0,10).first { it.key=="distractor" }.id
            val networkQuery="电脑网络故障"
            val networkVector=embedder.encode(networkQuery,query=true)
            db.complete(other,"电脑网络促销，新款路由器优惠。","[]",0)
            assertTrue(db.lexical(networkQuery,null,0).any { it.first.id==other })
            db.saveVectors(other,listOf(networkVector.map { -it }.toFloatArray()),passages=listOf("电脑网络促销，新款路由器优惠。"))
            db.saveVectors(id,listOf(networkVector),passages=listOf("无线连接失败，排查连接问题。"))
            assertEquals(listOf(id),search.search(networkQuery,null,0,true).map { it.photo.id })
        } finally { embedder.unload();db.close();context.deleteDatabase(name) }
    }
    @Test fun versionTwoUpgradeOnlyInvalidatesMeaningIndex() {
        val name="semantic-upgrade.db";context.deleteDatabase(name)
        var db=PhotoDatabase(context,name)
        db.upsertBatch(listOf(photo("old")),1)
        val id=db.recent(null,0,1).single().id
        db.complete(id,"租房合同 押金","[]",12);db.review(id,"wrong","保留评价","押金","租房")
        db.saveVectors(id,listOf(floatArrayOf(1f,0f)),passages=listOf("旧段落"));db.close()
        context.openOrCreateDatabase(name,0,null).use { old ->
            old.execSQL("ALTER TABLE vectors RENAME TO temp_vectors")
            old.execSQL("CREATE TABLE vectors(photo_id INTEGER NOT NULL,chunk INTEGER NOT NULL,embedding BLOB NOT NULL,PRIMARY KEY(photo_id,chunk))")
            old.execSQL("INSERT INTO vectors SELECT photo_id,chunk,embedding FROM temp_vectors")
            old.execSQL("DROP TABLE temp_vectors")
            old.execSQL("UPDATE index_versions SET version='bge-75c43b0:wordpiece-v1:cls256' WHERE name='semantic'")
            removeMetadataColumns(old);old.execSQL("ALTER TABLE photos DROP COLUMN match_debug")
            old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=2
        }
        db=PhotoDatabase(context,name)
        try {
            assertEquals("租房合同 押金",db.get(id)!!.text)
            assertEquals("保留评价",db.details(id)!!.photo.reviewNote)
            assertFalse(db.get(id)!!.semantic)
            assertEquals(1,db.lexical("押金",null,0).size)
            assertEquals(listOf(id),db.pendingIds(null,100,true))
            assertTrue(db.pendingIds(null,100,false).isEmpty())
            assertTrue(db.nearest(floatArrayOf(1f,0f),null,0).isEmpty())
            db.saveVectors(id,listOf(floatArrayOf(1f,0f)),passages=listOf("押金新段落"))
            assertEquals("押金新段落",db.nearestPassages(floatArrayOf(1f,0f),null,0).single().passage)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
