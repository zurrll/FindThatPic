package dev.localphotos.shitu.work

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.localphotos.shitu.*
import dev.localphotos.shitu.R
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One fixed batch of IDs, shared by all preparation stages. Completed per-photo caches are checkpoints. */
class Preparation(private val app:PhotoApp) {
    companion object { const val WORK="prepare-search" }
    private val file get()=File(app.noBackupFilesDir,"jobs/preparation.json")
    fun readJob():JSONObject?=runCatching { JSONObject(file.readText()) }.getOrNull()
    private fun write(value:JSONObject) {
        file.parentFile!!.mkdirs();val temp=File(file.parentFile,"preparation.tmp")
        temp.writeText(value.toString());check(temp.renameTo(file))
    }
    suspend fun start(limit:Int,options:SearchOptions,kinds:PreparationKinds,retry:Boolean=false,selected:List<Long>?=null)=withContext(Dispatchers.IO) {
        app.preparationJobMutex.withLock {
            val manager=WorkManager.getInstance(app)
            if(manager.getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }) return@withLock
            require(kinds.stages.isNotEmpty()) { "请选择至少一种搜索信息" }
            val ids=selected?.distinct()?.filter { app.database.isEligible(it) }?:app.database.pendingPreparationIds(limit,options,kinds,retry)
            check(ids.isNotEmpty()) { "当前范围没有待补齐的信息" }
            write(JSONObject().put("formatVersion",1).put("token",java.util.UUID.randomUUID().toString()).put("finished",false)
                .put("ids",JSONArray(ids)).put("text",kinds.text).put("similar",kinds.similar).put("description",kinds.description).put("retry",retry))
            // A new round replaces paused legacy rounds too; their completed caches remain valid.
            for((name,mutex) in listOf("current.json" to app.jobMutex,"visual.json" to app.visualJobMutex)) mutex.withLock {
                val legacy=File(file.parentFile,name)
                val previous=runCatching { JSONObject(legacy.readText()) }.getOrNull()
                if(previous!=null && !previous.optBoolean("finished")) {
                    previous.put("finished",true);val temp=File(legacy.parentFile,"$name.tmp")
                    temp.writeText(previous.toString());check(temp.renameTo(legacy))
                }
            }
            app.progress.value=IndexProgress(target=ids.size*kinds.stages.size,phase="等待准备搜索信息")
            enqueue(manager)
        }
    }
    private fun enqueue(manager:WorkManager) {
        manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<PreparationWorker>().build()).result.get()
    }
    suspend fun resume()=withContext(Dispatchers.IO) {
        app.preparationJobMutex.withLock {
            val manager=WorkManager.getInstance(app)
            if(manager.getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }) return@withLock
            check(readJob()?.optBoolean("finished")==false) { "没有可继续的批次" }
            enqueue(manager)
        }
    }
    suspend fun restore(adoptLegacy:Boolean=false)=withContext(Dispatchers.IO) {
        val prefs=app.getSharedPreferences("ui",0)
        if(adoptLegacy && readJob()==null && !prefs.getBoolean("preparation_migrated",false)) {
            val manager=WorkManager.getInstance(app)
            manager.cancelUniqueWork(IndexCoordinator.WORK).result.get();manager.cancelUniqueWork(VisualIndex.WORK).result.get()
            app.batchMutex.withLock {
                app.preparationJobMutex.withLock {
                    if(readJob()==null) {
                        val legacy=listOf("current.json","visual.json").mapNotNull { name ->
                            val f=File(app.noBackupFilesDir,"jobs/$name")
                            runCatching { JSONObject(f.readText()) }.getOrNull()?.takeIf { !it.optBoolean("finished") }?.let { f to it }
                        }
                        val ids=legacy.flatMap { (_,j) -> val a=j.getJSONArray("ids");(0 until a.length()).map { a.getLong(it) } }.distinct()
                        if(ids.isNotEmpty()) write(JSONObject().put("formatVersion",1).put("token",java.util.UUID.randomUUID().toString()).put("finished",false)
                            .put("ids",JSONArray(ids)).put("text",true).put("similar",true).put("description",true).put("retry",false))
                        legacy.forEach { (f,j) -> j.put("finished",true);f.writeText(j.toString()) }
                    }
                }
            }
            prefs.edit().putBoolean("preparation_migrated",true).apply()
        }
        app.preparationJobMutex.withLock {
            if(app.progress.value.running) return@withLock
            val job=readJob()
            if(job!=null && !job.optBoolean("finished") && app.progress.value.target==0) {
                val count=job.getJSONArray("ids").length()
                app.progress.value=IndexProgress(target=count*kinds(job).stages.size,phase="上批搜索信息尚未补齐",paused=true)
            }
        }
    }
    fun kinds(job:JSONObject)=PreparationKinds(job.optBoolean("text",true),job.optBoolean("similar",true),job.optBoolean("description",true))
    suspend fun finish(token:String)=app.preparationJobMutex.withLock {
        val job=readJob()?:return@withLock
        if(job.optString("token")==token) { job.put("finished",true);write(job) }
    }
    suspend fun discard()=withContext(Dispatchers.IO) {
        WorkManager.getInstance(app).cancelUniqueWork(WORK).result.get()
        app.preparationJobMutex.withLock { readJob()?.let { it.put("finished",true);write(it) } }
    }
    fun pause() { WorkManager.getInstance(app).cancelUniqueWork(WORK) }
}

class PreparationWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    private val app=context.applicationContext as PhotoApp
    private fun foreground(done:Int,total:Int,phase:String):ForegroundInfo {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("preparation","准备搜索信息",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(app,2,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val note=NotificationCompat.Builder(app,"preparation").setSmallIcon(R.drawable.ic_launcher).setContentTitle("拾图 · 准备搜索信息")
            .setContentText(phase).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(total,done,false)
            .addAction(0,"暂停",WorkManager.getInstance(app).createCancelPendingIntent(id)).build()
        return ForegroundInfo(1026,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val started=SystemClock.elapsedRealtime();var done=0;var total=0;var finished=false;var phase="已暂停，可继续本轮"
        try {
            val job=app.preparation.readJob()?:return@withContext Result.failure()
            if(job.optBoolean("finished")) return@withContext Result.success()
            val array=job.getJSONArray("ids");val ids=(0 until array.length()).map { array.getLong(it) }
            val stages=app.preparation.kinds(job).stages;total=ids.size*stages.size
            app.progress.value=IndexProgress(true,0,total,"等待当前任务结束")
            setForeground(foreground(0,total,"等待准备"))
            app.batchMutex.withLock {
                for((stageIndex,stage) in stages.withIndex()) {
                    val label=when(stage) { "ocr"->"识别图片文字";"meaning"->"准备文字含义";"similar"->"准备相近图片";else->"准备画面描述" }
                    val needed=app.database.pendingPreparationStage(ids,stage,job.optBoolean("retry"))
                    val ocr=if(stage=="ocr") OcrEngine(app) else null
                    try {
                        suspend fun process() {
                            for((i,id) in ids.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                if(!app.library.hasAccess()) error("照片权限已改变，请重新授权")
                                val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                                val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,100)?:100;val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
                                val plugged=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)>0
                                check(power.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE && (plugged || level*100/scale.coerceAtLeast(1)>15)) { "温度较高或电量较低，已暂停" }
                                while(app.userQueries.get()>0) { delay(100);currentCoroutineContext().ensureActive() }
                                phase="$label · ${i+1}/${ids.size} 张"
                                app.progress.value=IndexProgress(true,stageIndex*ids.size+i,total,phase,SystemClock.elapsedRealtime()-started)
                                val tick=SystemClock.elapsedRealtime()
                                val photo=if(id in needed) app.database.get(id) else null
                                if(photo!=null && app.database.isEligible(id)) {
                                    when(stage) {
                                        "ocr"->try {
                                            val output=app.models.run(ModelKind.OCR) { ocr!!.recognize(Uri.parse(photo.uri)) }
                                            currentCoroutineContext().ensureActive()
                                            app.database.complete(id,output.text,output.blocks,SystemClock.elapsedRealtime()-tick,photo.fingerprint,output.variants,output.debug,output.version)
                                        } catch(e:CancellationException) { throw e } catch(e:OcrModelUnavailable) { throw e } catch(e:Exception) {
                                            currentCoroutineContext().ensureActive();app.database.fail(id,if(e is SecurityException) "permission_changed" else "ocr_failed",photo.fingerprint)
                                        }
                                        "meaning"->{
                                            val meaningStart=SystemClock.elapsedRealtime()
                                            val passages=SemanticText.passages(photo.text);val vectors=mutableListOf<FloatArray>()
                                            for(chunk in passages) { currentCoroutineContext().ensureActive();vectors+=app.embedder.encode(TextTools.embeddingText(chunk)) }
                                            currentCoroutineContext().ensureActive();app.database.saveVectors(id,vectors,photo.fingerprint,passages,elapsedMs=SystemClock.elapsedRealtime()-meaningStart)
                                        }
                                        "similar"->try { app.visualSearch.prepare(id) }
                                            catch(e:CancellationException) { throw e } catch(e:Exception) { recordFailure(photo,e,false,tick) }
                                        else->try {
                                            val value=app.descriptionEmbedder.image(Uri.parse(photo.uri));currentCoroutineContext().ensureActive()
                                            app.database.saveDescription(photo,value,SystemClock.elapsedRealtime()-tick)
                                        } catch(e:CancellationException) { throw e } catch(e:Exception) { recordFailure(photo,e,true,tick) }
                                    }
                                }
                                done=stageIndex*ids.size+i+1
                                if(photo!=null) app.metrics.preparation(stage,SystemClock.elapsedRealtime()-tick)
                                app.progress.value=IndexProgress(true,done,total,phase,SystemClock.elapsedRealtime()-started,SystemClock.elapsedRealtime()-tick)
                                if(done%5==0) { setForeground(foreground(done,total,phase));app.metrics.sample() }
                                if(photo!=null) delay(if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE) 1000 else 20)
                            }
                        }
                        if(stage=="ocr" || stage=="meaning") app.indexMutex.withLock { process() } else process()
                    } finally { ocr?.close();withContext(NonCancellable) { app.models.release() } }
                }
                currentCoroutineContext().ensureActive();app.preparation.finish(job.getString("token"));finished=true;phase="本轮已结束"
                Result.success()
            }
        } catch(e:CancellationException) { phase="已暂停，可继续本轮";throw e }
        catch(e:Exception) { phase=e.message?.takeIf { it.contains("权限") || it.contains("暂停") || it.contains("模型") }?:"准备暂时停止，可继续本轮";Result.failure() }
        finally {
            app.metrics.batch(done,SystemClock.elapsedRealtime()-started)
            if(!finished && app.preparation.readJob()?.optBoolean("finished")==true) app.progress.value=IndexProgress()
            else app.progress.value=IndexProgress(false,done,total,phase,SystemClock.elapsedRealtime()-started,paused=!finished)
        }
    }
    private suspend fun recordFailure(photo:Photo,error:Exception,description:Boolean,tick:Long) {
        currentCoroutineContext().ensureActive()
        if(error is ai.onnxruntime.OrtException || error.message?.contains("模型")==true) throw error
        if(description) app.database.saveDescription(photo,null,SystemClock.elapsedRealtime()-tick,"image_unavailable")
        else app.database.saveVisual(photo,null,SystemClock.elapsedRealtime()-tick,"image_unavailable")
    }
}
