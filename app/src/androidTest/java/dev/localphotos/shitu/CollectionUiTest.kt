package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
import android.graphics.*
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localphotos.shitu.data.SearchOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class CollectionUiTest {
    @get:Rule val permissions=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    private fun click(tag:String) {
        val node=compose.onNodeWithTag(tag)
        if(!node.isDisplayed()) node.performScrollTo()
        node.performClick();compose.waitForIdle()
    }
    private fun shot(name:String) {
        compose.waitForIdle();Thread.sleep(350)
        val image=instrument.uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
    @Test fun oneFieldFeedbackMarksBatchExportAndClearAndExcludeAreUsable()=runBlocking {
        val folder="Pictures/CollectionUi/${System.nanoTime()}/"
        val uris=mutableListOf<android.net.Uri>()
        val image=Bitmap.createBitmap(700,500,Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE);drawText("Sample small text",35f,130f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=40f })
        }
        try {
            repeat(2) { i ->
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"collection-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1)
                }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                app.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            image.recycle();app.library.discover()
            val ids=app.database.recent(folder,0,10).map { it.id }
            ids.forEach { app.database.complete(it,"searchable sample","[]",1) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder))) }
                compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.size==2 }
                // Resume unchanged MediaStore: cached rows must not receive a new discovery timestamp.
                fun generation()=MediaStore.getExternalVolumeNames(app).sorted().joinToString { MediaStore.getGeneration(app,it).toString() }
                var last=generation();var stableSince=System.currentTimeMillis()
                val settleDeadline=stableSince+10_000
                while(System.currentTimeMillis()<settleDeadline && System.currentTimeMillis()-stableSince<1600) {
                    Thread.sleep(100)
                    val next=generation()
                    if(next!=last) { last=next;stableSince=System.currentTimeMillis() }
                }
                // Publishing PNGs can trigger asynchronous provider metadata updates and observer scans.
                if(app.library.needsDiscovery()) app.library.discover()
                Thread.sleep(300)
                fun seen()=app.database.readableDatabase.rawQuery("SELECT seen FROM photos WHERE _id=?",arrayOf(ids.first().toString())).use { it.moveToFirst();it.getLong(0) }
                val before=seen()
                scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitForIdle();Thread.sleep(400)
                assertFalse(vm.state.value.discovering);assertEquals(before,seen())
                app.database.saveFeedback(ids.first(),"旧版保存的说明","", "{}")
                click("photo_${ids.first()}");compose.onNodeWithTag("reviewNote").assertDoesNotExist()
                click("markOne");compose.waitUntil(5000) { app.database.get(ids.first())!!.marked }
                shot("collection-viewer.png");click("closeInspector")
                click("selectMode");click("selectVisible");click("markSelected")
                compose.waitUntil(5000) { ids.all { app.database.get(it)!!.marked } }
                click("exportSelected")
                compose.onNodeWithText("这批样本的整体说明").assertDoesNotExist()
                compose.onNodeWithTag("exportNote").performTextReplacement("这批图片的小字有漏识别")
                click("generateSamples")
                compose.waitUntil(20_000) { compose.onAllNodesWithText("分享 ZIP").fetchSemanticsNodes().isNotEmpty() }
                val file=File(app.cacheDir,"samples").listFiles()!!.filter { it.extension=="zip" }.maxBy { it.lastModified() }
                ZipFile(file).use { zip ->
                    val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    assertTrue(manifest.getBoolean("includeImages"));assertTrue(manifest.getBoolean("includeSourcePaths"))
                    assertEquals(2,manifest.getInt("copiedImages"))
                    val rows=manifest.getJSONArray("samples")
                    for(i in 0 until rows.length()) assertTrue(rows.getJSONObject(i).getBoolean("marked"))
                    assertTrue(zip.entries().asSequence().filter { it.name.endsWith("review.json") }.any { zip.getInputStream(it).bufferedReader().readText().contains("旧版") })
                }
                click("closeExport");shot("collection-batch.png")
                click("selectionMore");click("clearSelectedRecords")
                compose.waitUntil(5000) { compose.onAllNodesWithText("清除 2 张").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("confirmClearRecords").assertIsEnabled().performClick()
                compose.waitUntil(15_000) { ids.all { app.database.get(it)!!.state=="pending" } && !vm.state.value.changingRecords }
                assertTrue(ids.all { app.database.get(it)!!.marked });assertTrue(app.database.get(ids.first())!!.reviewNote.contains("旧版"))
                click("finishSelection")
                click("librarySettings");click("manageExclusions")
                compose.onNodeWithTag("excludedList").performScrollToNode(hasTestTag("exclude_$folder"));click("exclude_$folder")
                compose.waitUntil(10_000) { folder in vm.state.value.excluded && vm.state.value.hits.isEmpty() }
                shot("collection-exclusions.png");click("exclude_$folder")
                compose.waitUntil(10_000) { folder !in vm.state.value.excluded && vm.state.value.hits.size==2 }
                click("sheetDone");click("closeSettings")
                click("markedShortcut")
                compose.waitUntil(5000) { vm.state.value.options.markedOnly && vm.state.value.hits.size==2 }
                shot("collection-marked.png")
            }
        } finally {
            if(!image.isRecycled) image.recycle()
            app.database.setFolderExcluded(folder,false)
            uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover()
        }
    }
}
