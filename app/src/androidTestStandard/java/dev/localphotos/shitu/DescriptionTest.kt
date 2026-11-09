package dev.localphotos.shitu

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DescriptionTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    private fun photo(i:Int)=Photo(key="description-$i",uri="file:///never-decode-$i",name="$i.png",folder="Pictures/Description/",modified=1,size=1,width=224,height=224,added=i.toLong())
    private fun feature(axis:Int=0)=DescriptionFeatures(FloatArray(DescriptionFeatures.DIMENSIONS).apply { this[axis]=1f },224,224)
    @Test fun schemaSevenUpgradeKeepsExistingDataAndMissingStagesStayIndependent() {
        val name="description-migration.db";app.deleteDatabase(name)
        var db=PhotoDatabase(app,name);db.upsertBatch((1..4).map(::photo),1);val images=db.recent(null,0,10).sortedBy { it.added };val p=images[0]
        db.complete(p.id,"保留原文字","[]",3);db.saveVectors(p.id,listOf(FloatArray(512).apply { this[0]=1f }));db.mark(listOf(p.id),true);db.saveFeedback(p.id,"保留说明","查询","{}")
        db.saveVisual(p,VisualFeatures(FloatArray(1280).apply { this[0]=1f },0,ByteArray(768),224,224),1);db.close()
        app.openOrCreateDatabase(name,0,null).use { old ->old.execSQL("DROP TABLE description_features");old.execSQL("ALTER TABLE photos DROP COLUMN meaning_ms");old.version=7 }
        db=PhotoDatabase(app,name)
        try {
            assertEquals("保留原文字",db.get(p.id)!!.text);assertTrue(db.get(p.id)!!.semantic);assertTrue(db.get(p.id)!!.marked);assertEquals("保留说明",db.get(p.id)!!.reviewNote);assertNotNull(db.visual(p.id))
            assertFalse(db.searchDiagnostics(p.id).has("meaningMs"))
            db.saveVectors(p.id,listOf(FloatArray(512).apply { this[0]=1f }),elapsedMs=42)
            assertEquals(42L,db.searchDiagnostics(p.id).getLong("meaningMs"))
            assertEquals(1,db.preparationStats().ocr);assertEquals(0,db.preparationStats().description)
            assertFalse(p.id in db.pendingPreparationStage(images.map { it.id },"ocr",false));assertFalse(p.id in db.pendingPreparationStage(images.map { it.id },"similar",false))
            assertTrue(p.id in db.pendingPreparationStage(images.map { it.id },"description",false))
            assertTrue(db.saveDescription(p,feature(),2));assertEquals(1,db.preparationStats().complete)
            val second=images[1];db.saveDescription(second,null,1,"image_unavailable")
            assertFalse(second.id in db.pendingPreparationStage(images.map { it.id },"description",false));assertTrue(second.id in db.pendingPreparationStage(images.map { it.id },"description",true))
            db.clearRecognition(ClearScope(ids=listOf(p.id)));assertNotNull(db.description(p.id));assertNotNull(db.visual(p.id));assertEquals(0,db.preparationStats().complete)
            db.upsertBatch(listOf(photo(1).copy(modified=2)),2);assertNull(db.description(p.id));assertNull(db.visual(p.id))
            db.setFolderExcluded("Pictures/Description/",true);assertEquals(0,db.preparationStats().total)
            assertTrue(db.pendingPreparationIds(100,SearchOptions(),PreparationKinds()).isEmpty())
        } finally { db.close();app.deleteDatabase(name) }
    }
    @Test fun upgradingFromB16OnlyRequeuesTheDescriptionFeature() {
        val name="description-rn50-upgrade.db";app.deleteDatabase(name)
        PhotoDatabase(app,name).use { db ->
            db.upsertBatch(listOf(photo(1)),1)
            val photo=db.recent(null,0,1).single()
            val ids=listOf(photo.id)
            db.complete(photo.id,"保留原文字","[]",3)
            db.saveVectors(photo.id,listOf(FloatArray(512).apply { this[0]=1f }))
            db.saveVisual(photo,VisualFeatures(FloatArray(1280).apply { this[0]=1f },0,ByteArray(768),224,224),1)
            db.writableDatabase.execSQL("INSERT INTO description_features VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(photo.id,photo.fingerprint,"cnclip-f269048:q8:fit224-bicubic:bert52-lower:512-v1",
                    TextTools.pack(FloatArray(512).apply { this[0]=1f }),224,224,"",5))

            val old=db.preparationStats()
            assertEquals(1,old.ocr);assertEquals(1,old.meaning);assertEquals(1,old.similar)
            assertEquals(0,old.description);assertEquals(0,old.complete)
            assertNull(db.description(photo.id))
            db.eachDescription(SearchOptions(),PhotoFilter.ALL) { _,_,_ -> fail("Old vector must not participate in RN50 search") }
            for(stage in listOf("ocr","meaning","similar")) assertTrue(db.pendingPreparationStage(ids,stage,false).isEmpty())
            assertEquals(ids.toSet(),db.pendingPreparationStage(ids,"description",false))

            assertTrue(db.saveDescription(photo,feature(),2))
            assertEquals(DescriptionFeatures.DIMENSIONS,db.description(photo.id)?.embedding?.size)
            assertEquals(1,db.preparationStats().complete)
            assertTrue(db.pendingPreparationStage(ids,"description",false).isEmpty())
        }
        app.deleteDatabase(name)
    }
    @Test fun tenThousandDescriptionVectorsRespectFiltersAndNeedNoImageDecode()=runBlocking<Unit> {
        val name="description-scale.db";app.deleteDatabase(name)
        PhotoDatabase(app,name).use { db ->
            repeat(100) { batch ->db.upsertBatch((1..100).map { photo(batch*100+it) },1) }
            val query=app.descriptionEmbedder.text("一只猫")
            val rows=db.recent(null,0,10_000)
            db.writableDatabase.beginTransaction()
            try {
                rows.forEach { p ->db.saveDescription(p,DescriptionFeatures(query,224,224),1) }
                db.writableDatabase.setTransactionSuccessful()
            } finally { db.writableDatabase.endTransaction() }
            assertEquals(10_000,db.preparationStats().description)
            val start=android.os.SystemClock.elapsedRealtime()
            val hits=DescriptionSearch(db,app.descriptionEmbedder).find("一只猫",SearchOptions(),PhotoFilter.ALL)
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(100,hits.size)
            val id=hits.first().photo.id;db.mark(listOf(id),true)
            assertEquals(id,DescriptionSearch(db,app.descriptionEmbedder).find("一只猫",SearchOptions(markedOnly=true),PhotoFilter.ALL).single().photo.id)
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nDESCRIPTION_SCALE cached_entries=10000 query_ms=$elapsed; synthetic identical vectors, no image exists; not an accuracy test\n") })
        }
        app.deleteDatabase(name);app.models.release()
    }
    @Test fun bundledImageAndTextModelsWorkOfflineAndMatchNumericReferences()=runBlocking<Unit> {
        val folder=File(app.cacheDir,"description-reference").apply { mkdirs() };val encoder=app.descriptionEmbedder
        val reference=JSONObject(instrument.context.assets.open("description/reference.json").bufferedReader().readText())
        try {
            val tokenizer=WordPiece(app.assets.open("models/cnclip-vocab.txt").bufferedReader().useLines { lines ->lines.withIndex().associate { it.value to it.index } },lowercase=true)
            val tokenCases=reference.getJSONArray("tokens")
            for(i in 0 until tokenCases.length()) {
                val row=tokenCases.getJSONObject(i);val ids=row.getJSONArray("ids")
                assertArrayEquals(row.getString("text"),LongArray(ids.length()) { ids.getLong(it) },tokenizer.encode(row.getString("text"),52))
            }
            val images=reference.getJSONArray("images");val features=mutableListOf<DescriptionFeatures>()
            for(i in 0 until images.length()) {
                val row=images.getJSONObject(i);val file=File(folder,row.getString("name"))
                instrument.context.assets.open("visual/${file.name}").use { input ->file.outputStream().use(input::copyTo) }
                val value=encoder.image(Uri.fromFile(file));features+=value;val expected=row.getJSONArray("embedding")
                val score=TextTools.dot(FloatArray(DescriptionFeatures.DIMENSIONS) { expected.getDouble(it).toFloat() },value.embedding)
                assertTrue("image parity $score",score>0.9999f)
            }
            val texts=reference.getJSONArray("texts")
            for(i in 0 until texts.length()) {
                val row=texts.getJSONObject(i);val value=encoder.text(row.getString("text"));val expected=row.getJSONArray("embedding")
                val score=TextTools.dot(FloatArray(DescriptionFeatures.DIMENSIONS) { expected.getDouble(it).toFloat() },value)
                assertTrue("text parity $score",score>0.9999f)
            }
            val cat=encoder.text("一只猫");assertTrue(TextTools.dot(cat,features[1].embedding)>TextTools.dot(cat,features[0].embedding))
            val name="description-search.db";app.deleteDatabase(name)
            PhotoDatabase(app,name).use { db ->
                db.upsertBatch((1..2).map(::photo),1);val rows=db.recent(null,0,10).sortedBy { it.added }
                rows.forEachIndexed { i,p ->db.saveDescription(p,features[i],1) }
                val hits=DescriptionSearch(db,encoder).find("一只猫",SearchOptions(),PhotoFilter.ALL)
                assertEquals(rows[1].id,hits.first().photo.id)
                db.setFolderExcluded("Pictures/Description/",true)
                assertTrue(DescriptionSearch(db,encoder).find("一只猫",SearchOptions(),PhotoFilter.ALL).isEmpty())
            };app.deleteDatabase(name)
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nDESCRIPTION_REFERENCE tokenizer=${tokenCases.length()} image=2 text=${texts.length()} numeric parity and retrieval passed\n") })
        } finally { encoder.unloadImage();encoder.unloadText();folder.deleteRecursively() }
    }
}
