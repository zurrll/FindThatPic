package dev.localphotos.shitu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphotos.shitu.data.ClearScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ExcludedFolderSheet(state:UiState,vm:PhotoViewModel,onDismiss:()->Unit) {
    var path by remember { mutableStateOf("") };var manual by remember { mutableStateOf(false) }
    val folders=(state.stats.folders.map { it.path }+state.excluded).distinct().filter { folder ->
        folder in state.excluded || state.excluded.none { folder.startsWith(it) }
    }.sorted()
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SheetTitle("排除目录",onDismiss)
        Text("选中的目录及子目录将不出现在拾图中。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(20.dp,8.dp))
        TextButton(onClick={manual=!manual},modifier=Modifier.padding(horizontal=12.dp)) { Text(if(manual) "收起路径输入" else "手动输入目录路径") }
        if(manual) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(path,{path=it},singleLine=true,label={Text("目录路径")},placeholder={Text("Pictures/Example/")},
                modifier=Modifier.weight(1f).testTag("excludedPath"))
            TextButton(onClick={vm.exclude(path,true);path=""},enabled=path.isNotBlank(),modifier=Modifier.testTag("addExcludedFolder")) { Text("添加") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=460.dp).testTag("excludedList"),contentPadding=PaddingValues(bottom=24.dp)) {
            items(folders,key={it}) { folder ->
                val excluded=folder in state.excluded
                ListItem(headlineContent={Text(folder)},leadingContent={Checkbox(excluded,onCheckedChange=null)},
                    modifier=Modifier.testTag("exclude_$folder").clickable { vm.exclude(folder,!excluded) })
            }
        }
    }
}

@Composable internal fun ClearRecordsDialog(vm:PhotoViewModel,ids:List<Long>?=null,onDismiss:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf(if(ids.isNullOrEmpty()) "filtered" else "selected") }
    val target=when(choice) {
        "selected"->ClearScope(ids=ids)
        "filtered"->ClearScope(options=state.options,filter=state.filter)
        else->ClearScope()
    }
    val count by produceState<Int?>(null,target) {
        value=withContext(Dispatchers.IO) { vm.app.database.recognitionCount(target) }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("清除识别记录")},text={
        Column {
            if(!ids.isNullOrEmpty()) ClearChoice("所选 ${ids.size} 张",choice=="selected","clearSelected") { choice="selected" }
            ClearChoice("当前筛选范围",choice=="filtered","clearFiltered") { choice="filtered" }
            if(choice=="filtered") Text("按时间、目录、格式、标记和状态，不按搜索词。",style=MaterialTheme.typography.bodySmall)
            ClearChoice("全部识别记录",choice=="all","clearAll") { choice="all" }
            Text("清除文字与含义记录，保留原图、标记、说明及画面特征。当前准备任务会停止。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp))
        }
    },confirmButton={TextButton(onClick={vm.clearRecords(target);onDismiss()},enabled=count!=null && count!!>0 && !state.changingRecords,
        modifier=Modifier.testTag("confirmClearRecords")) { Text(if(count==null) "统计中" else "清除 $count 张") }},
        dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}
@Composable private fun ClearChoice(label:String,selected:Boolean,tag:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected,onClick=null);Text(label)
    }
}
