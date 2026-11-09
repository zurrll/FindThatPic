package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreparationUiTest {
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
    @Test fun unifiedPreparationPreservesCachesResumesAndSupportsVisualSearchAndSimilarityNavigation()=runBlocking<Unit> {
        val folder="Pictures/PreparationUi/${System.nanoTime()}/";val uris=mutableListOf<android.net.Uri>()
        app.preparation.discard();app.indexer.pause();app.visualIndexer.pause()
        try {
            repeat(20) { i ->
                val cat=i%2==1;val asset=if(cat) "chelsea.png" else "astronaut.png"
                val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"${if(cat) "cat" else "space"}-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1) }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                instrument.context.assets.open("visual/$asset").use { input ->app.contentResolver.openOutputStream(uri)!!.use(input::copyTo) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            app.library.discover();val photos=app.database.recent(folder,0,100);val old=photos.first { it.name=="space-0.png" }
            app.database.complete(old.id,"保留旧文字记录","[]",1,version="legacy")
            app.database.saveVisual(old,VisualFeatures(FloatArray(1280).apply { this[0]=1f },0,ByteArray(768),224,224),1)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder)));vm.kinds(PreparationKinds());vm.limit(100);vm.mode(SearchMode.TEXT);vm.query("") }
                compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.size==20 }
                compose.onNodeWithText("最近").assertDoesNotExist()
                click("indexButton");click("prepareStart")
                compose.waitUntil(20_000) { vm.state.value.workActive && app.progress.value.done>=2 }
                click("preparePause");compose.waitUntil(15_000) { !vm.state.value.workActive && app.progress.value.paused }
                val fixed=app.preparation.readJob()!!.getJSONArray("ids").toString()
                click("prepareResume")
                compose.waitUntil(100_000) { app.preparation.readJob()?.optBoolean("finished")==true && !vm.state.value.workActive }
                assertEquals(fixed,app.preparation.readJob()!!.getJSONArray("ids").toString())
                assertEquals(20,app.database.preparationStats(SearchOptions(folders=setOf(folder))).complete)
                assertEquals(BuildConfig.HAS_DESCRIPTION,app.preparation.readJob()!!.getBoolean("description"))
                if(!BuildConfig.HAS_DESCRIPTION) {
                    assertEquals(0,app.database.preparationStats(SearchOptions(folders=setOf(folder))).description)
                    compose.onNodeWithTag("searchModeMenu").assertDoesNotExist()
                }
                assertEquals("保留旧文字记录",app.database.get(old.id)!!.text);assertEquals("legacy",app.database.details(old.id)!!.version)
                assertTrue(app.database.pendingPreparationIds(100,SearchOptions(folders=setOf(folder)),PreparationKinds()).isEmpty())
                compose.waitUntil(10_000) { vm.state.value.prepared.complete==20 };compose.waitForIdle()
                val screen=instrument.uiAutomation.takeScreenshot()
                java.io.File(app.getExternalFilesDir(null),"preparation-page.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };screen.recycle()
                compose.onNodeWithTag("sheetDone").performClick()
                val reference=if(BuildConfig.HAS_DESCRIPTION) {
                    click("searchModeMenu");click("searchModeDESCRIPTION");compose.onNodeWithTag("searchField").performTextReplacement("一只猫")
                    compose.waitUntil(20_000) { !vm.state.value.searching && vm.state.value.hits.isNotEmpty() && vm.state.value.query=="一只猫" }
                    vm.state.value.hits.first().photo.also { assertTrue(it.name,it.name.startsWith("cat")) }
                } else {
                    compose.onNodeWithTag("searchField").performTextReplacement("cat-1.png")
                    val cat=photos.first { it.name=="cat-1.png" }
                    compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.firstOrNull()?.photo?.id==cat.id }
                    cat
                }
                click("photo_${reference.id}");click("findSimilar");click("visualScopeMenu");click("visualCurrentScope")
                val candidates=app.visualSearch.find(reference.id,SearchOptions(folders=setOf(folder)))
                assertTrue(candidates.isNotEmpty());val next=candidates.first().photo
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo_${next.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("similarResults").performScrollToNode(hasTestTag("photo_${next.id}"))
                click("photo_${next.id}");click("findSimilar")
                val seedName=androidx.compose.ui.semantics.SemanticsProperties.StateDescription
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("similarSeed").fetchSemanticsNodes().any { it.config.getOrElse(seedName) { "" }==next.name } }
                click("similarBack");compose.onNodeWithTag("similarSeed").assert(SemanticsMatcher.expectValue(seedName,reference.name))
                click("closeSimilar")
                if(BuildConfig.HAS_DESCRIPTION) { click("searchModeMenu");click("searchModeTEXT") }
                compose.onNodeWithTag("searchField").performTextReplacement("保留旧文字记录")
                compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.firstOrNull()?.photo?.id==old.id }
                click("librarySettings");click("openGuide")
                compose.onNodeWithText("先补全搜索信息").assertIsDisplayed()
                compose.onNodeWithTag("guideIllustration").assertIsDisplayed()
                click("guideNext");compose.onNodeWithText("如果有不想被识别的目录，").assertIsDisplayed()
                click("guideNext");compose.onNodeWithText("记得图片里有的文字？图片的文件名？").assertIsDisplayed()
                click("guideNext");compose.onNodeWithText("手头有一张图片，想找类似的图片？").assertIsDisplayed()
                if(BuildConfig.HAS_DESCRIPTION) {
                    click("guideNext");compose.onNodeWithText("记得图片的画面但是没有用来参考的图？").assertIsDisplayed()
                }
                click("guideNext");compose.onNodeWithTag("gettingStarted").assertDoesNotExist()
                click("librarySettings");click("settingsPrepare");click("sheetDone")
                click("librarySettings");click("performanceReport")
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("closeReport").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("exportPerformance").assertIsDisplayed()
                compose.onNodeWithText("中位").assertIsDisplayed()
                click("reportQueries");compose.onNodeWithText("最近查询").assertIsDisplayed()
                click("closeReport");click("closeSettings")
                scenario.onActivity { vm.query("");vm.kinds(PreparationKinds()) }
            }
        } finally {
            app.preparation.discard();app.models.release();app.getSharedPreferences("ui",0).edit().putString("search_mode","TEXT").putBoolean("prepare_text",true).putBoolean("prepare_similar",true).putBoolean("prepare_description",true).apply()
            uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover()
        }
    }
}
