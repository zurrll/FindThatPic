package dev.localphotos.shitu

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphotos.shitu.data.*

@Composable internal fun PhotoScreen(vm:PhotoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle();val progress by vm.progress.collectAsStateWithLifecycle()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    fun endInput() { focus.clearFocus();keyboard?.hide() }
    var sources by remember { mutableStateOf(false) };var filters by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) };var preparation by remember { mutableStateOf(false) }
    var preparationIds by remember { mutableStateOf<List<Long>?>(null) }
    var photo by remember { mutableStateOf<Long?>(null) };var similar by remember { mutableStateOf<Long?>(null) }
    var clearIds by remember { mutableStateOf<List<Long>?>(null) }
    var guide by rememberSaveable { mutableStateOf(vm.shouldShowGuide()) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshLibrary() }
    fun requestPhotos() { permission.launch(buildList {
        if(Build.VERSION.SDK_INT>=33) { add(Manifest.permission.READ_MEDIA_IMAGES);add(Manifest.permission.POST_NOTIFICATIONS) } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()) }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
        if(state.selecting) SelectionTools(state,vm,onClear={clearIds=state.selected.toList()},onPrepare={preparationIds=state.selected.toList();preparation=true})
    }) { padding ->
        LazyVerticalGrid(columns=GridCells.Adaptive(108.dp),modifier=Modifier.fillMaxSize().padding(padding).testTag("results"),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item(key="header",span={GridItemSpan(maxLineSpan)}) {
                Row(Modifier.fillMaxWidth().height(52.dp).testTag("homeHeader"),verticalAlignment=Alignment.CenterVertically) {
                    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primary) { Icon(UiIcons.Image,null,tint=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.padding(9.dp).size(22.dp)) }
                    Text(androidx.compose.ui.res.stringResource(R.string.app_name),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).padding(start=12.dp))
                    TextButton(onClick={endInput();management=true},modifier=Modifier.testTag("librarySettings")) { Icon(UiIcons.Settings,null,Modifier.size(19.dp));Spacer(Modifier.width(6.dp));Text("设置") }
                }
            }
            if(!state.allowed) item(span={GridItemSpan(maxLineSpan)}) {
                SectionCard { Text("从图库找回图片",style=MaterialTheme.typography.headlineMedium);Text("图片在手机内处理，原图不会改变。",style=MaterialTheme.typography.bodyMedium)
                    Button(onClick={requestPhotos()},modifier=Modifier.fillMaxWidth().height(52.dp).testTag("grantPhotos")) { Text("选择可访问的照片") } }
            } else {
                if(!state.selecting) item(key="preparation",span={GridItemSpan(maxLineSpan)}) {
                    val ready=state.prepared.total>0 && state.prepared.complete==state.prepared.total
                    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(if(ready) UiIcons.Check else UiIcons.Search,null,tint=MaterialTheme.colorScheme.onPrimaryContainer)
                                Column(Modifier.weight(1f)) {
                                    Text(if(state.discovering) "正在读取图库" else if(state.workActive) "正在补全搜索信息" else if(ready) "搜索信息已就绪" else "补全信息，找图更完整",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                    Text(if(state.discovering) "已读取 ${state.discovered} 张" else if(state.workActive) progress.phase else "已就绪 ${state.prepared.complete} / ${state.prepared.total} 张",style=MaterialTheme.typography.bodySmall)
                                }
                                if(ready && !state.workActive) TextButton(onClick={preparationIds=null;preparation=true},modifier=Modifier.testTag("indexButton")) { Text("查看") }
                            }
                            if(state.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if(state.workActive) LinearProgressIndicator(progress={progress.done.toFloat()/progress.target.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                            if(!ready || state.workActive) Button(onClick={endInput();preparationIds=null;preparation=true},enabled=!state.discovering && !state.changingRecords,modifier=Modifier.fillMaxWidth().height(46.dp).testTag("indexButton")) { Text(if(state.workActive) "查看进度" else if(progress.paused) "继续补全信息" else "补全搜索信息") }
                        }
                    }
                }
                item(key="search",span={GridItemSpan(maxLineSpan)}) {
                    OutlinedTextField(state.query,vm::query,modifier=Modifier.fillMaxWidth().testTag("searchField"),singleLine=true,shape=RoundedCornerShape(18.dp),
                        leadingIcon={Icon(UiIcons.Search,null)},placeholder={Text(if(state.mode==SearchMode.TEXT) "搜索图片里的文字或内容" else "描述想找的画面")},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={endInput();vm.searchNow()}),
                        trailingIcon={if(state.query.isNotEmpty()) TextButton(onClick={vm.query("")}) { Text("清除") }})
                }
                if(BuildConfig.HAS_DESCRIPTION) item(key="modes",span={GridItemSpan(maxLineSpan)}) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        SearchMode.entries.forEach { mode ->
                            val selected=state.mode==mode
                            Surface(onClick={vm.mode(mode)},shape=RoundedCornerShape(14.dp),color=if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.weight(1f).testTag("searchMode${mode.name}")) {
                                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) { Icon(if(mode==SearchMode.TEXT) UiIcons.Text else UiIcons.Image,null,Modifier.size(19.dp));Spacer(Modifier.width(8.dp));Text(if(mode==SearchMode.TEXT) "文字搜索" else "描述找图",style=MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
                item(key="filters",span={GridItemSpan(maxLineSpan)}) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick={endInput();sources=true},modifier=Modifier.testTag("folderButton")) { Text(when { state.options.folders.isNotEmpty()->"目录 · ${state.options.folders.size}";state.options.source!=null->state.options.source!!.label;else->"全部目录" }) }
                        TextButton(onClick={endInput();filters=true},modifier=Modifier.testTag("filterButton")) { Text(if(state.options.hasMetadataFilters || state.filter!=PhotoFilter.ALL || (state.mode==SearchMode.TEXT && state.options.originalOnly)) "筛选 · 已选" else "筛选") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick={endInput();vm.selecting(!state.selecting)},modifier=Modifier.testTag("selectMode")) { Text(if(state.selecting) "取消选择" else "多选") }
                    }
                }
                item(key="galleryTitle",span={GridItemSpan(maxLineSpan)}) {
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                        Text(if(state.query.isBlank()) "我的图库" else "搜索结果 · ${state.hits.size}${if(state.hits.size==100) "+" else ""}",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                        if(state.searching) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
                        if(state.stats.marked>0 || state.options.markedOnly) TextButton(onClick={vm.options(state.options.copy(markedOnly=!state.options.markedOnly))},modifier=Modifier.testTag("markedShortcut")) { Icon(UiIcons.Star,null,Modifier.size(15.dp));Spacer(Modifier.width(4.dp));Text(if(state.options.markedOnly) "查看全部" else "已标记") }
                    }
                }
                if(state.message.isNotBlank()) item(span={GridItemSpan(maxLineSpan)}) { Text(state.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                if(state.hits.isEmpty() && !state.searching) item(span={GridItemSpan(maxLineSpan)}) {
                    Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(UiIcons.Search,null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(state.mode==SearchMode.DESCRIPTION && state.query.isNotBlank() && state.prepared.description==0) "先补全画面信息，再试试描述找图" else if(state.query.isNotBlank()) "没有找到合适的图片" else "这个范围里还没有图片",style=MaterialTheme.typography.bodyLarge)
                        if(state.options.hasMetadataFilters || state.filter!=PhotoFilter.ALL) TextButton(onClick=vm::resetFilters) { Text("清除筛选") }
                    }
                }
                items(state.hits,key={"photo_${it.photo.id}"}) { hit -> PhotoCard(hit,state.selecting,hit.photo.id in state.selected,
                    onClick={endInput();if(state.selecting) vm.select(hit.photo.id) else photo=hit.photo.id},onLongClick={endInput();vm.selecting(true);vm.select(hit.photo.id)}) }
                if(state.query.isBlank() && state.hits.size>=state.browseLimit) item(span={GridItemSpan(maxLineSpan)}) { TextButton(onClick=vm::more,modifier=Modifier.fillMaxWidth()) { Text("加载更多图片") } }
            }
        }
    }
    if(sources) SourceSheet(state,vm,{sources=false})
    if(filters) FilterSheet(state,vm,{filters=false})
    if(preparation) PreparationSheet(vm,{preparation=false},ids=preparationIds)
    if(management) LibrarySheet(state,vm,{management=false},{requestPhotos()},{management=false;guide=true})
    photo?.let { id ->PhotoInspector(vm,id,{photo=null},onSimilar={photo=null;similar=it}) }
    similar?.let { id ->SimilarPhotos(vm,id,{similar=null}) }
    clearIds?.let { ids ->ClearRecordsDialog(vm,ids,{clearIds=null}) }
    if(guide) GettingStarted { vm.finishGuide();guide=false }
}

