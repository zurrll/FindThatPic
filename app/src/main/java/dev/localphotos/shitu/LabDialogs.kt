package dev.localphotos.shitu

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.localphotos.shitu.lab.SampleExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun SampleExportDialog(vm:PhotoViewModel,ids:List<Long>,onDismiss:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val openedHits=remember(ids) { vm.state.value.hits.filter { it.photo.id in ids } }
    var images by remember { mutableStateOf(true) };var paths by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(Triple(0,ids.size,0L)) }
    var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SampleExporter.Result?>(null) };var job by remember { mutableStateOf<Job?>(null) }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file=result?.file
        if(uri!=null && file!=null) job=scope.launch {
            busy=true
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out ->file.inputStream().use { it.copyTo(out,64*1024) } }?:error("无法打开保存位置") };message="已保存" }
            catch(e:CancellationException) { throw e } catch(_:Exception) { message="保存失败，请重试" } finally { busy=false }
        }
    }
    fun close() { job?.cancel();onDismiss() }
    FullPage(onDismiss={close()}) {
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxSize()) {
            PageHeader("打包图片",{close()},tag="closeExport")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                SectionCard {
                    Text(if(result==null) "已选择 ${ids.size} 张图片" else "压缩包已生成",style=MaterialTheme.typography.headlineSmall)
                    Text(if(result==null) "原图和已有搜索记录会一起打包。" else "${result!!.images} 张原图 · ${fileSize(result!!.file.length())}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(result?.skipped?:0>0) Text("${result!!.skipped} 张原图无法读取，错误记录已附带。",style=MaterialTheme.typography.bodySmall)
                }
                if(result==null) {
                    OutlinedTextField(note,{note=it.take(4000)},label={Text("问题说明（选填）")},placeholder={Text("例如：搜了什么、想找哪张、哪里不符合预期")},minLines=3,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("exportNote"))
                    Disclosure("打包选项",tag="exportOptions") {
                        SectionCard {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("包含原图");Switch(images,{images=it},enabled=!busy) }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("包含来源路径");Switch(paths,{paths=it},enabled=!busy) }
                            Text("文字、特征和诊断自动附带，不重新识别图片。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(busy) SectionCard {
                    LinearProgressIndicator(progress={progress.first.toFloat()/progress.second.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                    Text("${progress.first} / ${progress.second} 张 · ${fileSize(progress.third)}",style=MaterialTheme.typography.bodyMedium)
                }
                if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodyMedium)
            }
            Surface(tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(result==null) {
                        Button(onClick={
                            focus.clearFocus();keyboard?.hide()
                            job=scope.launch {
                                busy=true;message=""
                                try {
                                    withContext(Dispatchers.IO) { openedHits.filter { it.matchDebug.isNotBlank() }.forEach { hit ->vm.app.database.saveMatch(hit.photo.id,runCatching { JSONObject(hit.matchDebug).optString("query") }.getOrDefault(""),hit.matchDebug) } }
                                    result=SampleExporter(context,vm.app.database).export(ids,images,paths,note,withContext(Dispatchers.IO) { vm.app.metrics.report() }) { done,total,bytes ->progress=Triple(done,total,bytes) }
                                } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message?:"打包失败，请重试" } finally { busy=false }
                            }
                        },enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("generateSamples")) { Text(if(busy) "正在打包…" else "生成 ZIP 压缩包") }
                        if(busy) TextButton(onClick={job?.cancel()},modifier=Modifier.fillMaxWidth()) { Text("取消打包") }
                    } else {
                        OutlinedButton(onClick={save.launch(result!!.file.name)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(48.dp)) { Text("保存到文件") }
                        Button(onClick={
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.reports",result!!.file)
                            safeStart(context,Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri)
                                .apply { clipData=ClipData.newUri(context.contentResolver,"测试样本",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"分享测试样本"))
                        },enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text("分享 ZIP") }
                    }
                }
            }
        }
    }
}
