package dev.localphotos.shitu.work

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.os.SystemClock
import dev.localphotos.shitu.R
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.localphotos.shitu.*
import dev.localphotos.shitu.data.*
import dev.localphotos.shitu.core.VisualFeatures
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent jobs and checkpoints; the OCR button never starts this work. */
class VisualIndex(private val app:PhotoApp) {
    companion object { const val WORK="visual-index" }
    private val file get()=File(app.noBackupFilesDir,"jobs/visual.json")
    suspend fun start(limit:Int,options:SearchOptions,retry:Boolean=false)=withContext(Dispatchers.IO) {
        app.visualJobMutex.withLock {
            val manager=WorkManager.getInstance(app)
            if(manager.getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }) return@withLock
            val ids=app.database.pendingVisualIds(limit,options,retry)
            check(ids.isNotEmpty()) { "本范围没有待准备的图片" }
            val value=JSONObject().put("token",java.util.UUID.randomUUID().toString()).put("version",VisualFeatures.VERSION)
                .put("finished",false).put("retry",retry).put("ids",JSONArray(ids))
            file.parentFile!!.mkdirs();val temp=File(file.parentFile,"visual.tmp");temp.writeText(value.toString());check(temp.renameTo(file))
            app.visualProgress.value=IndexProgress(target=ids.size,phase="等待准备画面特征")
            enqueue(manager)
        }
    }
    suspend fun resume()=withContext(Dispatchers.IO) {
        app.visualJobMutex.withLock {
            val manager=WorkManager.getInstance(app)
            if(manager.getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }) return@withLock
            val value=readJob()
            check(value!=null && !value.optBoolean("finished") && value.optString("version")==VisualFeatures.VERSION) { "没有可继续的画面批次" }
            enqueue(manager)
        }
    }
    suspend fun restore()=withContext(Dispatchers.IO) {
        app.visualJobMutex.withLock {
            if(app.visualProgress.value.target>0) return@withLock
            val value=readJob()
            if(value!=null && !value.optBoolean("finished") && value.optString("version")==VisualFeatures.VERSION)
                app.visualProgress.value=IndexProgress(target=value.getJSONArray("ids").length(),phase="画面特征上批未完成",paused=true)
        }
    }
    fun readJob():JSONObject?=runCatching { JSONObject(file.readText()) }.getOrNull()
    internal suspend fun finish(token:String) {
        app.visualJobMutex.withLock {
            val value=readJob()?:return@withLock
            if(value.optString("token")==token) {
                value.put("finished",true)
                val temp=File(file.parentFile,"visual-finished.tmp");temp.writeText(value.toString());check(temp.renameTo(file))
            }
        }
    }
    private fun enqueue(manager:WorkManager) {
        manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<VisualWorker>().build()).result.get()
    }
    fun pause() { WorkManager.getInstance(app).cancelUniqueWork(WORK) }
}

class VisualWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    private val app=context.applicationContext as PhotoApp
    private fun foreground(done:Int,total:Int):ForegroundInfo {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("visual","画面特征",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(app,1,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(app,"visual").setSmallIcon(R.drawable.ic_launcher).setContentTitle("拾图 · 准备画面特征")
            .setContentText("$done / $total 张").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(total,done,false)
            .addAction(0,"暂停",WorkManager.getInstance(app).createCancelPendingIntent(id)).build()
        return ForegroundInfo(1025,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val started=SystemClock.elapsedRealtime()
        var done=0;var total=0;var finished=false;var phase="画面特征已暂停"
        try {
            val job=app.visualIndexer.readJob()?:return@withContext Result.failure()
            if(job.optBoolean("finished") || job.optString("version")!=VisualFeatures.VERSION) return@withContext Result.failure()
            val ids=job.getJSONArray("ids");total=ids.length()
            app.visualProgress.value=IndexProgress(true,0,total,"等待当前文字任务结束")
            setForeground(foreground(0,total))
            // Avoid running two batches of inference at once on a large library.
            app.batchMutex.withLock {
                for(i in 0 until total) {
                    currentCoroutineContext().ensureActive()
                    if(!app.library.hasAccess()) { phase="照片权限已改变";return@withContext Result.failure() }
                    val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,100)?:100
                    val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
                    val plugged=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)>0
                    if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE || (!plugged && level*100/scale.coerceAtLeast(1)<=15)) {
                        phase="温度较高或电量较低，画面特征已暂停";return@withContext Result.success()
                    }
                    val start=SystemClock.elapsedRealtime()
                    val photo=app.database.get(ids.getLong(i),brief=true)
                    if(photo!=null && app.database.isEligible(photo.id) && (if(job.optBoolean("retry")) app.database.visual(photo.id)==null else !app.database.visualAttempted(photo.id))) {
                        app.visualProgress.value=IndexProgress(true,done,total,"准备画面特征",start-started)
                        try { app.visualSearch.prepare(photo.id) }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) {
                            currentCoroutineContext().ensureActive()
                            // Do not fill the library with failures if the bundled model itself is missing/broken.
                            if(e is ai.onnxruntime.OrtException || e.message?.contains("模型")==true) {
                                phase="画面模型暂不可用，请重新安装完整安装包";return@withContext Result.failure()
                            }
                            app.database.saveVisual(photo,null,SystemClock.elapsedRealtime()-start,if(e is SecurityException) "permission_changed" else "image_unavailable")
                        }
                    }
                    done++
                    app.visualProgress.value=IndexProgress(true,done,total,"准备画面特征",SystemClock.elapsedRealtime()-started,SystemClock.elapsedRealtime()-start)
                    if(done%5==0) setForeground(foreground(done,total))
                    delay(if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE) 1000 else 20)
                }
                currentCoroutineContext().ensureActive();app.visualIndexer.finish(job.getString("token"))
                finished=true;phase="画面特征本轮完成"
                Result.success()
            }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { phase="画面特征已停止，可继续本轮";Result.failure() }
        finally {
            withContext(NonCancellable) { app.visualEmbedder.unload() }
            app.visualProgress.value=IndexProgress(false,done,total,phase,SystemClock.elapsedRealtime()-started,paused=!finished)
        }
    }
}
