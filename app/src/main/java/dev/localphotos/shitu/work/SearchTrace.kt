package dev.localphotos.shitu.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import org.json.JSONObject
import dev.localphotos.shitu.BuildConfig
import java.util.UUID

/** One request; resource figures are samples, not continuous device-wide measurements. */
class SearchTrace(private val context:Context,val mode:String,val model:String) {
    val id=UUID.randomUUID().toString().take(12)
    private val started=SystemClock.elapsedRealtime()
    private val values=JSONObject().put("id",id).put("captured_at",System.currentTimeMillis()).put("mode",mode)
        .put("app_version",BuildConfig.VERSION_NAME).put("app_build",BuildConfig.VERSION_CODE)
        .put("model",model).put("backend","CPU · 2 threads").put("status","running")
    private val stages=JSONObject()
    private var peakPssKb=0
    init { values.put("before",resources());sample() }
    @Synchronized fun put(key:String,value:Any?) { values.put(key,value?:JSONObject.NULL) }
    @Synchronized fun elapsed(key:String,ms:Long) { stages.put(key,stages.optLong(key)+ms.coerceAtLeast(0)) }
    suspend fun <T> stage(key:String,block:suspend()->T):T { val t=SystemClock.elapsedRealtime();return try { block() } finally { elapsed(key,SystemClock.elapsedRealtime()-t) } }
    @Synchronized fun sample() { val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory);peakPssKb=maxOf(peakPssKb,memory.totalPss) }
    @Synchronized fun finish(status:String) { sample();values.put("status",status).put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("sampled_peak_pss_kb",peakPssKb).put("after",resources()) }
    @Synchronized fun json():JSONObject=JSONObject(values.toString()).put("stages_ms",JSONObject(stages.toString())).apply {
        if(optString("status")=="running") put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("sampled_peak_pss_kb",peakPssKb)
    }
    private fun resources():JSONObject {
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val b=context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1;val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
        return JSONObject().put("thermal_status",power.currentThermalStatus).put("battery_percent",if(level<0) JSONObject.NULL else level*100/scale.coerceAtLeast(1))
            .put("battery_temperature_c",battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)?.div(10.0)?:JSONObject.NULL)
            .put("charging",b.isCharging).put("power_save",power.isPowerSaveMode).put("process_cpu_ms",Process.getElapsedCpuTime())
    }
}
