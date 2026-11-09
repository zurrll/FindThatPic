package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PreparationSheet(vm:PhotoViewModel,onDismiss:()->Unit,options:SearchOptions?=null,ids:List<Long>?=null) {
    val state by vm.state.collectAsStateWithLifecycle();val progress by vm.progress.collectAsStateWithLifecycle()
    val range=options?:state.options
    var stats by remember { mutableStateOf(PreparationStats()) };var retry by remember { mutableStateOf(false) }
    LaunchedEffect(range,ids,progress.done,state.workActive,state.stats.total,state.excluded) {
        stats=withContext(Dispatchers.IO) { vm.app.database.preparationStats(if(ids==null) range else SearchOptions(),ids) }
    }
    FullPage(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("补全搜索信息",onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text(if(ids!=null) "为所选 ${stats.total} 张图片准备搜索" else if(range.hasMetadataFilters) "当前筛选 · ${stats.total} 张图片" else "图库中的 ${stats.total} 张图片",style=MaterialTheme.typography.headlineSmall)
                Text("已有结果会跳过，原图不会改变。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard {
                    Coverage("文字与含义",stats.ocr-stats.withText+stats.meaning,stats.total,stats.ocrFailed,UiIcons.Text)
                    if(BuildConfig.HAS_DESCRIPTION) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.surfaceVariant)
                        Coverage("画面描述",stats.description,stats.total,stats.descriptionFailed,UiIcons.Image)
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.surfaceVariant)
                    Coverage("相近图片",stats.similar,stats.total,stats.similarFailed,UiIcons.Similar)
                }
                if(stats.ocrFailed+stats.similarFailed+stats.descriptionFailed>0) SectionCard {
                    KindOption("同时重试失败的图片",retry,"prepareRetry",!state.workActive) { retry=it }
                }
                if(progress.target>0) SectionCard {
                    Text(progress.phase,style=MaterialTheme.typography.titleSmall,modifier=Modifier.testTag("preparationPhase"))
                    if(state.workActive) LinearProgressIndicator(progress={progress.done.toFloat()/progress.target.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                    if(progress.paused) Text("已保存进度，继续时会跳过完成项。",style=MaterialTheme.typography.bodySmall)
                }
                if(ids==null) {
                    Text("本轮处理数量",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(100,500,2000,Int.MAX_VALUE).forEach { count -> FilterChip(state.limit==count,{vm.limit(count)},enabled=!state.workActive,label={Text(if(count==Int.MAX_VALUE) "全部" else "$count 张")},modifier=Modifier.testTag("prepareLimit$count")) }
                    }
                }
                Text("依次处理，可随时暂停或离开本页。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Disclosure("自定义处理",tag="preparationOptions") {
                    SectionCard {
                        KindOption("文字与含义",state.kinds.text,"prepareText",!state.workActive) { vm.kinds(state.kinds.copy(text=it)) }
                        if(BuildConfig.HAS_DESCRIPTION) KindOption("画面描述",state.kinds.description,"prepareDescription",!state.workActive) { vm.kinds(state.kinds.copy(description=it)) }
                        KindOption("相近图片",state.kinds.similar,"prepareSimilar",!state.workActive) { vm.kinds(state.kinds.copy(similar=it)) }
                    }
                }
                if(state.message.isNotBlank()) Text(state.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            }
            Surface(tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(state.workActive) OutlinedButton(onClick=vm::pause,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("preparePause")) { Text("暂停处理") }
                    else {
                        if(progress.paused) Button(onClick=vm::continuePreparation,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("prepareResume")) { Text("继续上次进度") }
                        val noWork=stats.complete==stats.total && stats.total>0 && !retry
                        val action={vm.prepare(range,retry,ids)}
                        if(progress.paused) TextButton(onClick=action,enabled=state.kinds.stages.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("prepareStart")) { Text("按当前选择开始新一轮") }
                        else Button(onClick=action,enabled=state.kinds.stages.isNotEmpty() && !state.discovering && !state.changingRecords && !noWork && stats.total>0,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("prepareStart")) { Text(if(noWork) "全部已就绪" else "开始准备") }
                    }
                }
            }
        }
    }
}
@Composable private fun Coverage(title:String,count:Int,total:Int,failed:Int,icon:androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) { Text(title,style=MaterialTheme.typography.bodyLarge);Text(if(failed>0) "$failed 张需要重试" else if(count==total && total>0) "已就绪" else "尚有信息待补全",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("$count / $total",style=MaterialTheme.typography.labelLarge)
    }
}
@Composable private fun KindOption(title:String,checked:Boolean,tag:String,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) { Text(title,modifier=Modifier.weight(1f));Checkbox(checked,onChange,enabled=enabled,modifier=Modifier.testTag(tag)) }
}
