package dev.localphotos.shitu

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises index capacity, NOT 10,000 real-image OCR or real-device performance. */
@RunWith(AndroidJUnit4::class)
class IndexScaleTest {
    @Test fun tenThousandEntriesRemainSearchableWithoutLoadingFullImages() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        context.deleteDatabase("scale-test.db")
        val db=PhotoDatabase(context,"scale-test.db")
        try {
            val started=SystemClock.elapsedRealtime()
            for(batch in 0 until 100) db.upsertBatch((0 until 100).map { n ->
                val i=batch*100+n
                Photo(key="external:$i",uri="content://media/external/images/media/$i",name="$i.png",folder="Pictures/Scale${i%20}/",modified=1,size=100,width=1000,height=2000,added=i.toLong())
            },1)
            val ids=db.pendingIds(null,Int.MAX_VALUE,false)
            val regular=FloatArray(512).apply { this[0]=-1f }
            val target=FloatArray(512).apply { this[0]=1f }
            db.writableDatabase.beginTransaction()
            try {
                ids.forEachIndexed { i,id ->
                    db.complete(id,if(i==9999) "武康路咖啡店订单 AB-123" else "其他内容 条目 $i","[]",1)
                    db.saveVectors(id,listOf(if(i==9999) target else regular))
                }
                db.writableDatabase.setTransactionSuccessful()
            } finally { db.writableDatabase.endTransaction() }
            assertEquals(10_000,db.stats().total)
            val lexicalStart=SystemClock.elapsedRealtime()
            val hits=db.lexical("武康路",null,0)
            val lexicalMs=SystemClock.elapsedRealtime()-lexicalStart
            assertEquals(ids.last(),hits.first().first.id)
            val vectorStart=SystemClock.elapsedRealtime()
            assertEquals(ids.last(),db.nearest(target,null,0).first().first.id)
            val vectorMs=SystemClock.elapsedRealtime()-vectorStart
            instrument.sendStatus(0,Bundle().apply { putString("stream","\nINDEX_SCALE synthetic_entries=10000 lexical_ms=$lexicalMs vector_scan_ms=$vectorMs total_ms=${SystemClock.elapsedRealtime()-started}\n") })
        } finally { db.close();context.deleteDatabase("scale-test.db") }
    }
}
