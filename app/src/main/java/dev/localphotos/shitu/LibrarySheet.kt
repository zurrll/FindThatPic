package dev.localphotos.shitu

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable internal fun LibrarySheet(state:UiState,vm:PhotoViewModel,onDismiss:()->Unit,onPermission:()->Unit,onGuide:()->Unit,onPrepare:()->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var exclusions by remember { mutableStateOf(false) };var clear by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) };var about by remember { mutableStateOf(false) }
    FullPage(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("图库设置",onDismiss,tag="closeSettings")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(2.dp)) {
                QuietHeading("图库")
                LibraryRow("补全搜索信息",UiIcons.Check,onPrepare,tag="settingsPrepare")
                LibraryRow("排除目录",UiIcons.Folder,{exclusions=true},value=if(state.excluded.isEmpty()) "" else "${state.excluded.size} 个",tag="manageExclusions")
                LibraryRow("照片访问权限",UiIcons.Image,onPermission,value=state.access)
                LibraryRow("刷新图库",UiIcons.Similar,{vm.refreshLibrary()},value=if(state.discovering) "正在读取" else "",enabled=!state.discovering)
                Spacer(Modifier.height(8.dp))
                QuietHeading("帮助")
                LibraryRow("操作指引",UiIcons.Search,onGuide,tag="openGuide")
                LibraryRow("关于拾图",UiIcons.Info,{about=true},value=BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(8.dp))
                QuietHeading("诊断与维护")
                LibraryRow("性能报告",UiIcons.Info,{scope.launch { report=withContext(Dispatchers.IO) { vm.app.metrics.report() } }},tag="performanceReport")
                LibraryRow("清除文字识别记录",UiIcons.Close,{clear=true},tag="manageClearRecords")
            }
        }
    }
    if(exclusions) ExcludedFolderSheet(state,vm,{exclusions=false})
    if(clear) ClearRecordsDialog(vm,onDismiss={clear=false})
    if(about) AlertDialog(onDismissRequest={about=false},title={Text("${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}")},text={Text("构建 ${BuildConfig.VERSION_CODE}\n\n图片和搜索记录保存在手机内，无联网权限。原图只读。")},confirmButton={TextButton(onClick={about=false}) { Text("关闭") }})
    report?.let { value ->
        FullPage({report=null}) {
            val data=remember(value) { JSONObject(value) }
            Column(Modifier.fillMaxSize()) {
                PageHeader("性能报告",{report=null},tag="closeReport") {
                    IconButton(onClick={
                        val file=File(File(context.cacheDir,"reports").apply { mkdirs() },"findthatpic-performance.json").apply { writeText(value) }
                        val uri=FileProvider.getUriForFile(context,"${context.packageName}.reports",file)
                        safeStart(context,Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"导出报告"))
                    },modifier=Modifier.testTag("exportPerformance")) { Icon(UiIcons.Share,"导出报告") }
                }
                PerformanceCards(data,value)
            }
        }
    }
}

@Composable private fun LibraryRow(title:String,icon:ImageVector,onClick:()->Unit,value:String="",tag:String="",enabled:Boolean=true) {
    Column {
        val modifier=if(tag.isBlank()) Modifier else Modifier.testTag(tag)
        Surface(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth(),color=Color.Transparent) {
            Row(Modifier.heightIn(min=48.dp).padding(horizontal=2.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(icon,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                if(value.isNotBlank()) Text(value,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(UiIcons.Chevron,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
}
