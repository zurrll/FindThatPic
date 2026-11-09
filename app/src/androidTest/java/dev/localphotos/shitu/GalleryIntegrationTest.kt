package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.localphotos.shitu.work.IndexCoordinator
import dev.localphotos.shitu.core.TextTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GalleryIntegrationTest {
    @get:Rule val permissions:GrantPermissionRule=GrantPermissionRule.grant(
        if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as PhotoApp
    @Test fun multiFolderDiscoveryRealWorkerIncrementalIndexAndSemanticSearch()=runBlocking {
        val photos=mutableListOf<Uri>()
        val token=System.nanoTime()
        val folder="Pictures/ShituTest/$token/"
        val downloadFolder="Download/ShituTest/$token/"
        val samples=listOf(
            "全屋费用明细\n柜体、瓷砖、水电、人工\n总计 88000 元",
            "租房合同\n押一付三\n房租 3500 元",
            "公园散步\n小狗和小猫\n周末晴天",
            "周末探店 武康路\n咖啡和面包\n周六下午三点",
            "会议安排\n周一上午十点\n研发会议室",
            "订单 AB-123\n退款流程\n三个工作日内到账"
        )
        try {
            samples.forEachIndexed { i,text -> photos+=insert(folder,"sample-$i.png",text) }
            photos+=insert(downloadFolder,"sample-0.png","旅行清单\n帐篷、水壶、背包")
            app.library.discover()
            assertEquals(6,app.database.stats().folders.first { it.path==folder }.count)
            assertTrue(app.database.stats().folders.any { it.path==downloadFolder })
            ActivityScenario.launch(MainActivity::class.java).use {
                app.indexer.start(100,folder,false)
                val pauseDeadline=System.currentTimeMillis()+30_000
                while(app.progress.value.done<1 && System.currentTimeMillis()<pauseDeadline) Thread.sleep(10)
                WorkManager.getInstance(app).cancelUniqueWork(IndexCoordinator.WORK).result.get()
                // Let the in-flight native OCR call finish before resuming the saved batch.
                val stopDeadline=System.currentTimeMillis()+30_000
                while(app.progress.value.running && System.currentTimeMillis()<stopDeadline) Thread.sleep(20)
                assertFalse(app.progress.value.running)
                app.indexer.start(100,folder,false)
                waitForWorker()
                val first=app.search.search("武康路",folder,0,false)
                assertEquals(1,first.size)
                assertTrue(first.single().photo.text.contains("武康路"))
                assertTrue(app.search.search("旅行",folder,0,false).isEmpty())
                assertTrue(app.search.search("旅行",downloadFolder,0,false).isEmpty())
                app.indexer.start(100,folder,true)
                waitForWorker()
                assertTrue("Semantic index incomplete: ${app.database.stats()} phase=${app.progress.value.phase}",app.database.stats().semantic>=6)
                val related=app.search.search("装修预算",folder,0,true)
                assertTrue("No semantic matches",related.isNotEmpty())
                assertTrue(related.first().photo.text,TextTools.normalize(related.first().photo.text).contains("费用"))
                assertTrue(app.search.search("费用",folder,0,false).isNotEmpty())
                assertTrue(app.search.search("火星基地生命维持系统维修手册",folder,0,true).isEmpty())
                val indexed=app.database.stats().processed
                photos+=insert(folder,"new.png","新增图片 增量校验")
                app.library.discover()
                assertEquals(indexed,app.database.stats().processed)
                assertEquals(1,app.database.pendingIds(folder,100,false).size)
                if(InstrumentationRegistry.getArguments().getString("keepFixtures")=="true") captureSemanticScreen()
            }
        } finally {
            app.indexer.pause()
            if(InstrumentationRegistry.getArguments().getString("keepFixtures")!="true") {
                photos.forEach { app.contentResolver.delete(it,null,null) }
                app.library.discover()
            }
        }
    }
    private fun captureSemanticScreen() {
        fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(node==null) return null
            if(predicate(node)) return node
            for(i in 0 until node.childCount) find(node.getChild(i),predicate)?.let { return it }
            return null
        }
        instrumentation.waitForIdleSync()
        val input=find(instrumentation.uiAutomation.rootInActiveWindow) { it.className?.toString()=="android.widget.EditText" }
        assertNotNull("Search field missing",input)
        assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"装修预算")
        }))
        val deadline=System.currentTimeMillis()+15_000
        while(System.currentTimeMillis()<deadline) {
            if(find(instrumentation.uiAutomation.rootInActiveWindow) { TextTools.normalize(it.text?.toString()?:"").contains("全屋费用") }!=null) break
            Thread.sleep(100)
        }
        val imageDeadline=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<imageDeadline && find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.contentDescription?.toString()?.contains("sample-")==true
        }==null) Thread.sleep(100)
        val image=instrumentation.uiAutomation.takeScreenshot()
        val file=File(app.getExternalFilesDir(null),"semantic-search.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        val dump=StringBuilder()
        fun visit(node:AccessibilityNodeInfo?) {
            if(node==null) return
            if(!node.text.isNullOrBlank()) dump.appendLine("${node.className} | ${node.text} | enabled=${node.isEnabled} clickable=${node.isClickable}")
            for(i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        File(app.getExternalFilesDir(null),"ui-nodes.txt").writeText(dump.toString())
        assertNotNull("Semantic result missing from UI",find(instrumentation.uiAutomation.rootInActiveWindow) { TextTools.normalize(it.text?.toString()?:"").contains("全屋费用") })
    }
    private fun insert(folder:String,name:String,text:String):Uri {
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val collection=if(folder.startsWith("Download/")) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri=app.contentResolver.insert(collection,values)!!
        val image=Bitmap.createBitmap(1080,1400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.rgb(246,247,242))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(23,107,91);textSize=60f }
        canvas.drawText("拾图 · 测试样本",60f,150f,paint)
        paint.color=Color.BLACK;paint.textSize=54f
        text.lines().forEachIndexed { i,line -> canvas.drawText(line,60f,350f+i*130,paint) }
        app.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
        app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
        return uri
    }
    private fun waitForWorker() {
        val deadline=System.currentTimeMillis()+180_000
        while(System.currentTimeMillis()<deadline) {
            val infos=WorkManager.getInstance(app).getWorkInfosForUniqueWork(IndexCoordinator.WORK).get()
            if(infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                assertTrue("Index worker failed: ${app.progress.value.phase}",infos.any { it.state==WorkInfo.State.SUCCEEDED })
                return
            }
            Thread.sleep(200)
        }
        fail("Index worker timed out: ${app.progress.value}")
    }
}
