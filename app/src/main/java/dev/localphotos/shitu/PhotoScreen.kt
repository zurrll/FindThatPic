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
import androidx.compose.ui.graphics.Color
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
    var searchModes by remember { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(vm.shouldShowGuide()) }
    fun openPreparation() { endInput();preparationIds=null;preparation=true }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshLibrary() }
    fun requestPhotos() { permission.launch(buildList {
        if(Build.VERSION.SDK_INT>=33) { add(Manifest.permission.READ_MEDIA_IMAGES);add(Manifest.permission.POST_NOTIFICATIONS) } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()) }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
        if(state.selecting) SelectionTools(state,vm,onClear={clearIds=state.selected.toList()},onPrepare={preparationIds=state.selected.toList();preparation=true})
    }) { padding ->
        LazyVerticalGrid(columns=GridCells.Adaptive(100.dp),modifier=Modifier.fillMaxSize().padding(padding).testTag("results"),contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            item(key="header",span={GridItemSpan(maxLineSpan)}) {
                Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("homeHeader"),verticalAlignment=Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(R.string.app_name),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    IconButton(onClick={endInput();management=true},modifier=Modifier.testTag("librarySettings")) {
                        Icon(UiIcons.Settings,"设置",Modifier.size(20.dp))
                    }
                }
            }
            if(!state.allowed) item(span={GridItemSpan(maxLineSpan)}) {
                SectionCard { Text("从图库找回图片",style=MaterialTheme.typography.headlineMedium);Text("图片在手机内处理，原图不会改变。",style=MaterialTheme.typography.bodyMedium)
                    Button(onClick={requestPhotos()},modifier=Modifier.fillMaxWidth().height(52.dp).testTag("grantPhotos")) { Text("选择可访问的照片") } }
            } else {
                item(key="search",span={GridItemSpan(maxLineSpan)}) {
                    TextField(state.query,vm::query,modifier=Modifier.fillMaxWidth().testTag("searchField"),
                        singleLine=true,shape=RoundedCornerShape(14.dp),
                        colors=TextFieldDefaults.colors(focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,
                            focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant),
                        leadingIcon={Icon(UiIcons.Search,null,Modifier.size(20.dp))},
                        placeholder={Text(if(state.mode==SearchMode.TEXT) "搜截图里的字或文件名" else "描述想找的画面",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={endInput();vm.searchNow()}),
                        trailingIcon={
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                if(state.query.isNotEmpty()) IconButton(onClick={vm.query("")},modifier=Modifier.testTag("clearQuery")) { Icon(UiIcons.Close,"清除搜索",Modifier.size(18.dp)) }
                                if(BuildConfig.HAS_DESCRIPTION) Box {
                                    TextButton(onClick={endInput();searchModes=true},modifier=Modifier.testTag("searchModeMenu")) {
                                        Text(if(state.mode==SearchMode.TEXT) "文字" else "画面")
                                        Icon(UiIcons.Down,"切换搜索方式",Modifier.size(16.dp))
                                    }
                                    DropdownMenu(searchModes,onDismissRequest={searchModes=false}) {
                                        SearchMode.entries.forEach { mode ->
                                            DropdownMenuItem(text={Text(if(mode==SearchMode.TEXT) "文字搜索" else "描述画面")},
                                                onClick={searchModes=false;vm.mode(mode)},modifier=Modifier.testTag("searchMode${mode.name}"))
                                        }
                                    }
                                }
                            }
                        })
                }
                item(key="filters",span={GridItemSpan(maxLineSpan)}) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={endInput();sources=true},modifier=Modifier.testTag("folderButton")) {
                            Text(when { state.options.folders.isNotEmpty()->"目录 · ${state.options.folders.size}";state.options.source!=null->state.options.source!!.label;else->"全部目录" })
                            Icon(UiIcons.Down,null,Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        val filtered=state.options.hasMetadataFilters || state.filter!=PhotoFilter.ALL || (state.mode==SearchMode.TEXT && state.options.originalOnly)
                        IconButton(onClick={endInput();filters=true},modifier=Modifier.testTag("filterButton")) {
                            Icon(UiIcons.Filter,if(filtered) "筛选 · 已选" else "筛选",Modifier.size(20.dp),
                                tint=if(filtered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick={endInput();vm.selecting(!state.selecting)},modifier=Modifier.testTag("selectMode")) {
                            Icon(if(state.selecting) UiIcons.Close else UiIcons.Select,if(state.selecting) "取消选择" else "多选",Modifier.size(20.dp))
                        }
                    }
                }
                val pending=(state.prepared.total-state.prepared.complete).coerceAtLeast(0)
                if(!state.selecting && (state.discovering || state.workActive || progress.paused || pending>0 || state.prepared.ocrFailed+state.prepared.similarFailed+state.prepared.descriptionFailed>0)) {
                    item(key="preparation",span={GridItemSpan(maxLineSpan)}) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                val label=when {
                                    state.discovering->"正在读取图库"
                                    state.workActive->progress.phase
                                    pending>0->"$pending 张待补全"
                                    else->"查看准备记录"
                                }
                                Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,
                                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if(!state.discovering) TextButton(onClick=::openPreparation,enabled=!state.changingRecords,modifier=Modifier.testTag("indexButton")) {
                                    Text(if(state.workActive) "查看进度" else if(progress.paused) "继续补全" else "补全信息")
                                }
                            }
                            if(state.workActive && progress.target>0) LinearProgressIndicator(progress={progress.done.toFloat()/progress.target},modifier=Modifier.fillMaxWidth().height(2.dp))
                            else HorizontalDivider()
                        }
                    }
                }
                if(state.query.isNotBlank() || state.searching) item(key="galleryTitle",span={GridItemSpan(maxLineSpan)}) {
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=6.dp)) {
                        if(state.query.isNotBlank()) Text("${state.hits.size}${if(state.hits.size==100) "+" else ""} 个结果",
                            style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                        if(state.searching) { if(state.query.isBlank()) Spacer(Modifier.weight(1f));CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp) }
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
    if(management) LibrarySheet(state,vm,{management=false},{requestPhotos()},{management=false;guide=true},
        onPrepare={management=false;openPreparation()})
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
                TextButton(onClick=onPrepare,enabled=state.selected.isNotEmpty() && !state.workActive,modifier=Modifier.weight(1f).testTag("prepareSelected")) { Text("补全所选") }
                Box {
                    TextButton(onClick={more=true},modifier=Modifier.testTag("selectionMore")) { Text("更多") }
                    DropdownMenu(more,onDismissRequest={more=false}) {
                        DropdownMenuItem(text={Text(if(state.selected.isEmpty()) "连续选择 500 张" else "再选 500 张")},onClick={more=false;vm.selectBatch()},enabled=!state.selectingBatch,modifier=Modifier.testTag("selectBatch"))
                        DropdownMenuItem(text={Text("重新识别文字")},onClick={more=false;vm.rerun(state.selected.toList())},enabled=state.selected.isNotEmpty() && state.selected.size<=PhotoLimits.OCR_BATCH && !state.workActive && !state.changingRecords,modifier=Modifier.testTag("rerunSelected"))
                        DropdownMenuItem(text={Text("清除文字识别记录")},onClick={more=false;onClear()},enabled=state.selected.isNotEmpty() && !state.changingRecords,modifier=Modifier.testTag("clearSelectedRecords"))
                    }
                }
            }
        }
    }
}
