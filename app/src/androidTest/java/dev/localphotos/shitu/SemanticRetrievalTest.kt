package dev.localphotos.shitu

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.LocalEmbedder
import dev.localphotos.shitu.core.SearchEngine
import dev.localphotos.shitu.core.TextTools
import dev.localphotos.shitu.core.SemanticText
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixed synthetic OCR corpus: tests text retrieval separately from OCR accuracy. */
@RunWith(AndroidJUnit4::class)
class SemanticRetrievalTest {
    @Test fun fixedScreenshotQueries()=runBlocking { runFixture("semantic-cases.json","current") }
    @Test fun broadTopicsAndMinorityWords()=runBlocking { runFixture("semantic-topics.json","topics") }
    private suspend fun runFixture(asset:String,defaultLabel:String) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val fixture=JSONObject(instrument.context.assets.open(asset).bufferedReader().use { it.readText() })
        val db=PhotoDatabase(context,"semantic-cases.db")
        val embedder=LocalEmbedder(context)
        val search=SearchEngine(db,embedder)
        val report=JSONObject();val results=JSONArray();val mapping=mutableMapOf<Long,String>()
        val started=SystemClock.elapsedRealtime()
        try {
            val docs=fixture.getJSONArray("documents")
            for(i in 0 until docs.length()) {
                val d=docs.getJSONObject(i)
                db.upsertBatch(listOf(Photo(key=d.getString("id"),uri="content://synthetic/$i",name="$i.png",folder="fixtures/",modified=1,size=100,width=1080,height=2400,added=i.toLong())),1)
            }
            for(photo in db.recent(null,0,100)) {
                val d=(0 until docs.length()).map { docs.getJSONObject(it) }.first { it.getString("id")==photo.key }
                val text=d.getString("text");db.complete(photo.id,text,"[]",0)
                val chunks=SemanticText.passages(text)
                db.saveVectors(photo.id,chunks.map { embedder.encode(TextTools.embeddingText(it)) },passages=chunks)
                mapping[photo.id]=photo.key
            }
            val queries=fixture.getJSONArray("queries")
            for(i in 0 until queries.length()) {
                val q=queries.getJSONObject(i);val query=q.getString("query")
                val relevant=q.getJSONArray("relevant").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val hits=search.search(query,null,0,true)
                val rank=hits.indexOfFirst { mapping[it.photo.id] in relevant }.let { if(it<0) 0 else it+1 }
                results.put(JSONObject().put("query",query).put("split",q.getString("split")).put("rank",rank)
                    .put("relevant",JSONArray(relevant.toList())).put("top",JSONArray(hits.take(5).map { mapping.getValue(it.photo.id) })))
            }
            report.put("description",fixture.getString("description")).put("documents",docs.length()).put("queries",results).put("totalMs",SystemClock.elapsedRealtime()-started)
            val label=if(defaultLabel=="current") InstrumentationRegistry.getArguments().getString("semanticLabel")?:defaultLabel else defaultLabel
            File(context.getExternalFilesDir(null),"semantic-$label.json").writeText(report.toString(2))
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nSEMANTIC_REPORT ${report}\n") })
            for(i in 0 until results.length()) {
                val r=results.getJSONObject(i)
                if(r.getJSONArray("relevant").length()>0) assertTrue("Missing from top 5: $r",r.getInt("rank") in 1..5)
                else assertEquals("Unrelated query should be empty: $r",0,r.getJSONArray("top").length())
                if(r.getString("split") in listOf("minority_word","exact")) assertEquals("Literal match must rank first: $r",1,r.getInt("rank"))
            }
        } finally { embedder.unload();db.close();context.deleteDatabase("semantic-cases.db") }
    }
}
