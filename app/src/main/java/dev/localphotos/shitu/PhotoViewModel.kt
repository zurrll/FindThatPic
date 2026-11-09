package dev.localphotos.shitu

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.localphotos.shitu.core.*
import dev.localphotos.shitu.work.SearchTrace
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import org.json.JSONObject
import dev.localphotos.shitu.data.Hit
import dev.localphotos.shitu.data.LibraryStats
import dev.localphotos.shitu.data.PhotoFilter
import dev.localphotos.shitu.data.SearchOptions
import dev.localphotos.shitu.data.ClearScope
import dev.localphotos.shitu.data.PhotoLimits
import dev.localphotos.shitu.data.SearchMode
import dev.localphotos.shitu.data.PreparationKinds
import dev.localphotos.shitu.data.PreparationStats
import kotlinx.coroutines.flow.combine
import dev.localphotos.shitu.work.IndexCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(val access:String="尚未授权照片",val allowed:Boolean=false,val discovering:Boolean=false,
    val discovered:Int=0,val stats:LibraryStats=LibraryStats(),val hits:List<Hit> = emptyList(),
    val query:String="",val options:SearchOptions=SearchOptions(),
    val limit:Int=100,val searching:Boolean=false,val workActive:Boolean=false,val message:String="",val browseLimit:Int=60,
    val filter:PhotoFilter=PhotoFilter.ALL,val selecting:Boolean=false,val selected:Set<Long> = emptySet(),
    val mode:SearchMode=SearchMode.TEXT,val prepared:PreparationStats=PreparationStats(),val kinds:PreparationKinds=PreparationKinds(),
    val excluded:Set<String> = emptySet(),val changingRecords:Boolean=false,val selectingBatch:Boolean=false) {
    val folder get()=options.folders.singleOrNull()
}

