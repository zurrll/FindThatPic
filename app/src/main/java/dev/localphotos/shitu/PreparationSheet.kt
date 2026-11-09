package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PreparationSheet(vm:PhotoViewModel,onDismiss:()->Unit,options:SearchOptions?=null,ids:List<Long>?=null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val range=options?:state.options
    var stats by remember { mutableStateOf(PreparationStats()) }
    var retry by remember { mutableStateOf(false) }
    LaunchedEffect(range,ids,progress.done,state.workActive,state.stats.total,state.excluded) {
        stats=withContext(Dispatchers.IO) { vm.app.database.preparationStats(if(ids==null) range else SearchOptions(),ids) }
    }
    val paused=progress.paused && !state.workActive
    val pending=(stats.total-stats.complete).coerceAtLeast(0)
    val hasFailures=stats.ocrFailed+stats.similarFailed+stats.descriptionFailed>0
    val scope=when {
        ids!=null -> "所选图片"
        range.hasMetadataFilters -> "当前筛选"
        else -> "全部图库"
    }
    val noWork=pending==0 && stats.total>0 && !retry
    val canStart=state.kinds.stages.isNotEmpty() && !state.discovering && !state.changingRecords && !noWork && stats.total>0

    FullPage(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("补全搜索信息",onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("$scope · ${stats.complete} / ${stats.total} 张已就绪",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    state.workActive -> "正在补全"
                    paused -> "进度已保存"
                    pending>0 -> "$pending 张待补全"
                    stats.total>0 -> "搜索信息已就绪"
                    else -> "当前范围没有图片"
                },style=MaterialTheme.typography.headlineSmall)
                if((state.workActive || paused) && progress.target>0) {
                    Text(progress.phase,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("preparationPhase"))
                    LinearProgressIndicator(progress={ (progress.done.toFloat()/progress.target).coerceIn(0f,1f) },
                        modifier=Modifier.fillMaxWidth().height(4.dp))
                }
                HorizontalDivider()
                if(ids==null) {
                    QuietHeading("本轮最多处理")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(100,500,2000,Int.MAX_VALUE).forEach { count ->
                            FilterChip(state.limit==count,{vm.limit(count)},enabled=!state.workActive,
                                label={Text(if(count==Int.MAX_VALUE) "全部" else "$count 张")},
                                modifier=Modifier.testTag("prepareLimit$count"))
                        }
                    }
                }
                QuietHeading("准备内容")
                PreparationOption("文字与含义",stats.ocr-stats.withText+stats.meaning,stats.total,state.kinds.text,"prepareText",!state.workActive) {
                    vm.kinds(state.kinds.copy(text=it))
                }
                PreparationOption("相近图片",stats.similar,stats.total,state.kinds.similar,"prepareSimilar",!state.workActive) {
                    vm.kinds(state.kinds.copy(similar=it))
                }
                PreparationOption("画面描述",stats.description,stats.total,state.kinds.description,"prepareDescription",!state.workActive) {
                    vm.kinds(state.kinds.copy(description=it))
                }
                if(hasFailures) Disclosure("更多选项",tag="preparationOptions") {
                    PreparationOption("重试失败的图片",null,stats.total,retry,"prepareRetry",!state.workActive) { retry=it }
                }
                if(state.message.isNotBlank()) Text(state.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                when {
                    state.workActive -> Button(onClick=vm::pause,modifier=Modifier.fillMaxWidth().heightIn(min=46.dp).testTag("preparePause")) { Text("暂停") }
                    paused -> {
                        Button(onClick=vm::continuePreparation,modifier=Modifier.fillMaxWidth().heightIn(min=46.dp).testTag("prepareResume")) { Text("继续补全") }
                        TextButton(onClick={vm.prepare(range,retry,ids)},enabled=canStart,modifier=Modifier.fillMaxWidth().testTag("prepareStart")) { Text("按当前选择开始新一轮") }
                    }
                    else -> Button(onClick={vm.prepare(range,retry,ids)},enabled=canStart,
                        modifier=Modifier.fillMaxWidth().heightIn(min=46.dp).testTag("prepareStart")) {
                        Text(if(noWork) "全部已就绪" else "开始准备")
                    }
                }
            }
        }
    }
}

@Composable private fun PreparationOption(title:String,count:Int?,total:Int,checked:Boolean,tag:String,enabled:Boolean,onChange:(Boolean)->Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag(tag).toggleable(checked,enabled,Role.Checkbox,onChange),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            if(count!=null) Text("$count / $total",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Checkbox(checked,onCheckedChange=null,enabled=enabled,modifier=Modifier.padding(horizontal=4.dp))
        }
        HorizontalDivider()
    }
}
