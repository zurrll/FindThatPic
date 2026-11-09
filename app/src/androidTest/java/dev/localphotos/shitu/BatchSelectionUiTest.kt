package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
import android.graphics.*
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
import java.io.ByteArrayOutputStream
import java.io.File

class BatchSelectionUiTest {
    @get:Rule val permission=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as PhotoApp
    @Test fun oneTapSelectsFiveHundredBeyondLoadedGridAndCanMarkMore()=runBlocking<Unit> {
        val folder="Pictures/BatchSelection/${System.nanoTime()}/";val uris=mutableListOf<android.net.Uri>()
        val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);Canvas(bitmap).drawColor(Color.rgb(30,100,80))
        val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
        fun click(tag:String){val node=compose.onNodeWithTag(tag);if(!node.isDisplayed())node.performScrollTo();node.performClick();compose.waitForIdle()}
        try {
            repeat(502) { i ->
                val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"batch-$i.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.IS_PENDING,1) }
                val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;uris+=uri
                app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            }
            app.library.discover()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:PhotoViewModel
                scenario.onActivity { vm=ViewModelProvider(it)[PhotoViewModel::class.java];vm.options(SearchOptions(folders=setOf(folder))) }
                compose.waitUntil(15000) { !vm.state.value.searching && vm.state.value.hits.size==60 }
                click("selectMode");click("selectionMore");click("selectBatch")
                compose.waitUntil(10000) { vm.state.value.selected.size==500 && !vm.state.value.selectingBatch }
                assertEquals(60,vm.state.value.hits.size)
                click("selectionMore");click("selectBatch");compose.waitUntil(10000) { vm.state.value.selected.size==502 }
                click("markSelected");compose.waitUntil(10000) { app.database.recent(folder,0,600).all { it.marked } }
                val shot=instrument.uiAutomation.takeScreenshot();File(app.getExternalFilesDir(null),"batch-selection-500.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
                compose.onNodeWithTag("exportSelected").assertDoesNotExist()
            }
        } finally { uris.forEach { app.contentResolver.delete(it,null,null) };app.library.discover() }
    }
}