@OptIn(FlowPreview::class)
class PhotoViewModel(application:Application):AndroidViewModel(application) {
    val app=application as PhotoApp
    private val prefs=app.getSharedPreferences("ui",0)
    private val mutable=MutableStateFlow(UiState(allowed=app.library.hasAccess(),access=app.library.accessDescription(),limit=prefs.getInt("limit",100),mode=if(BuildConfig.HAS_DESCRIPTION && prefs.getString("search_mode","TEXT")==SearchMode.DESCRIPTION.name) SearchMode.DESCRIPTION else SearchMode.TEXT,
        kinds=PreparationKinds(prefs.getBoolean("prepare_text",true),prefs.getBoolean("prepare_similar",true),BuildConfig.HAS_DESCRIPTION && prefs.getBoolean("prepare_description",true))))
    val state=mutable.asStateFlow()
    val progress=app.progress.sample(160).stateIn(viewModelScope,SharingStarted.Eagerly,app.progress.value)
    private val queries=MutableStateFlow(0)
    private var discovery:Job?=null
    private var observedChange:Job?=null
    private var refreshAgain=false
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean) {
            observedChange?.cancel()
            observedChange=viewModelScope.launch { delay(800);refreshLibrary(force=android.os.Build.VERSION.SDK_INT<30,quiet=true) }
        }
    }
    init {
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer)
        viewModelScope.launch { queries.debounce(220).collectLatest { loadResults() } }
        viewModelScope.launch {
            app.preparation.restore(adoptLegacy=true)
            val manager=WorkManager.getInstance(app)
            combine(manager.getWorkInfosForUniqueWorkFlow(IndexCoordinator.WORK),manager.getWorkInfosForUniqueWorkFlow(dev.localphotos.shitu.work.VisualIndex.WORK),manager.getWorkInfosForUniqueWorkFlow(dev.localphotos.shitu.work.Preparation.WORK)) { a,b,c -> a+b+c }.collect { infos ->
                val active=infos.any { it.state==WorkInfo.State.RUNNING || it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED }
                mutable.value=mutable.value.copy(workActive=active)
                if(!active) { app.preparation.restore();app.indexer.restorePausedBatch();refreshStats();queries.value++ }
            }
        }
        viewModelScope.launch {
            while(true) {
                delay(1200)
                if(mutable.value.workActive) {
                    refreshStats()
                    if(mutable.value.query.isBlank()) queries.value++
                }
            }
        }
    }
    fun onResume()=refreshLibrary(force=false,quiet=true)
    fun refreshLibrary(force:Boolean=true,quiet:Boolean=false) {
        if(discovery?.isActive==true) { refreshAgain=true;return }
        discovery=viewModelScope.launch {
            val allowed=app.library.hasAccess()
            val accessChanged=allowed!=mutable.value.allowed
            mutable.update { it.copy(allowed=allowed,access=app.library.accessDescription()) }
            try {
                val needed=withContext(Dispatchers.IO) { force || app.library.needsDiscovery() }
                if(needed) {
                    // Routine resume/observer refreshes keep cached results usable.
                    val showProgress=!quiet
                    if(showProgress && allowed) mutable.update { it.copy(discovering=true,discovered=0,message="") }
                    withContext(Dispatchers.IO) { app.library.discover { count -> mutable.update { it.copy(discovered=count) } } }
                }
                refreshStats()
                if(needed || accessChanged || mutable.value.hits.isEmpty()) queries.value++
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { mutable.update { it.copy(message="图库暂时无法读取，请检查照片权限并刷新。") } }
            finally {
                mutable.update { it.copy(discovering=false) }
                discovery=null
                if(refreshAgain) { refreshAgain=false;refreshLibrary(force=false,quiet=true) }
            }
        }
    }
    private suspend fun refreshStats() {
        val options=mutable.value.options
        val (stats,excluded,prepared)=withContext(Dispatchers.IO) { Triple(app.database.stats(),app.database.excludedFolders(),app.database.preparationStats(options)) }
        mutable.update { it.copy(stats=stats,excluded=excluded,prepared=if(it.options==options) prepared else it.prepared) }
    }
    private fun requestSearch(change:(UiState)->UiState) {
        mutable.update { change(it).copy(hits=emptyList(),searching=it.allowed,message="") }
        queries.value++
    }
    fun query(value:String)=requestSearch { it.copy(query=value) }
    fun searchNow() { queries.value++ }
    fun mode(value:SearchMode) {
        if(value==SearchMode.DESCRIPTION && !BuildConfig.HAS_DESCRIPTION) return
        prefs.edit().putString("search_mode",value.name).apply()
        requestSearch { it.copy(mode=value) }
    }
    fun kinds(value:PreparationKinds) {
        mutable.update { it.copy(kinds=value) };prefs.edit().putBoolean("prepare_text",value.text).putBoolean("prepare_similar",value.similar).putBoolean("prepare_description",value.description).apply()
    }
    fun prepare(options:SearchOptions=mutable.value.options,retry:Boolean=false,ids:List<Long>?=null) {
        if(mutable.value.workActive || mutable.value.discovering || mutable.value.changingRecords) return
        val snapshot=mutable.value;mutable.update { it.copy(workActive=true,message="") }
        viewModelScope.launch {
            try { app.preparation.start(snapshot.limit,options,snapshot.kinds,retry,ids) }
            catch(e:Exception) { mutable.update { it.copy(workActive=false,message=e.message?:"无法开始准备") } }
        }
    }
    fun continuePreparation() {
        if(mutable.value.workActive) return
        mutable.update { it.copy(workActive=true,message="") }
        viewModelScope.launch { try {
            if(app.preparation.readJob()?.optBoolean("finished")==false) app.preparation.resume()
            else if(runCatching { org.json.JSONObject(java.io.File(app.noBackupFilesDir,"jobs/current.json").readText()).optBoolean("finished") }.getOrDefault(true)==false) app.indexer.resume()
            else app.visualIndexer.resume()
        } catch(e:Exception) { mutable.update { it.copy(workActive=false,message=e.message?:"无法继续") } } }
    }
    fun more() { mutable.update { it.copy(browseLimit=it.browseLimit+60,searching=it.allowed) };queries.value++ }
    fun options(value:SearchOptions) { requestSearch { it.copy(options=value,browseLimit=60,selected=emptySet()) };viewModelScope.launch { refreshStats() } }
    fun resetFilters() { requestSearch { it.copy(options=SearchOptions(),filter=PhotoFilter.ALL,browseLimit=60,selected=emptySet()) };viewModelScope.launch { refreshStats() } }
    fun filter(value:PhotoFilter)=requestSearch { it.copy(filter=value,browseLimit=60,selected=emptySet()) }
    fun selecting(value:Boolean) { mutable.value=mutable.value.copy(selecting=value,selected=if(value) mutable.value.selected else emptySet()) }
    fun select(id:Long) {
        val selected=mutable.value.selected
        mutable.value=mutable.value.copy(selected=if(id in selected) selected-id else selected+id)
    }
    fun selectVisible() { mutable.value=mutable.value.copy(selected=(mutable.value.selected+mutable.value.hits.map { it.photo.id }).toSet()) }
    fun selectBatch()=viewModelScope.launch {
        val snapshot=mutable.value
        if(snapshot.selectingBatch) return@launch
        mutable.update { it.copy(selectingBatch=true,message="") }
        try {
            val ids=withContext(Dispatchers.IO) {
                if(snapshot.query.isNotBlank()) snapshot.hits.map { it.photo.id }.filter { it !in snapshot.selected }.take(PhotoLimits.SAMPLE_BATCH)
                else app.database.sampleIds(PhotoLimits.SAMPLE_BATCH,snapshot.selected,snapshot.filter,snapshot.options)
            }
            mutable.update { current ->
                if(!current.selecting || current.options!=snapshot.options || current.filter!=snapshot.filter || current.query!=snapshot.query || current.excluded!=snapshot.excluded) current
                else current.copy(selected=current.selected+ids,message=if(ids.isEmpty()) "当前范围没有未选图片" else if(snapshot.query.isNotBlank()) "仅选择本次搜索结果；清空搜索词可选更多" else "")
            }
        } catch(_:Exception) { mutable.update { it.copy(message="选图失败，请刷新后重试") } }
        finally { mutable.update { it.copy(selectingBatch=false) } }
    }
    fun changed() { viewModelScope.launch { refreshStats();queries.value++ } }
    fun rerun(ids:List<Long>) {
        if(mutable.value.workActive || mutable.value.discovering || mutable.value.changingRecords) return
        mutable.value=mutable.value.copy(workActive=true,message="")
        viewModelScope.launch {
            try { app.indexer.rerun(ids) }
            catch(e:Exception) { mutable.value=mutable.value.copy(workActive=false,message=e.message?:"重跑失败") }
        }
    }
    fun limit(value:Int) { mutable.value=mutable.value.copy(limit=value);prefs.edit().putInt("limit",value).apply() }
    fun mark(ids:List<Long>,value:Boolean)=viewModelScope.launch {
        withContext(Dispatchers.IO) { app.database.mark(ids,value) }
        val selected=ids.toSet()
        mutable.update { it.copy(hits=it.hits.map { hit ->if(hit.photo.id in selected) hit.copy(photo=hit.photo.copy(marked=value)) else hit }) }
        refreshStats()
        if(mutable.value.options.markedOnly) queries.value++
    }
    fun exclude(path:String,value:Boolean)=viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { app.database.setFolderExcluded(path,value);app.library.invalidateScan() }
            mutable.update { it.copy(selected=emptySet()) }
            refreshStats();queries.value++;refreshLibrary(quiet=true)
        } catch(e:Exception) { mutable.update { it.copy(message=e.message?:"无法更新排除目录") } }
    }
    fun clearRecords(target:ClearScope)=viewModelScope.launch {
        mutable.update { it.copy(changingRecords=true) }
        try {
            val count=app.indexer.clearRecognition(target)
            mutable.update { it.copy(message="已清除 $count 张的识别记录",selected=emptySet()) }
            refreshStats();queries.value++
        } catch(_:Exception) { mutable.update { it.copy(message="清除失败，请重试。") } }
        finally { mutable.update { it.copy(changingRecords=false) } }
    }
    fun pause() { app.preparation.pause();app.indexer.pause();app.visualIndexer.pause() }
    private suspend fun loadResults() {
        val s=mutable.value
        if(!s.allowed) { mutable.update { it.copy(hits=emptyList(),searching=false) };return }
        mutable.update { it.copy(searching=true) }
        val request=queries.value
        fun current(it:UiState)=queries.value==request && it.query==s.query && it.options==s.options && it.filter==s.filter && it.browseLimit==s.browseLimit && it.allowed==s.allowed && it.mode==s.mode
        val querying=s.query.isNotBlank();if(querying) app.userQueries.incrementAndGet()
        val model=if(s.mode==SearchMode.DESCRIPTION) DescriptionFeatures.VERSION else "bge-small-zh-v1.5:int8"
        val trace=if(querying) SearchTrace(app,s.mode.name,model) else null
        trace?.put("preparation_active",s.workActive)
        val sampler=if(trace!=null) viewModelScope.launch(Dispatchers.IO) { while(isActive) { trace.sample();delay(1000) } } else null
        var status="success";var result:List<Hit>?=null
        fun unavailable(message:String) { status="degraded";trace?.put("fallback",message);mutable.update { if(current(it)) it.copy(message=message) else it } }
        val onCoarse:(List<Hit>)->Unit={ rows ->mutable.update { if(current(it)) it.copy(hits=rows) else it } }
        try {
            result=withContext(Dispatchers.IO) {
                if(BuildConfig.HAS_DESCRIPTION && s.mode==SearchMode.DESCRIPTION && querying) {
                    if(app.database.preparationStats(s.options).description==0) {
                        unavailable("先补全画面信息")
                        emptyList()
                    } else app.descriptionSearch.find(s.query,s.options,s.filter,trace)
                } else app.search.search(s.query,null,0,true,s.browseLimit,s.filter,s.options,
                    onKeywords=onCoarse,onSemanticUnavailable={unavailable("相关搜索暂不可用，已显示文字结果")},trace=trace)
            }
        } catch(e:CancellationException) { status="cancelled";throw e }
        catch(e:Exception) { status="failed";trace?.put("error_type",e.javaClass.simpleName);mutable.update { if(current(it)) it.copy(message="搜索暂不可用，请重试") else it } }
        finally {
            sampler?.cancel()
            try {
                withContext(NonCancellable+Dispatchers.IO) {
                    sampler?.join()
                    if(trace!=null) runCatching { trace.finish(status);app.metrics.record(trace) }
                }
            } finally {
                // Returning from a non-cancellable IO block to a cancelled Main job can throw.
                // Always release the preparation guard, even on that dispatcher boundary.
                if(querying) app.userQueries.decrementAndGet()
                val diagnostic=trace?.json()
                val completed=result?.map { hit -> if(diagnostic==null) hit else hit.copy(matchDebug=runCatching { JSONObject(hit.matchDebug).put("search",diagnostic).toString() }.getOrDefault(hit.matchDebug)) }
                mutable.update { if(current(it)) it.copy(hits=completed?:it.hits,searching=false) else it }
            }
        }
    }
    override fun onCleared() { app.contentResolver.unregisterContentObserver(observer);super.onCleared() }
}
