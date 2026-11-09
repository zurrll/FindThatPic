package dev.localphotos.shitu

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.work.IndexCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LabWorkTest {
    @get:Rule val permissions=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @Test fun pausedSelectionResumesOnlyItsOriginalIds()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoApp
        val file=File(app.cacheDir,"lab-work.png")
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        canvas.drawText("discovery 订单",40f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=50f })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val manager=WorkManager.getInstance(app)
        var locked=false
        fun waitFinished() {
            val deadline=System.currentTimeMillis()+30_000
            while(System.currentTimeMillis()<deadline) {
                if(manager.getWorkInfosForUniqueWork(IndexCoordinator.WORK).get().all { it.state.isFinished }) return
                Thread.sleep(30)
            }
            fail("Worker did not stop")
        }
        try {
            app.database.upsertBatch((1..3).map { i->Photo(key="labwork:$i",uri=Uri.fromFile(file).toString(),name="sample.png",folder="LabWork/",modified=1,size=file.length(),width=800,height=600,added=i.toLong()) },System.currentTimeMillis())
            val ids=app.database.recent("LabWork/",0,3).map { it.id }
            app.indexMutex.lock();locked=true
            app.indexer.rerun(ids.take(2))
            manager.cancelUniqueWork(IndexCoordinator.WORK).result.get();waitFinished()
            app.indexMutex.unlock();locked=false
            app.indexer.resume();waitFinished()
            assertTrue(ids.take(2).all { app.database.get(it)!!.state=="done" })
            assertEquals("pending",app.database.get(ids.last())!!.state)
            assertEquals(2,app.progress.value.target)
        } finally {
            if(locked) app.indexMutex.unlock()
            app.indexer.pause();file.delete();app.database.invalidateVolume("labwork")
        }
    }
    @Test fun meaningPreparationResumesCachedTextWithoutStartingOcr()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoApp
        val manager=WorkManager.getInstance(app)
        val folder="MeaningWork/"
        var locked=false
        fun waitFinished() {
            val deadline=System.currentTimeMillis()+30_000
            while(System.currentTimeMillis()<deadline) {
                if(manager.getWorkInfosForUniqueWork(IndexCoordinator.WORK).get().all { it.state.isFinished }) return
                Thread.sleep(30)
            }
            fail("Meaning worker did not stop")
        }
        try {
            // Intentionally unreadable originals: meaning preparation must use cached OCR only.
            app.database.upsertBatch((1..2).map { i -> Photo(key="meaningwork:$i",uri="file:///unavailable-$i.png",name="sample.png",
                folder=folder,modified=1,size=100,width=100,height=100,added=i.toLong()) },System.currentTimeMillis())
            val ids=app.database.recent(folder,0,10).map { it.id }
            val cached=ids.first();val pending=ids.last()
            app.database.complete(cached,"测试已有文字的索引恢复","[]",123)
            app.indexMutex.lock();locked=true
            app.indexer.start(100,null,true,options=dev.localphotos.shitu.data.SearchOptions(folders=setOf(folder)),meaningsOnly=true)
            manager.cancelUniqueWork(IndexCoordinator.WORK).result.get();waitFinished()
            app.progress.value=dev.localphotos.shitu.data.IndexProgress()
            app.indexer.restorePausedBatch()
            assertTrue(app.progress.value.paused);assertEquals(1,app.progress.value.target)
            app.indexMutex.unlock();locked=false
            app.indexer.resume();waitFinished()
            assertTrue(app.database.get(cached)!!.semantic)
            assertEquals(123L,app.database.details(cached)!!.ocrMs)
            assertEquals("pending",app.database.get(pending)!!.state)
        } finally {
            if(locked) app.indexMutex.unlock()
            app.indexer.pause();app.database.invalidateVolume("meaningwork")
        }
    }

    @Test fun clearingRecordsCancelsSavedBatchAndPreservesMarks()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoApp
        val manager=WorkManager.getInstance(app)
        var locked=false
        try {
            app.database.upsertBatch(listOf(Photo(key="clearwork:1",uri="file:///unavailable",name="sample.png",folder="ClearWork/",modified=1,size=100,width=100,height=100,added=1)),1)
            val id=app.database.recent("ClearWork/",0,1).single().id
            app.database.complete(id,"previous OCR","[]",4);app.database.mark(listOf(id),true)
            app.indexMutex.lock();locked=true
            app.indexer.rerun(listOf(id))
            val clearing=async(kotlinx.coroutines.Dispatchers.IO) {
                app.indexer.clearRecognition(dev.localphotos.shitu.data.ClearScope(ids=listOf(id)))
            }
            val deadline=System.currentTimeMillis()+10_000
            while(manager.getWorkInfosForUniqueWork(IndexCoordinator.WORK).get().any { !it.state.isFinished } && System.currentTimeMillis()<deadline) Thread.sleep(20)
            app.indexMutex.unlock();locked=false
            clearing.await()
            assertEquals("pending",app.database.get(id)!!.state)
            assertEquals("",app.database.details(id)!!.previousText)
            assertTrue(app.database.get(id)!!.marked)
            assertTrue(runCatching { app.indexer.resume() }.isFailure)
            assertFalse(app.progress.value.paused)
        } finally {
            if(locked) app.indexMutex.unlock()
            app.indexer.pause();app.database.invalidateVolume("clearwork")
        }
    }

}
