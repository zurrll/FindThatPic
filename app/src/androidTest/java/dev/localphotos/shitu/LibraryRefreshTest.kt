package dev.localphotos.shitu

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localphotos.shitu.data.MediaLibrary
import dev.localphotos.shitu.data.PhotoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRefreshTest {
    @get:Rule val permissions=GrantPermissionRule.grant(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun revisionsSkipUnchangedLibraryAndExclusionsCanBeReversed()=runBlocking {
        val name="library-refresh.db";context.deleteDatabase(name)
        val db=PhotoDatabase(context,name);val library=MediaLibrary(context,db)
        val folder="Pictures/Refresh_%_${System.nanoTime()}/"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"refresh.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"${folder}Child/");put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
        try {
            library.discover()
            // Finish any provider generation changes caused by publishing the fixture.
            if(library.needsDiscovery()) library.discover()
            if(Build.VERSION.SDK_INT>=30) assertFalse(library.needsDiscovery())
            val original=db.recent("${folder}Child/",0,10).single()
            db.complete(original.id,"cached OCR","[]",12);db.mark(listOf(original.id),true)
            db.setFolderExcluded(folder,true);library.invalidateScan()
            assertTrue(library.needsDiscovery())
            library.discover()
            assertTrue(db.recent("${folder}Child/",0,10).isEmpty())
            assertFalse(db.isEligible(original.id))
            assertEquals("cached OCR",db.get(original.id)!!.text)
            db.setFolderExcluded(folder,false);library.invalidateScan();library.discover()
            val restored=db.recent("${folder}Child/",0,10).single()
            assertEquals(original.id,restored.id);assertEquals("cached OCR",restored.text);assertTrue(restored.marked)
            // Regranting the same permission must not trust a stamp from before access was revoked.
            val gate=object:android.content.ContextWrapper(context) {
                var allowed=true
                override fun checkPermission(permission:String,pid:Int,uid:Int):Int =
                    if(!allowed && permission.startsWith("android.permission.READ_")) android.content.pm.PackageManager.PERMISSION_DENIED else super.checkPermission(permission,pid,uid)
                override fun checkSelfPermission(permission:String):Int =
                    if(!allowed && permission.startsWith("android.permission.READ_")) android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            }
            val gated=MediaLibrary(gate,db)
            gate.allowed=false;assertFalse(gated.hasAccess());gated.discover()
            assertTrue(db.recent("${folder}Child/",0,10).isEmpty())
            gate.allowed=true;assertTrue(gated.needsDiscovery());gated.discover()
            assertEquals(original.id,db.recent("${folder}Child/",0,10).single().id)
        } finally { context.contentResolver.delete(uri,null,null);db.close();context.deleteDatabase(name) }
    }
}
