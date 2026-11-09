package dev.localphotos.shitu.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class MediaLibrary(private val context: Context, private val db: PhotoDatabase) {
    fun hasAccess(): Boolean {
        fun granted(p:String)=ContextCompat.checkSelfPermission(context,p)==PackageManager.PERMISSION_GRANTED
        return if(Build.VERSION.SDK_INT>=33) granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            (Build.VERSION.SDK_INT>=34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun accessDescription(): String = if(!hasAccess()) "尚未授权照片" else if(Build.VERSION.SDK_INT>=34 &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED) "仅限所选照片" else "已授权设备照片"

    private val scanPrefs=context.getSharedPreferences("library_scan_${db.databaseName}",Context.MODE_PRIVATE)
    private var lastScanElapsed=0L
    private fun partialAccess()=Build.VERSION.SDK_INT>=34 && hasAccess() &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED
    private fun stamp():String {
        val volumes=MediaStore.getExternalVolumeNames(context).sorted()
        val revisions=volumes.joinToString("|") { volume ->
            val version=if(Build.VERSION.SDK_INT>=30) MediaStore.getVersion(context,volume) else MediaStore.getVersion(context)
            val generation=if(Build.VERSION.SDK_INT>=30) MediaStore.getGeneration(context,volume) else 0
            "$volume:$version:$generation"
        }
        return "${accessDescription()}:$revisions:${db.excludedFolders().sorted()}"
    }
    /** Revision checks avoid an O(library size) read every time the activity resumes. */
    fun needsDiscovery():Boolean {
        if(!hasAccess()) return true
        if(partialAccess()) return true // Selection grants may change without a global generation change.
        if(Build.VERSION.SDK_INT<30) return lastScanElapsed==0L || SystemClock.elapsedRealtime()-lastScanElapsed>=60_000
        return scanPrefs.getString("stamp",null)!=stamp()
    }
    fun invalidateScan() { scanPrefs.edit().remove("stamp").apply();lastScanElapsed=0 }
    suspend fun discover(onCount:(Int)->Unit = {}): Int {
        if(!hasAccess()) { db.hideAll();invalidateScan();return 0 }
        val before=stamp()
        val excluded=db.excludedFolders()
        val seen=System.currentTimeMillis()
        val prefs=context.getSharedPreferences("media_versions",Context.MODE_PRIVATE)
        var total=0
        val volumes=MediaStore.getExternalVolumeNames(context)
        for(volume in volumes) {
            currentCoroutineContext().ensureActive()
            val version=if(Build.VERSION.SDK_INT>=30) MediaStore.getVersion(context,volume) else MediaStore.getVersion(context)
            val old=prefs.getString(volume,null)
            if(old!=null && old!=version) db.invalidateVolume(volume)
            val uri=MediaStore.Images.Media.getContentUri(volume)
            val columns=mutableListOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.MIME_TYPE)
            if(Build.VERSION.SDK_INT>=30) columns+=MediaStore.Images.Media.GENERATION_MODIFIED
            val selection=excluded.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "(${MediaStore.Images.Media.RELATIVE_PATH} IS NULL OR substr(${MediaStore.Images.Media.RELATIVE_PATH},1,?)<>?)" }
            val selectionArgs=excluded.takeIf { it.isNotEmpty() }?.flatMap { listOf(it.length.toString(),it) }?.toTypedArray()
            val cursor=context.contentResolver.query(uri,columns.toTypedArray(),selection,selectionArgs,"${MediaStore.Images.Media._ID} ASC")
                ?: error("媒体库暂时无法读取，请稍后刷新")
            cursor.use { c ->
                val batch=ArrayList<Photo>(100)
                fun str(name:String)=c.getString(c.getColumnIndexOrThrow(name))?:""
                fun long(name:String)=c.getLong(c.getColumnIndexOrThrow(name))
                while(c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id=long(MediaStore.Images.Media._ID)
                    batch+=Photo(key="$volume:$id",uri=ContentUris.withAppendedId(uri,id).toString(),
                        name=str(MediaStore.Images.Media.DISPLAY_NAME),folder=str(MediaStore.Images.Media.RELATIVE_PATH).ifBlank { "其他来源/" },
                        modified=long(MediaStore.Images.Media.DATE_MODIFIED),size=long(MediaStore.Images.Media.SIZE),
                        width=long(MediaStore.Images.Media.WIDTH).toInt(),height=long(MediaStore.Images.Media.HEIGHT).toInt(),
                        added=long(MediaStore.Images.Media.DATE_ADDED),generation=if(Build.VERSION.SDK_INT>=30) long(MediaStore.Images.Media.GENERATION_MODIFIED) else 0,
                        taken=(long(MediaStore.Images.Media.DATE_TAKEN)/1000).coerceAtLeast(0),mime=str(MediaStore.Images.Media.MIME_TYPE))
                    total++
                    if(batch.size==100) { db.upsertBatch(batch,seen);batch.clear();onCount(total) }
                }
                if(batch.isNotEmpty()) db.upsertBatch(batch,seen)
            }
            prefs.edit().putString(volume,version).apply()
        }
        // Only reconcile availability after ALL volumes were queried successfully.
        currentCoroutineContext().ensureActive()
        db.finishDiscovery(seen)
        onCount(total)
        lastScanElapsed=SystemClock.elapsedRealtime()
        if(stamp()==before) scanPrefs.edit().putString("stamp",before).apply() else invalidateScan()
        return total
    }
}
