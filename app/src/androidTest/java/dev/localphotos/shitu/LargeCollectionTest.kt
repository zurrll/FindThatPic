package dev.localphotos.shitu

import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

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
}
