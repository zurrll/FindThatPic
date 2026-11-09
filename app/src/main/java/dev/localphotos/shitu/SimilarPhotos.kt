package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
    var selecting by mutableStateOf(false);var selected by mutableStateOf(emptySet<Long>())
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
    fun select(id:Long) { page.selected=if(id in page.selected) page.selected-id else page.selected+id }
    FullPage({back()}) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("相近图片",{back()},tag=if(pages.size>1) "similarBack" else "closeSimilar",action={
                if(pages.size>1) TextButton(onClick=onDismiss,modifier=Modifier.testTag("closeSimilar")) { Text("图库") }
                TextButton(onClick={page.selecting=!page.selecting;if(!page.selecting) page.selected=emptySet()},modifier=Modifier.testTag("similarSelect")) { Text(if(page.selecting) "完成" else "多选") }
            })
            key(page.id) { LazyVerticalGrid(columns=GridCells.Adaptive(108.dp),state=page.scroll,modifier=Modifier.weight(1f).testTag("similarResults"),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item(span={GridItemSpan(maxLineSpan)}) { SectionCard {
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        page.seed?.let { PhotoImage(it,Modifier.size(64.dp),fit=true) }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text("以这张图继续查找",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(page.seed?.name?:"正在读取…",maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("similarSeed"))
                        }
                    }
                } }
                if(available.hasMetadataFilters) item(span={GridItemSpan(maxLineSpan)}) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!currentScope,{currentScope=false;page.selected=emptySet()},label={Text("全部图库")},modifier=Modifier.testTag("visualAllScope"))
                        FilterChip(currentScope,{currentScope=true;page.selected=emptySet()},label={Text("当前范围")},modifier=Modifier.testTag("visualCurrentScope"))
                    }
                }
                item(span={GridItemSpan(maxLineSpan)}) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("${page.hits.size} 张相近候选",style=MaterialTheme.typography.titleSmall)
                            Text("已准备 ${stats.ready} / ${stats.total} 张",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("visualCoverage"))
                        }
                        if(loading) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                        if(stats.ready<stats.total) TextButton(onClick={preparing=true},modifier=Modifier.testTag("prepareFromSimilar")) { Text("补全信息") }
                    }
                }
                if(failure.isNotBlank()) item(span={GridItemSpan(maxLineSpan)}) { Text(failure,color=MaterialTheme.colorScheme.error) }
                if(!loading && page.hits.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(if(stats.ready<=1) "先补全相近图片信息。" else "当前已准备的图片中，没有合适的相近候选。",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=28.dp)) }
                items(page.hits,key={it.photo.id}) { hit ->PhotoCard(hit,page.selecting,hit.photo.id in page.selected,onClick={if(page.selecting) select(hit.photo.id) else inspected=hit},onLongClick={page.selecting=true;select(hit.photo.id)}) }
            } }
            if(page.selecting) Surface(tonalElevation=4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("已选 ${page.selected.size} 张",modifier=Modifier.weight(1f));TextButton(onClick={page.selected=page.hits.map { it.photo.id }.toSet()},modifier=Modifier.testTag("similarSelectAll")) { Text("选择全部候选") } }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={vm.mark(page.selected.toList(),true)},enabled=page.selected.isNotEmpty(),modifier=Modifier.weight(1f).testTag("similarMark")) { Text("标记") }

                    }
                }
            }
        }
    }
    inspected?.let { hit ->PhotoInspector(vm,hit.photo.id,onDismiss={inspected=null},onSimilar={next(it)},match=hit) }
    if(preparing) PreparationSheet(vm,{preparing=false},options=options)
}
