package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

private class SimilarPage(val id:Long) {
    val scroll=LazyGridState()
    var seed by mutableStateOf<Photo?>(null);var hits by mutableStateOf(emptyList<Hit>())
    var scope:SearchOptions?=null;var revision=-1L
    var stats by mutableStateOf(VisualStats())
}
private data class SimilarSnapshot(val hits:List<Hit>,val stats:VisualStats,val revision:Long)
@Composable internal fun SimilarPhotos(vm:PhotoViewModel,seedId:Long,onDismiss:()->Unit) {
    val app=vm.app
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val state by vm.state.collectAsStateWithLifecycle()
    val available=remember(seedId) { state.options.copy(originalOnly=false,order=ResultOrder.RELEVANCE) }
    val pages=remember(seedId) { mutableStateListOf(SimilarPage(seedId)) };val page=pages.last()
    var currentScope by remember { mutableStateOf(false) };val options=if(currentScope) available else SearchOptions()
    val stats=page.stats;var inspected by remember { mutableStateOf<Hit?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var loading by remember(page.id) { mutableStateOf(page.scope==null) };var failure by remember(page.id) { mutableStateOf("") }
    val covered=inspected!=null || preparing
    LaunchedEffect(page.id,options,lifecycle,covered) {
        if(covered) return@LaunchedEffect
        // Keep a scan alive during indexing. StateFlow conflates writes while it runs; the next
        // pass observes the latest revision, including replacements with unchanged ready counts.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { app.database.visualChanges.collect { revision ->
            if(page.scope!=options || page.revision!=revision) {
                loading=true;failure=""
                if(page.scope!=options) page.hits=emptyList()
                try {
                    page.seed=withContext(Dispatchers.IO) { app.database.get(page.id,brief=true)?:error("图片已不可访问") }
                    val result=withContext(Dispatchers.IO) {
                        app.visualSearch.prepare(page.id)
                        val scannedRevision=app.database.visualChanges.value
                        val hits=app.visualSearch.find(page.id,options)
                        SimilarSnapshot(hits,app.database.visualStats(options),scannedRevision)
                    }
                    page.hits=result.hits;page.stats=result.stats
                    page.scope=options;page.revision=result.revision
                } catch(e:CancellationException) { throw e } catch(e:Exception) { page.hits=emptyList();failure=e.message?:"暂时无法查找相近图片" }
                finally { loading=false }
                // Throttle completed scans, instead of restarting an unfinished one on every tick.
                delay(1000)
            }
        } }
    }
    fun back() { if(pages.size>1) pages.removeAt(pages.lastIndex) else onDismiss() }
    fun next(id:Long) { inspected=null;val existing=pages.indexOfFirst { it.id==id };if(existing>=0) while(pages.lastIndex>existing) pages.removeAt(pages.lastIndex) else pages.add(SimilarPage(id)) }
    FullPage({back()}) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(end=10.dp),verticalAlignment=Alignment.CenterVertically) {
                val nested=pages.size>1
                IconButton(onClick=::back,modifier=Modifier.testTag(if(nested) "similarBack" else "closeSimilar")) { Icon(UiIcons.Back,"返回") }
                Text("相近图片",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                if(nested) TextButton(onClick=onDismiss,modifier=Modifier.testTag("closeSimilar")) { Text("图库") }
            }
            key(page.id) {
                LazyVerticalGrid(columns=GridCells.Adaptive(144.dp),state=page.scroll,modifier=Modifier.weight(1f).testTag("similarResults"),
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    item(span={GridItemSpan(maxLineSpan)}) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                page.seed?.let { PhotoImage(it,Modifier.size(width=42.dp,height=36.dp),size=120) }
                                Text("参考图",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier=Modifier.weight(1f).testTag("similarSeed").semantics { stateDescription=page.seed?.name.orEmpty() })
                                val scopeLabel=if(currentScope) "当前范围" else "全部图库"
                                if(available.hasMetadataFilters) {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick={expanded=true},modifier=Modifier.testTag("visualScopeMenu")) {
                                            Text(scopeLabel);Icon(UiIcons.Down,null,Modifier.size(16.dp))
                                        }
                                        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
                                            DropdownMenuItem(text={Text("全部图库")},onClick={expanded=false;currentScope=false},modifier=Modifier.testTag("visualAllScope"))
                                            DropdownMenuItem(text={Text("当前范围")},onClick={expanded=false;currentScope=true},modifier=Modifier.testTag("visualCurrentScope"))
                                        }
                                    }
                                } else Text(scopeLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                    item(span={GridItemSpan(maxLineSpan)}) {
                        Row(Modifier.fillMaxWidth().heightIn(min=34.dp).padding(horizontal=3.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("${page.hits.size} 张",style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
                            if(loading) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
                            else if(stats.ready<stats.total) {
                                Text("已准备 ${stats.ready}/${stats.total}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("visualCoverage"))
                                TextButton(onClick={preparing=true},modifier=Modifier.testTag("prepareFromSimilar")) { Text("补全信息") }
                            }
                        }
                    }
                    if(failure.isNotBlank()) item(span={GridItemSpan(maxLineSpan)}) { Text(failure,color=MaterialTheme.colorScheme.error) }
                    if(!loading && failure.isBlank() && page.hits.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
                        Text(if(stats.ready<=1) "先补全相近图片信息。" else "没有找到合适的相近图片。",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=20.dp))
                    }
                    items(page.hits,key={it.photo.id}) { hit ->
                        PhotoCard(hit,selecting=false,selected=false,onClick={inspected=hit},showSnippet=false)
                    }
                }
            }
        }
    }
    inspected?.let { hit ->PhotoInspector(vm,hit.photo.id,onDismiss={inspected=null},onSimilar={next(it)},match=hit) }
    if(preparing) PreparationSheet(vm,{preparing=false},options=options)
}
