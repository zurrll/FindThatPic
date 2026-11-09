package dev.localphotos.shitu.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.localphotos.shitu.MainActivity
import dev.localphotos.shitu.PhotoApp
import dev.localphotos.shitu.R
import dev.localphotos.shitu.core.OcrEngine
import dev.localphotos.shitu.core.OcrModelUnavailable
import dev.localphotos.shitu.core.TextTools
import dev.localphotos.shitu.core.SemanticText
import dev.localphotos.shitu.data.IndexProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class IndexWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    private val app=context.applicationContext as PhotoApp
    private fun foreground(done:Int,total:Int):ForegroundInfo {
        val manager=app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("index","本地图片索引",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(app,0,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val pause=androidx.work.WorkManager.getInstance(app).createCancelPendingIntent(id)
        val notification=NotificationCompat.Builder(app,"index").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("拾图 · 在手机内建立索引").setContentText("$done / $total 张，图片不会上传")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(total,done,false)
            .addAction(0,"暂停",pause).build()
        return ForegroundInfo(1024,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
      app.batchMutex.withLock { app.indexMutex.withLock {
        val started=SystemClock.elapsedRealtime()
        var completed=0;var target=0;var finished=false;var phase="已暂停，可继续本轮"
        val ocr=OcrEngine(app)
        try {
            val file=File(app.noBackupFilesDir,"jobs/${inputData.getString("job")?:return@withContext Result.failure()}")
            val job=JSONObject(file.readText());val ids=job.getJSONArray("ids");target=ids.length()
            val semantic=job.getBoolean("semantic")
            val meaningsOnly=job.optBoolean("meaningsOnly")
            if(!app.library.hasAccess()) { phase="照片权限已改变，请重新授权";return@withContext Result.failure() }
            setForeground(foreground(0,target))
            for(i in 0 until ids.length()) {
                currentCoroutineContext().ensureActive()
                val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
                if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE) {
                    phase="手机温度较高，已暂停；降温后继续";return@withContext Result.success()
                }
                val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,100)?:100
                val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
                val plugged=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)>0
                if(!plugged && level*100/scale.coerceAtLeast(1)<=15) { phase="电量低于 15%，已暂停";return@withContext Result.success() }
                val photo=app.database.get(ids.getLong(i))
                if(photo==null || !app.database.isEligible(photo.id) || (meaningsOnly && photo.state!="done") || photo.state=="failed" || (photo.state!="pending" && (!semantic || photo.semantic || photo.state=="empty"))) { completed++;continue }
                val imageStart=SystemClock.elapsedRealtime()
                app.progress.value=IndexProgress(true,completed,target,if(photo.state=="pending") "识别图片文字" else "建立含义索引",imageStart-started)
                if(photo.state=="pending") {
                    try {
                        val output=app.models.run(dev.localphotos.shitu.core.ModelKind.OCR) { ocr.recognize(Uri.parse(photo.uri)) }
                        currentCoroutineContext().ensureActive()
                        app.database.complete(photo.id,output.text,output.blocks,SystemClock.elapsedRealtime()-imageStart,photo.fingerprint,
                            output.variants,output.debug,output.version)
                    } catch(e:CancellationException) { throw e }
                    catch(e:OcrModelUnavailable) { throw e }
                    catch(e:Exception) {
                        currentCoroutineContext().ensureActive()
                        val code=when(e) {
                            is SecurityException->"permission_changed"
                            is java.io.FileNotFoundException->"file_unavailable"
                            else->e.message?.takeIf { it in setOf("unsupported_image","image_too_large","region_decode_unavailable","decode_failed") }?:"ocr_failed"
                        }
                        app.database.fail(photo.id,code,photo.fingerprint)
                    }
                }
                val updated=app.database.get(photo.id)
                if(semantic && updated?.state=="done" && !updated.semantic) {
                    app.progress.value=app.progress.value.copy(phase="建立中文含义索引")
                    val meaningStart=SystemClock.elapsedRealtime()
                    val vectors=mutableListOf<FloatArray>()
                    try {
                        val passages=SemanticText.passages(updated.text)
                        for(chunk in passages) {
                            currentCoroutineContext().ensureActive()
                            while(app.embedder.searching) delay(100)
                            vectors+=app.embedder.encode(TextTools.embeddingText(chunk))
                        }
                        app.database.saveVectors(photo.id,vectors,updated.fingerprint,passages,elapsedMs=SystemClock.elapsedRealtime()-meaningStart)
                    } catch(e:CancellationException) { throw e }
                    catch(_:Exception) { phase="含义索引暂不可用，文字搜索仍可用";return@withContext Result.failure() }
                }
                completed++
                app.progress.value=IndexProgress(true,completed,target,"正在建立索引",SystemClock.elapsedRealtime()-started,SystemClock.elapsedRealtime()-imageStart)
                if(completed%5==0) { app.metrics.sample();setForeground(foreground(completed,target)) }
                delay(if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE) 1000 else 20)
            }
            currentCoroutineContext().ensureActive()
            app.jobMutex.withLock {
                val current=runCatching { JSONObject(file.readText()) }.getOrNull()
                if(current?.optString("scope")==job.optString("scope")) {
                    job.put("finished",true)
                    val temp=File(file.parentFile,"completed.tmp");temp.writeText(job.toString());check(temp.renameTo(file))
                }
            }
            finished=true;phase="本轮完成"
            Result.success()
        } catch(e:CancellationException) { throw e }
        catch(e:OcrModelUnavailable) { phase="OCR 模型无法加载，可继续本轮";Result.failure() }
        catch(e:Exception) { android.util.Log.e("ShituIndex","Index stopped: ${e.javaClass.simpleName}",e);phase="本轮已停止，可刷新图库后继续";Result.failure() }
        finally {
            ocr.close()
            app.metrics.batch(completed,SystemClock.elapsedRealtime()-started)
            app.progress.value=IndexProgress(false,completed,target,phase,SystemClock.elapsedRealtime()-started,paused=!finished)
        }
      } }
    }
}
