package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public image fixtures exercise real UI and persistent cache metadata, not retrieval accuracy. */
class LabUiTest {
    @get:Rule val permissions=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    private fun click(tag:String) {
        compose.waitUntil(8000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val node=compose.onNodeWithTag(tag);if(!node.isDisplayed()) node.performScrollTo();node.performClick();compose.waitForIdle()
    }
    private fun shot(name:String) { compose.waitForIdle();Thread.sleep(350);val image=instrument.uiAutomation.takeScreenshot();File(app.getExternalFilesDir(null),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() }
    @Test fun fullPhotoTechnicalDetailsStaySeparateAndPreserveOldNotes()=runBlocking<Unit> {
        val folder="Pictures/InterfaceTest/${System.nanoTime()}/";val uris=mutableListOf<android.net.Uri>()
        try {
            repeat(14) { i ->
                val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"${if(i%2==0) "猫咪" else "太空"}-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1) }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                instrument.context.assets.open("visual/${if(i%2==0) "chelsea.png" else "astronaut.png"}").use { input ->app.contentResolver.openOutputStream(uri)!!.use(input::copyTo) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            app.library.discover()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder)));vm.mode(SearchMode.TEXT) }
                compose.waitUntil(15000) { !vm.state.value.searching && vm.state.value.hits.size==14 }
                val target=vm.state.value.hits.first { it.photo.name.startsWith("猫咪") }.photo;val id=target.id
                app.database.complete(id,"disco\nvery\n费用明细","[]",21,debug="{\"baseCount\":1,\"refinementCount\":0,\"tiles\":[{\"box\":[0,0,451,300],\"decodeMs\":2,\"ocrMs\":19,\"decodedWidth\":451,\"decodedHeight\":300}]}")
                app.database.saveVectors(id,listOf(FloatArray(512).apply { this[0]=1f }),passages=listOf("费用明细"))
                app.database.saveVisual(target,VisualFeatures(FloatArray(1280).apply { this[0]=1f },0,ByteArray(768),451,300),7)
                app.database.saveDescription(target,DescriptionFeatures(FloatArray(512).apply { this[0]=1f },451,300),32)
                app.database.saveFeedback(id,"旧版说明需要保留","旧版查询", "{}")
                val failed=vm.state.value.hits.first { it.photo.id!=id }.photo.id
                app.database.fail(failed,"ocr_failed")
                val legacy=vm.state.value.hits.first { it.photo.id!=id && it.photo.id!=failed }.photo.id
                app.database.complete(legacy,"旧文字记录","[{\"box\":[0,0,100,100]}]",2)
                scenario.onActivity { vm.changed() };compose.waitForIdle()
                shot("build12-home.png")
                click("indexButton");compose.onNodeWithTag("prepareStart").assertIsDisplayed();compose.waitUntil(8000) { compose.onAllNodesWithTag("prepareRetry").fetchSemanticsNodes().isNotEmpty() };shot("build12-preparation.png");click("prepareRetry");click("sheetDone")
                compose.onNodeWithTag("searchField").performTextReplacement("discovery")
                compose.waitUntil(15000) { !vm.state.value.searching && vm.state.value.hits.any { it.photo.id==id && it.matchDebug.isNotBlank() } }
                click("photo_$id");compose.onNodeWithTag("photoViewer").assertIsDisplayed()
                compose.onNodeWithTag("exportOne").assertDoesNotExist();compose.onNodeWithTag("reviewNote").assertDoesNotExist();compose.onNodeWithTag("rerunOne").assertDoesNotExist()
                compose.onNodeWithTag("openInGallery").assertIsDisplayed()
                shot("build12-viewer.png");compose.onNodeWithTag("photoViewer").performTouchInput { doubleClick() };shot("build12-viewer-zoom.png")
                click("viewerMore");click("advancedInspector");compose.onNodeWithText("搜索信息").assertIsDisplayed()
                assertEquals("discovery",JSONObject(app.database.details(id)!!.matchDebug).getString("query"));shot("build12-technical.png")
                click("detail_文字含义");compose.onNodeWithText("BGE-small-zh-v1.5 · INT8").assertExists()
                click("detail_相近特征");compose.onNodeWithText("1280 维 · 5 KB").assertExists()
                if(BuildConfig.HAS_DESCRIPTION) {
                    click("detail_画面特征");compose.onNodeWithText("Chinese-CLIP B/16 · INT8").assertExists()
                }
                click("detail_OCR");click("overlay_处理块");shot("build12-ocr-details.png")
                click("closeTechnical");click("closeInspector")
                compose.onNodeWithTag("searchField").performTextReplacement("");compose.waitUntil(10000) { !vm.state.value.searching && vm.state.value.hits.size==14 }
                click("selectMode");click("selectVisible")
                compose.onNodeWithTag("exportSelected").assertDoesNotExist()
                assertEquals("旧版说明需要保留",app.database.get(id)!!.reviewNote);assertEquals("旧版查询",app.database.details(id)!!.query)
                click("finishSelection")
                click("filterButton");compose.onNodeWithTag("originalOnly").assertDoesNotExist();shot("build12-filters.png");click("moreFilters");compose.onNodeWithTag("originalOnly").assertExists();click("sheetDone")
                click("photo_$legacy");click("viewerMore");click("advancedInspector");click("detail_OCR");click("overlay_处理块")
                compose.onNodeWithText("旧记录未保存统一坐标，不叠加识别框。").assertExists();click("closeTechnical");click("closeInspector")
            }
        } finally { uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover() }
    }
}
