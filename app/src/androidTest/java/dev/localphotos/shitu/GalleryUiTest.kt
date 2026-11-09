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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localphotos.shitu.data.SearchOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GalleryUiTest {
    @get:Rule val permissions=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    @Test fun headerScrollsAwayAndMetadataControlsCompose()=runBlocking {
        val root="Pictures/ShituGalleryUi/${System.nanoTime()}"
        val folderA="$root/Screenshots/";val folderB="$root/Other/"
        val uris=mutableListOf<android.net.Uri>()
        val bitmap=Bitmap.createBitmap(100,150,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(54,125,105)) }
        try {
            repeat(31) { i ->
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"gallery-ui-$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                    put(MediaStore.Images.Media.DATE_TAKEN,1_700_000_000_000L+i*1000L)
                    put(MediaStore.Images.Media.RELATIVE_PATH,if(i==30) folderB else folderA)
                    put(MediaStore.Images.Media.IS_PENDING,1)
                }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                app.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            bitmap.recycle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java] }
                compose.waitUntil(15_000) { vm.state.value.allowed && !vm.state.value.discovering && vm.state.value.stats.folders.any { it.path==folderB } }
                scenario.onActivity { vm.options(SearchOptions(folders=setOf(folderA))) }
                compose.waitUntil(5000) { !vm.state.value.searching && vm.state.value.hits.size==30 }
                assertEquals("image/png",vm.state.value.hits.first().photo.mime)
                // Provider metadata, including a missing date, must reach the cached record unchanged.
                val first=vm.state.value.hits.first().photo
                app.contentResolver.query(android.net.Uri.parse(first.uri),arrayOf(MediaStore.Images.Media.DATE_TAKEN),null,null,null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst());assertEquals(cursor.getLong(0)/1000,first.taken)
                }
                val last=vm.state.value.hits.last().photo.id
                compose.onNodeWithTag("results").performScrollToNode(hasTestTag("photo_$last"))
                compose.onNodeWithTag("photo_$last").assertIsDisplayed()
                compose.onNodeWithTag("homeHeader").assertIsNotDisplayed()
                val shot=instrument.uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir(null),"gallery-scroll.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
                compose.onNodeWithTag("results").performScrollToIndex(0)
                compose.onNodeWithTag("folderButton").performClick()
                compose.onNodeWithTag("sourceList").performScrollToNode(hasTestTag("source_$folderB"))
                compose.onNodeWithTag("source_$folderB").performClick()
                compose.onNodeWithTag("sheetDone").performClick()
                compose.waitUntil(5000) { vm.state.value.options.folders.size==2 && vm.state.value.hits.size==31 }
                compose.onNodeWithTag("filterButton").performClick()
                compose.onNodeWithTag("moreFilters").performClick()
                compose.onNodeWithTag("originalOnly").performScrollTo().performClick()
                compose.onNodeWithTag("format_JPEG").performScrollTo().performClick()
                compose.onNodeWithTag("sheetDone").performClick()
                compose.waitUntil(5000) { !vm.state.value.searching && vm.state.value.hits.isEmpty() }
                assertTrue(vm.state.value.options.originalOnly)
                compose.onNodeWithTag("filterButton").performClick()
                compose.onNodeWithTag("filterList").performScrollToNode(hasTestTag("clearFilters"))
                compose.onNodeWithTag("clearFilters").performClick()
                compose.onNodeWithTag("sheetDone").performClick()
                compose.waitUntil(5000) { !vm.state.value.searching && vm.state.value.options==SearchOptions() }
                compose.onAllNodesWithText("文字含义 · 试验").assertCountEquals(0)
                val home=instrument.uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir(null),"gallery-home.png").outputStream().use { home.compress(Bitmap.CompressFormat.PNG,100,it) };home.recycle()
            }
        } finally {
            if(!bitmap.isRecycled) bitmap.recycle()
            uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover()
        }
    }
}
