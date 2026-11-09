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
import dev.localphotos.shitu.core.VisualFeatures
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Replaces features without changing coverage: a ready count is not a cache revision. */
class SimilarRefreshUiTest {
    @get:Rule val permission=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoApp
    private fun feature(axis:Int)=VisualFeatures(FloatArray(1280).apply { this[axis]=1f },123,ByteArray(768) { 90 },512,512)
    private fun click(tag:String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).apply { if(!isDisplayed()) performScrollTo() }.performClick();compose.waitForIdle()
    }
    private fun result(id:Long)=hasTestTag("photo_$id") and hasAnyAncestor(hasTestTag("similarResults"))
    @Test fun openResultsFollowFeatureReplacementAndScopeChangesWithoutReopening()=runBlocking<Unit> {
        val folder="Pictures/SimilarRefresh/${System.nanoTime()}/"
        val uris=mutableListOf<android.net.Uri>()
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("visual/astronaut.png").readBytes()
        try {
            repeat(3) { i ->
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"refresh-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1)
                })!!;uris+=uri
                app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            app.library.discover()
            val rows=app.database.recent(folder,0,3);val seed=rows[0];val old=rows[1];val replacement=rows[2]
            app.database.saveVisual(seed,feature(0),1);app.database.saveVisual(old,feature(0),1);app.database.saveVisual(replacement,feature(1),1)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder))) }
                compose.waitUntil(15_000) { !vm.state.value.searching && vm.state.value.hits.size==3 }
                click("photo_${seed.id}");click("findSimilar");click("visualCurrentScope")
                compose.waitUntil(10_000) { compose.onAllNodes(result(old.id)).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(compose.onAllNodes(result(replacement.id)).fetchSemanticsNodes().isEmpty())
                val before=app.database.visualStats()
                app.database.saveVisual(old,feature(1),1);app.database.saveVisual(replacement,feature(0),1)
                assertEquals(before,app.database.visualStats())
                compose.waitUntil(10_000) { compose.onAllNodes(result(replacement.id)).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(result(old.id)).fetchSemanticsNodes().isEmpty() }
                // There is no progress update or work-state change here to force a page refresh.
                app.database.mark(listOf(replacement.id),true)
                compose.waitUntil(10_000) { compose.onAllNodes(result(replacement.id) and hasContentDescription("已标记")).fetchSemanticsNodes().isNotEmpty() }
                app.database.setFolderExcluded(folder,true)
                compose.waitUntil(10_000) { compose.onAllNodes(result(replacement.id)).fetchSemanticsNodes().isEmpty() }
                app.database.setFolderExcluded(folder,false)
                compose.waitUntil(10_000) { compose.onAllNodes(result(replacement.id)).fetchSemanticsNodes().isNotEmpty() }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                app.database.saveVisual(old,feature(0),1);app.database.saveVisual(replacement,feature(1),1)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { compose.onAllNodes(result(old.id)).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(result(replacement.id)).fetchSemanticsNodes().isEmpty() }
                click("closeSimilar")
            }
        } finally {
            app.database.setFolderExcluded(folder,false)
            uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover()
        }
    }
}
