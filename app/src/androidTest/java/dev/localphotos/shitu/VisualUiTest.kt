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
import dev.localphotos.shitu.data.SearchOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class VisualUiTest {
    @get:Rule val permission=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    private fun click(tag:String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val matcher=hasTestTag(tag)
        val node=if(tag.startsWith("photo_") && compose.onAllNodesWithTag("similarResults").fetchSemanticsNodes().isNotEmpty())
            compose.onNode(matcher and hasAnyAncestor(hasTestTag("similarResults"))) else compose.onNode(matcher)
        if(!node.isDisplayed()) node.performScrollTo();node.performClick();compose.waitForIdle()
    }
    @Test fun selectPreparePauseResumeAndReviewVisualMatch()=runBlocking {
        val folder="Pictures/VisualUi/${System.nanoTime()}/"
        val uris=mutableListOf<android.net.Uri>()
        val bytes=instrument.context.assets.open("visual/astronaut.png").readBytes()
        try {
            // Enough real inference work remains to exercise pause/resume, using public-domain image copies.
            repeat(120) { i ->
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"visual-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1)
                }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            app.library.discover()
            val seed=app.database.recent(folder,0,1).single()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder))) }
                compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.isNotEmpty() }
                click("photo_${seed.id}");click("findSimilar")
                compose.waitUntil(15_000) { app.database.visual(seed.id)!=null }
                click("visualScopeMenu");click("visualCurrentScope")
                click("prepareFromSimilar")
                scenario.onActivity { vm.kinds(dev.localphotos.shitu.data.PreparationKinds(text=false,similar=true,description=false));vm.limit(500) }
                val previousToken=app.preparation.readJob()?.optString("token")
                click("prepareStart")
                compose.waitUntil(20_000) { app.preparation.readJob()?.optString("token")!=previousToken && vm.state.value.workActive && app.progress.value.done>=2 }
                kotlinx.coroutines.withTimeout(5000) { app.indexer.clearRecognition(dev.localphotos.shitu.data.ClearScope(ids=listOf(seed.id))) }
                assertNotNull(app.database.visual(seed.id))
                // Clearing records discards the unified job; a fresh batch reuses completed visual features.
                compose.waitUntil(15_000) { !vm.state.value.workActive }
                click("prepareStart")
                compose.waitUntil(20_000) { vm.state.value.workActive && app.progress.value.running && app.progress.value.done>=2 && compose.onAllNodesWithTag("preparePause").fetchSemanticsNodes().isNotEmpty() }
                // Continuous progress used to cancel/debounce every search until the whole batch ended.
                compose.onNodeWithTag("sheetDone").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("40 张").fetchSemanticsNodes().isNotEmpty() }
                assertTrue("results should be available before indexing finishes",vm.state.value.workActive)
                click("prepareFromSimilar")
                click("preparePause")
                compose.waitUntil(15_000) { app.progress.value.paused && !vm.state.value.workActive }
                assertTrue("pause must leave work to resume",app.database.visualStats(SearchOptions(folders=setOf(folder))).ready<120)
                val ids=app.preparation.readJob()!!.getJSONArray("ids").toString()
                click("prepareResume")
                compose.waitUntil(60_000) { app.preparation.readJob()?.optBoolean("finished")==true && !vm.state.value.workActive }
                assertEquals(ids,app.preparation.readJob()!!.getJSONArray("ids").toString())
                compose.onNodeWithTag("sheetDone").performClick()
                compose.waitUntil(15_000) { app.database.visualStats(SearchOptions(folders=setOf(folder))).ready==120 }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("40 张").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(app.database.recent(folder,0,200).all { it.state=="pending" }) // never ran OCR
                val hits=app.visualSearch.find(seed.id,SearchOptions(folders=setOf(folder)))
                assertFalse(hits.any { it.photo.id==seed.id })
                compose.waitForIdle()
                val shot=instrument.uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir(null),"visual-results.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
                // The live page can still be replacing its earlier 40 candidates after the last write.
                compose.waitUntil(10_000) { runCatching {
                    compose.onNodeWithTag("similarResults").performScrollToNode(hasTestTag("photo_${hits.first().photo.id}"))
                }.isSuccess }
                click("photo_${hits.first().photo.id}")
                compose.onNodeWithTag("shareImage").assertIsDisplayed()
                compose.onNodeWithTag("findSimilar").assertIsDisplayed()
                click("closeInspector")
                compose.onNodeWithTag("similarSelect").assertDoesNotExist()
                click("closeSimilar")
            }
        } finally {
            app.preparation.discard();app.visualIndexer.pause();app.visualEmbedder.unload();app.getSharedPreferences("ui",0).edit().putBoolean("prepare_text",true).putBoolean("prepare_similar",true).putBoolean("prepare_description",true).apply()
            uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover()
        }
    }
}
