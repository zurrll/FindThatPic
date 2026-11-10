package dev.localphotos.shitu.work

import android.app.ActivityManager
import android.content.Context
import dev.localphotos.shitu.BuildConfig
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import dev.localphotos.shitu.PhotoApp
import dev.localphotos.shitu.core.OcrEngine
import org.json.JSONArray
import org.json.JSONObject

class PerformanceMonitor(private val context:Context) {
    private val prefs=context.getSharedPreferences("performance",Context.MODE_PRIVATE)
    @Synchronized fun sample() {
        val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
        prefs.edit().putInt("peak_pss_kb",maxOf(prefs.getInt("peak_pss_kb",0),memory.totalPss)).apply()
    }
    private fun preparationKey(stage:String) = "prepare_ms_${stage}_build_${BuildConfig.VERSION_CODE}"
    @Synchronized fun preparation(stage:String,ms:Long) {
        val key=preparationKey(stage)
        val times=prefs.getString(key,"")!!.split(',').mapNotNull(String::toLongOrNull).takeLast(99)+ms
        prefs.edit().putString(key,times.joinToString(",")).apply()
    }
    @Synchronized fun record(trace:SearchTrace) {
        val list=runCatching { JSONArray(prefs.getString("recent_searches_v1","[]")) }.getOrDefault(JSONArray())
        val keep=JSONArray();for(i in maxOf(0,list.length()-29) until list.length()) list.optJSONObject(i)?.let { keep.put(it) }
        keep.put(trace.json());prefs.edit().putString("recent_searches_v1",keep.toString()).apply();sample()
    }
    fun recent():JSONArray=runCatching { JSONArray(prefs.getString("recent_searches_v1","[]")) }.getOrDefault(JSONArray())
    fun batch(count:Int,ms:Long) { prefs.edit().putInt("last_batch_count",count).putLong("last_batch_ms",ms).apply();sample() }
    fun report():String {
        val app=context.applicationContext as PhotoApp
        val stats=app.database.stats();val prepared=app.database.preparationStats()
        val memory=ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val preparation=JSONObject()
        for(stage in listOf("ocr","meaning","similar","description")) {
            val times=prefs.getString(preparationKey(stage),"")!!.split(',').mapNotNull(String::toLongOrNull)
            val sorted=times.sorted()
            fun percentile(p:Double)=sorted.getOrNull(((sorted.size-1).coerceAtLeast(0)*p).toInt())?:JSONObject.NULL
            preparation.put(stage,JSONObject().put("samples",times.size).put("p50_ms",percentile(.5))
                .put("p95_ms",percentile(.95)).put("last_ms",times.lastOrNull()?:JSONObject.NULL))
        }
        val db=context.getDatabasePath("photos.db")
        return JSONObject().apply {
            put("app_version",BuildConfig.VERSION_NAME);put("app_build",BuildConfig.VERSION_CODE);put("device",Build.MODEL);put("manufacturer",Build.MANUFACTURER)
            put("api",Build.VERSION.SDK_INT);put("ram_total_bytes",memory.totalMem)
            put("authorized_photos",stats.total);put("ocr_with_text",stats.done);put("ocr_without_text",stats.empty)
            put("description_photos",prepared.description);put("failed",stats.failed);put("semantic_photos",stats.semantic);put("folder_count",stats.folders.size)
            put("peak_process_pss_kb",prefs.getInt("peak_pss_kb",0))
            put("preparation_latency",preparation);put("recent_searches",recent())
            put("ocr_recognition_threads",OcrEngine.RECOGNITION_THREADS)
            put("available_bytes",context.noBackupFilesDir.usableSpace)
            put("description_failed",prepared.descriptionFailed);put("similar_failed",prepared.similarFailed)
            put("emulator",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
            put("last_batch_count",prefs.getInt("last_batch_count",0));put("last_batch_elapsed_ms",prefs.getLong("last_batch_ms",0))
            put("database_bytes",db.length()+java.io.File(db.path+"-wal").length())
            put("apk_bytes",java.io.File(context.applicationInfo.sourceDir).length())
            put("original_model_cache_bytes",java.io.File(context.noBackupFilesDir,"models").walkTopDown().filter { it.isFile }.sumOf { it.length() })
            put("thermal_status",(context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
            put("network_permission",false)
            put("notes",JSONArray(listOf("elapsed includes idle/thermal waits","PSS is sampled, not continuous","cold/warm refers to model session state","No pictures, OCR text, paths or query strings included", "Battery temperature is not CPU temperature; no energy consumption is inferred")))
        }.toString(2)
    }
}
