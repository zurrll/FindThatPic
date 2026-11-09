package dev.localphotos.shitu

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable internal fun LibrarySheet(state:UiState,vm:PhotoViewModel,onDismiss:()->Unit,onPermission:()->Unit,onGuide:()->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var exclusions by remember { mutableStateOf(false) };var clear by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) };var about by remember { mutableStateOf(false) }
    FullPage(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("图库设置",onDismiss,tag="closeSettings")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SettingRow("排除目录",if(state.excluded.isEmpty()) "选择不想在拾图中看到的文件夹" else "已排除 ${state.excluded.size} 个目录",{exclusions=true},Modifier.testTag("manageExclusions"),UiIcons.Folder)
                SettingRow("照片访问权限",state.access,onPermission,icon=UiIcons.Image)
                SettingRow("刷新图库",if(state.discovering) "正在读取…" else "检查新增、移动或删除的图片",{if(!state.discovering) vm.refreshLibrary()},icon=UiIcons.Similar)
                HorizontalDivider(Modifier.padding(horizontal=20.dp,vertical=12.dp))
                Column(Modifier.padding(horizontal=12.dp)) {
                    Disclosure("测试与维护",tag="maintenance") {
                        SettingRow("清除文字识别记录","可指定所选、当前范围或全部",{clear=true},Modifier.testTag("manageClearRecords"))
                        SettingRow("性能报告","查看本机处理耗时与内存记录",{scope.launch { report=withContext(Dispatchers.IO) { vm.app.metrics.report() } }},Modifier.testTag("performanceReport"),UiIcons.Info)
                    }
                }
                SettingRow("操作指引","补全信息、搜索和找相近",onGuide,Modifier.testTag("openGuide"),UiIcons.Search)
                SettingRow("关于拾图","${BuildConfig.VERSION_NAME} · ${context.getString(R.string.edition_name)}",{about=true},icon=UiIcons.Info)
            }
        }
    }
    if(exclusions) ExcludedFolderSheet(state,vm,{exclusions=false})
    if(clear) ClearRecordsDialog(vm,onDismiss={clear=false})
    if(about) AlertDialog(onDismissRequest={about=false},title={Text("${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}")},text={Text("构建 ${BuildConfig.VERSION_CODE}\n\n图片和搜索记录保存在手机内，无联网权限。原图只读。")},confirmButton={TextButton(onClick={about=false}) { Text("关闭") }})
    report?.let { value ->FullPage({report=null}) {
        val data=remember(value) { JSONObject(value) }
        Column(Modifier.fillMaxSize()) {
            PageHeader("性能报告",{report=null},tag="closeReport")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                PerformanceCards(data)
                Disclosure("完整报告") { SectionCard { Text(value,style=MaterialTheme.typography.bodySmall) } }
            }
            Button(onClick={
                val file=File(File(context.cacheDir,"reports").apply { mkdirs() },"findthatpic-performance.json").apply { writeText(value) };val uri=FileProvider.getUriForFile(context,"${context.packageName}.reports",file)
                safeStart(context,Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"导出报告"))
            },modifier=Modifier.fillMaxWidth().padding(20.dp).height(52.dp)) { Text("导出报告") }
        }
    } }
}
