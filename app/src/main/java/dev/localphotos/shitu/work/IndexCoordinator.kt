package dev.localphotos.shitu.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.localphotos.shitu.PhotoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import dev.localphotos.shitu.data.SearchOptions
import dev.localphotos.shitu.data.IndexProgress
import dev.localphotos.shitu.data.ClearScope
import dev.localphotos.shitu.data.PhotoLimits

class IndexCoordinator(private val app:PhotoApp) {
    companion object { const val WORK="photo-index" }
    suspend fun start(limit:Int,folder:String?,semantic:Boolean,options:SearchOptions=SearchOptions(),meaningsOnly:Boolean=false) = withContext(Dispatchers.IO) {
      app.jobMutex.withLock {
        val active=WorkManager.getInstance(app).getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }
        if(active) return@withLock
        val jobs=File(app.noBackupFilesDir,"jobs").apply { mkdirs() }
        val job=File(jobs,"current.json")
        val previous=if(job.exists()) runCatching { JSONObject(job.readText()) }.getOrNull() else null
        val resumeSelected=previous?.optString("scope")?.startsWith("selected:")==true && !previous.optBoolean("finished") &&
            !semantic
        val scope=if(resumeSelected) previous!!.getString("scope") else "${folder?:"*"}:$limit:$semantic:adaptive:$options:$meaningsOnly"
        val ids=if(previous?.optString("scope")==scope && !previous.optBoolean("finished")) {
            previous.getJSONArray("ids")
        } else JSONArray(app.database.pendingIds(folder,limit,semantic,options,meaningsOnly))
        val value=JSONObject().put("scope",scope).put("semantic",semantic).put("meaningsOnly",meaningsOnly).put("finished",false).put("ids",ids)
        val temp=File(jobs,"current.tmp")
        temp.writeText(value.toString())
        check(temp.renameTo(job))
        val request=OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("job" to job.name)).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,request).result.get()
        Unit
      }
    }
    suspend fun rerun(ids:List<Long>)=withContext(Dispatchers.IO) {
      app.jobMutex.withLock {
        require(ids.isNotEmpty() && ids.size<=PhotoLimits.OCR_BATCH) { "每次最多重跑 ${PhotoLimits.OCR_BATCH} 张" }
        val manager=WorkManager.getInstance(app)
        check(manager.getWorkInfosForUniqueWork(WORK).get().none { !it.state.isFinished }) { "请先暂停当前任务" }
        val jobs=File(app.noBackupFilesDir,"jobs").apply { mkdirs() }
        val job=File(jobs,"current.json")
        val value=JSONObject().put("scope","selected:${System.currentTimeMillis()}").put("semantic",false)
            .put("finished",false).put("ids",JSONArray(ids.distinct()))
        val temp=File(jobs,"current.tmp");temp.writeText(value.toString());check(temp.renameTo(job))
        app.database.requeue(ids)
        manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("job" to job.name)).build()).result.get()
        Unit
      }
    }
    suspend fun restorePausedBatch()=withContext(Dispatchers.IO) {
        app.jobMutex.withLock {
            if(app.progress.value.target>0 || app.progress.value.running) return@withLock
            val file=File(app.noBackupFilesDir,"jobs/current.json")
            val job=if(file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
            if(job!=null && !job.optBoolean("finished")) {
                app.progress.value=IndexProgress(target=job.getJSONArray("ids").length(),phase="上批未完成",paused=true)
            }
        }
    }
    suspend fun resume()=withContext(Dispatchers.IO) {
        app.jobMutex.withLock {
            val manager=WorkManager.getInstance(app)
            if(manager.getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }) return@withLock
            val file=File(app.noBackupFilesDir,"jobs/current.json")
            check(file.exists() && !JSONObject(file.readText()).optBoolean("finished")) { "没有可继续的批次" }
            manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("job" to file.name)).build()).result.get()
            Unit
        }
    }
    suspend fun clearRecognition(target:ClearScope):Int=withContext(Dispatchers.IO) {
        val manager=WorkManager.getInstance(app)
        manager.cancelUniqueWork(WORK).result.get()
        app.preparation.discard()
        // Wait for any in-flight OCR to release its resources before clearing its output.
        app.indexMutex.withLock {
            app.jobMutex.withLock {
                val count=app.database.clearRecognition(target)
                val file=File(app.noBackupFilesDir,"jobs/current.json")
                if(file.exists()) {
                    val job=runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).put("finished",true)
                    val temp=File(file.parentFile,"clear.tmp");temp.writeText(job.toString());check(temp.renameTo(file))
                }
                app.progress.value=IndexProgress()
                count
            }
        }
    }
    fun pause() { WorkManager.getInstance(app).cancelUniqueWork(WORK) }
}
