package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

internal fun traceModel(value:String)=when {
    value.startsWith("bge")->"BGE"
    value.startsWith("cnclip-rn50")->"Chinese-CLIP RN50"
    else->"Chinese-CLIP"
}
internal fun traceStatus(value:String)=when(value) { "running"->"进行中";"success"->"完成";"cancelled"->"已取消";"degraded"->"已降级";"failed"->"失败";else->value }
internal fun elapsedLabel(ms:Long)=if(ms<1000) "$ms ms" else String.format(Locale.ROOT,"%.2f s",ms/1000.0)
@Composable internal fun SearchTraceCard(data:JSONObject) {
    val stages=data.optJSONObject("stages_ms")?:JSONObject();val before=data.optJSONObject("before")?:JSONObject();val after=data.optJSONObject("after")?:JSONObject()
    SectionCard {
        Text("本次检索",style=MaterialTheme.typography.titleMedium)
        MetricRow("方案",traceModel(data.optString("model")))
        MetricRow("状态",traceStatus(data.optString("status")));MetricRow("总耗时",elapsedLabel(data.optLong("elapsed_ms")))
        for((key,label) in listOf("model_wait" to "模型切换与等待","model_load" to "检索模型加载","query_encode" to "搜索词编码","lexical" to "文字匹配","recall" to "候选召回"))
            if(stages.has(key)) MetricRow(label,elapsedLabel(stages.optLong(key)))
        if(data.has("query_cache")) MetricRow("搜索词向量",if(data.optBoolean("query_cache")) "复用缓存" else "本次计算")
        if(data.has("model_was_loaded")) MetricRow("检索模型",if(data.optBoolean("model_was_loaded")) "已加载" else "本次加载")
        if(data.has("scanned_vectors")) MetricRow(if(data.optString("mode")=="TEXT") "扫描文字向量" else "扫描图片向量",data.optInt("scanned_vectors").toString())
        if(data.has("coarse_candidates")) MetricRow("粗召回候选",data.optInt("coarse_candidates").toString())
        if(data.has("fallback")) MetricRow("提示",data.optString("fallback"))
        MetricRow("采样进程内存峰值",fileSize(data.optLong("sampled_peak_pss_kb")*1024))
        if(after.length()>0) {
            MetricRow("温度状态","${before.optInt("thermal_status")} → ${after.optInt("thermal_status")}（0 正常，3 严重）")
            MetricRow("电池温度","${before.optDouble("battery_temperature_c")} → ${after.optDouble("battery_temperature_c")} ℃")
            MetricRow("电量","${before.optInt("battery_percent")} → ${after.optInt("battery_percent")}%${if(after.optBoolean("charging")) " · 充电中" else ""}")
            MetricRow("进程 CPU 时间",elapsedLabel((after.optLong("process_cpu_ms")-before.optLong("process_cpu_ms")).coerceAtLeast(0)))
        }
        Text("内存每秒采样，可能错过瞬时峰值；电池温度不代表芯片温度，电量变化不等于模型耗电量。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PerformanceCards(data:JSONObject,raw:String) {
    var query by remember { mutableStateOf(false) }
    SecondaryTabRow(selectedTabIndex=if(query) 1 else 0,containerColor=MaterialTheme.colorScheme.background) {
        Tab(selected=!query,onClick={query=false},text={Text("批量准备")},modifier=Modifier.testTag("reportPrepare"))
        Tab(selected=query,onClick={query=true},text={Text("单次查询")},modifier=Modifier.testTag("reportQueries"))
    }
    key(query) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(query) {
                val rows=data.optJSONArray("recent_searches")
                QuietHeading("最近查询")
                if(rows==null || rows.length()==0) Text("还没有查询记录",style=MaterialTheme.typography.bodyMedium)
                else for(i in rows.length()-1 downTo 0) {
                    val trace=rows.optJSONObject(i)?:continue
                    val label=if(trace.optString("mode")=="TEXT") "文字搜索" else "描述画面"
                    MetricRow(label,elapsedLabel(trace.optLong("elapsed_ms")))
                    Disclosure("第 ${rows.length()-i} 条 · 阶段与资源") { SearchTraceCard(trace) }
                }
            } else {
                QuietHeading("每个阶段最近 100 次处理")
                val stages=data.optJSONObject("preparation_latency")?:JSONObject()
                PerformanceLine("阶段","中位","P95",heading=true)
                val stageLabels=listOf("ocr" to "识别文字","meaning" to "文字含义","similar" to "相近特征")+
                    if(BuildConfig.HAS_DESCRIPTION) listOf("description" to "画面描述") else emptyList()
                for((key,label) in stageLabels) {
                    val row=stages.optJSONObject(key)?:JSONObject()
                    fun duration(name:String)=if(row.has(name) && !row.isNull(name)) elapsedLabel(row.optLong(name)) else "—"
                    PerformanceLine(label,duration("p50_ms"),duration("p95_ms"))
                }
                Text("P95 用来看耗时较长的图片。首次加载计入统计。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Disclosure("样本数与最近一次") {
                    for((key,label) in stageLabels) {
                        val row=stages.optJSONObject(key)?:JSONObject()
                        val count=row.optInt("samples")
                        MetricRow(label,if(count==0) "尚未记录" else "$count 次 · ${elapsedLabel(row.optLong("last_ms"))}")
                    }
                }
            }
            if(data.optBoolean("emulator")) Text("模拟器数据",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Disclosure("设备与存储") {
                MetricRow("设备",data.optString("device"))
                MetricRow("图库数量",data.optInt("authorized_photos").toString())
                for((label,key) in listOf("设备内存" to "ram_total_bytes","安装包" to "apk_bytes","模型缓存" to "original_model_cache_bytes",
                    "索引数据库" to "database_bytes","可用存储" to "available_bytes")) MetricRow(label,fileSize(data.optLong(key)))
                MetricRow("进程内存采样峰值",fileSize(data.optLong("peak_process_pss_kb")*1024))
            }
            Disclosure("完整报告") { SelectableValue(raw,mono=true) }
        }
    }
}

@Composable private fun PerformanceLine(label:String,median:String,p95:String,heading:Boolean=false) {
    val style=if(heading) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color=if(heading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(label,Modifier.weight(1.3f),style=style,color=color)
            Text(median,Modifier.weight(1f),style=style,color=color,textAlign=TextAlign.End)
            Text(p95,Modifier.weight(1f),style=style,color=color,textAlign=TextAlign.End)
        }
        HorizontalDivider()
    }
}
