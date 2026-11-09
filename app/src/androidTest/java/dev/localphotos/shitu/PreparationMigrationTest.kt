package dev.localphotos.shitu

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.localphotos.shitu.work.Preparation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreparationMigrationTest {
    @Test fun legacyPausedBatchesBecomeOnePausedBatchWithoutStartingInference()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoApp
        val directory=File(app.noBackupFilesDir,"jobs").apply { mkdirs() }
        val files=listOf("preparation.json","current.json","visual.json").map { File(directory,it) }
        val backups=files.associateWith { if(it.exists()) it.readText() else null }
        val prefs=app.getSharedPreferences("ui",0);val had=prefs.contains("preparation_migrated");val migrated=prefs.getBoolean("preparation_migrated",false)
        val progress=app.progress.value
        try {
            app.preparation.discard();files.forEach { it.delete() };prefs.edit().putBoolean("preparation_migrated",false).commit()
            File(directory,"current.json").writeText(JSONObject().put("finished",false).put("ids",JSONArray(listOf(11,12))).toString())
            File(directory,"visual.json").writeText(JSONObject().put("finished",false).put("ids",JSONArray(listOf(12,13))).toString())
            app.progress.value=dev.localphotos.shitu.data.IndexProgress()
            app.preparation.restore(adoptLegacy=true)
            val job=app.preparation.readJob()!!
            assertEquals("[11,12,13]",job.getJSONArray("ids").toString());assertFalse(job.getBoolean("finished"))
            assertEquals(4,app.preparation.kinds(job).stages.size);assertTrue(app.progress.value.paused);assertFalse(app.progress.value.running)
            assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(Preparation.WORK).get().all { it.state.isFinished })
            assertTrue(JSONObject(File(directory,"current.json").readText()).getBoolean("finished"))
            assertTrue(JSONObject(File(directory,"visual.json").readText()).getBoolean("finished"))
            app.preparation.restore(adoptLegacy=true);assertEquals(job.getString("token"),app.preparation.readJob()!!.getString("token"))
        } finally {
            files.forEach { file -> backups[file]?.let(file::writeText)?:file.delete() }
            prefs.edit().apply { if(had) putBoolean("preparation_migrated",migrated) else remove("preparation_migrated") }.commit()
            app.progress.value=progress
        }
    }
}