@Composable private fun SelectionTools(state:UiState,vm:PhotoViewModel,onClear:()->Unit,onPrepare:()->Unit) {
    var more by remember { mutableStateOf(false) }
    Surface(tonalElevation=4.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("已选 ${state.selected.size} 张",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                TextButton(onClick=vm::selectVisible,modifier=Modifier.testTag("selectVisible")) { Text("选择当前结果") }
                TextButton(onClick={vm.selecting(false)},modifier=Modifier.testTag("finishSelection")) { Text("完成") }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={vm.mark(state.selected.toList(),true)},enabled=state.selected.isNotEmpty(),modifier=Modifier.weight(1f).testTag("markSelected")) { Text("标记") }
                Box {
                    TextButton(onClick={more=true},modifier=Modifier.testTag("selectionMore")) { Text("更多") }
                    DropdownMenu(more,onDismissRequest={more=false}) {
                        DropdownMenuItem(text={Text(if(state.selected.isEmpty()) "连续选择 500 张" else "再选 500 张")},onClick={more=false;vm.selectBatch()},enabled=!state.selectingBatch,modifier=Modifier.testTag("selectBatch"))
                        DropdownMenuItem(text={Text("补全所选图片的信息")},onClick={more=false;onPrepare()},enabled=state.selected.isNotEmpty() && !state.workActive,modifier=Modifier.testTag("prepareSelected"))
                        DropdownMenuItem(text={Text("取消标记")},onClick={more=false;vm.mark(state.selected.toList(),false)},enabled=state.selected.isNotEmpty(),modifier=Modifier.testTag("unmarkSelected"))
                        DropdownMenuItem(text={Text("重新识别文字")},onClick={more=false;vm.rerun(state.selected.toList())},enabled=state.selected.isNotEmpty() && state.selected.size<=PhotoLimits.OCR_BATCH && !state.workActive && !state.changingRecords,modifier=Modifier.testTag("rerunSelected"))
                        DropdownMenuItem(text={Text("清除文字识别记录")},onClick={more=false;onClear()},enabled=state.selected.isNotEmpty() && !state.changingRecords,modifier=Modifier.testTag("clearSelectedRecords"))
                    }
                }
            }
        }
    }
}
