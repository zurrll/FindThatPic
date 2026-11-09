package dev.localphotos.shitu

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.localphotos.shitu.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SourceSheet(state:UiState,vm:PhotoViewModel,onDismiss:()->Unit) {
    val options=state.options
    var folders by remember { mutableStateOf(options.folders.isNotEmpty()) };var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SheetTitle("图片范围",onDismiss)
        Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!folders,{folders=false},label={Text("按来源")},modifier=Modifier.testTag("sourceTabKinds"))
            FilterChip(folders,{folders=true},label={Text("按目录")},modifier=Modifier.testTag("sourceTabFolders"))
        }
        if(folders) OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("查找目录")},leadingIcon={Icon(UiIcons.Search,null)},modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp).testTag("folderSearch"))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp).testTag("sourceList"),contentPadding=PaddingValues(bottom=24.dp)) {
            if(!folders) {
                item { ListItem(headlineContent={Text("全部图片")},leadingContent={RadioButton(options.source==null && options.folders.isEmpty(),null)},modifier=Modifier.clickable { vm.options(options.copy(source=null,folders=emptySet())) }) }
                items(SourceKind.entries) { source ->
                    ListItem(headlineContent={Text(source.label)},leadingContent={RadioButton(options.source==source,null)},modifier=Modifier.clickable { vm.options(options.copy(source=source,folders=emptySet())) })
                }
                item { Text("来源根据图片所在目录判断。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(20.dp,8.dp)) }
            } else {
                item { TextButton(onClick={vm.options(options.copy(folders=emptySet(),source=null))},modifier=Modifier.padding(horizontal=12.dp)) { Text("选择全部目录") } }
                items(state.stats.folders.filter { folder ->state.excluded.none { folder.path.startsWith(it) } && folder.path.contains(query,true) },key={it.path}) { folder ->
                    ListItem(headlineContent={Text(folder.path.trimEnd('/').substringAfterLast('/').ifBlank { "根目录" })},supportingContent={Text(folder.path)},
                        trailingContent={Text("${folder.count}",style=MaterialTheme.typography.labelMedium)},leadingContent={Checkbox(folder.path in options.folders,null)},modifier=Modifier.testTag("source_${folder.path}").clickable { vm.options(options.copy(source=null,folders=if(folder.path in options.folders) options.folders-folder.path else options.folders+folder.path)) })
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilterSheet(state:UiState,vm:PhotoViewModel,onDismiss:()->Unit) {
    val options=state.options;val context=LocalContext.current
    var more by remember { mutableStateOf(options.format!=ImageFormat.ALL || options.longOnly || options.originalOnly || options.undatedOnly || options.dateField!=DateField.ADDED || state.filter!=PhotoFilter.ALL) }
    var diagnostics by remember { mutableStateOf(state.filter!=PhotoFilter.ALL) }
    val today=LocalDate.now();val zone=ZoneId.systemDefault()
    val preset=listOf(30,90).any { days ->options.after==today.minusDays(days.toLong()-1).atStartOfDay(zone).toEpochSecond() && options.before==today.plusDays(1).atStartOfDay(zone).toEpochSecond() }
    var custom by remember { mutableStateOf(!preset && (options.after>0 || options.before>0)) }
    fun chooseDate(start:Boolean) {
        val zone=ZoneId.systemDefault();val stored=if(start) options.after else if(options.before>0) options.before-1 else 0
        var initial=if(stored>0) Instant.ofEpochSecond(stored).atZone(zone).toLocalDate() else LocalDate.now()
        if(start && options.before>0) initial=minOf(initial,Instant.ofEpochSecond(options.before-1).atZone(zone).toLocalDate())
        if(!start && options.after>0) initial=maxOf(initial,Instant.ofEpochSecond(options.after).atZone(zone).toLocalDate())
        DatePickerDialog(context,{_,year,month,day ->
            val date=LocalDate.of(year,month+1,day);val seconds=(if(start) date else date.plusDays(1)).atStartOfDay(zone).toEpochSecond()
            vm.options(if(start) options.copy(after=seconds,undatedOnly=false) else options.copy(before=seconds,undatedOnly=false))
        },initial.year,initial.monthValue-1,initial.dayOfMonth).apply {
            if(start && options.before>0) datePicker.maxDate=options.before*1000-1
            if(!start && options.after>0) datePicker.minDate=options.after*1000
        }.show()
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SheetTitle("筛选图片",onDismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=580.dp).testTag("filterList"),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            item {
                Label("时间范围")
                Choices {
                    FilterChip(options.after==0L && options.before==0L && !options.undatedOnly,{custom=false;vm.options(options.copy(after=0,before=0,undatedOnly=false))},label={Text("不限")})
                    listOf(30,90).forEach { days ->
                        val after=LocalDate.now().minusDays(days.toLong()-1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();val before=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                        FilterChip(options.after==after && options.before==before && !options.undatedOnly,{custom=false;vm.options(options.copy(after=after,before=before,undatedOnly=false))},label={Text("近${days}天")})
                    }
                    FilterChip(custom,{custom=!custom},label={Text("自定义")})
                }
                if(custom) Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={chooseDate(true)},modifier=Modifier.testTag("dateStart")) { Text(dateLabel(options.after,"起始日期")) }
                    OutlinedButton(onClick={chooseDate(false)},modifier=Modifier.testTag("dateEnd")) { Text(dateLabel(if(options.before>0) options.before-1 else 0,"结束日期")) }
                }
                Label("结果排序")
                Choices { ResultOrder.entries.forEach { order ->FilterChip(options.order==order,{vm.options(options.copy(order=order))},label={Text(when(order) { ResultOrder.RELEVANCE->"相关度";ResultOrder.NEWEST->"最新在前";ResultOrder.OLDEST->"最早在前" })}) } }
                Toggle("只看已标记",options.markedOnly,{vm.options(options.copy(markedOnly=it))},"markedOnly")
                SettingRow(if(more) "收起更多条件" else "更多条件","格式、长图和文字匹配",{more=!more},Modifier.testTag("moreFilters"))
            }
            if(more) item {
                Label("文件格式");Choices { ImageFormat.entries.forEach { format ->FilterChip(options.format==format,{vm.options(options.copy(format=format))},label={Text(format.label)},modifier=Modifier.testTag("format_${format.name}")) } }
                Toggle("只看长图",options.longOnly,{vm.options(options.copy(longOnly=it))},"longOnly")
                if(state.mode==SearchMode.TEXT) Toggle("只匹配原词",options.originalOnly,{vm.options(options.copy(originalOnly=it))},"originalOnly")
                Label("时间依据");Choices { DateField.entries.forEach { field ->FilterChip(options.dateField==field,{vm.options(options.copy(dateField=field))},label={Text(field.label)}) } }
                Toggle("只看时间缺失的图片",options.undatedOnly,{vm.options(options.copy(undatedOnly=it,after=0,before=0))},"undatedOnly")
                TextButton(onClick={diagnostics=!diagnostics},modifier=Modifier.padding(horizontal=12.dp).testTag("diagnosticFilters")) { Text(if(diagnostics) "收起技术筛选" else "技术筛选") }
            }
            if(more && diagnostics) items(PhotoFilter.entries.filter { it!=PhotoFilter.LONG }) { filter ->
                ListItem(headlineContent={Text(filter.label)},leadingContent={RadioButton(state.filter==filter,null)},modifier=Modifier.testTag("filter_${filter.name}").clickable { vm.filter(filter) })
            }
            item { TextButton(onClick={custom=false;more=false;diagnostics=false;vm.resetFilters()},modifier=Modifier.padding(horizontal=12.dp).testTag("clearFilters")) { Text("重置筛选") } }
        }
    }
}
@Composable internal fun SheetTitle(title:String,onDismiss:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=onDismiss,modifier=Modifier.testTag("sheetDone")) { Text("完成") }
    }
}
@Composable private fun Label(value:String) { Text(value,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(horizontal=20.dp,vertical=10.dp)) }
@Composable private fun Choices(content:@Composable RowScope.()->Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),content=content) }
@Composable private fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit,tag:String) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) { Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(checked,onChange,modifier=Modifier.testTag(tag)) }
}
internal fun dateLabel(seconds:Long,fallback:String):String=if(seconds<=0) fallback else Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate().toString()
