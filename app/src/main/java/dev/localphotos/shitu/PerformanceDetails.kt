package dev.localphotos.shitu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.json.JSONObject
import java.util.Locale

internal fun traceModel(value:String)=when { value.startsWith("bge")->"BGE";else->"Chinese-CLIP" }
internal fun traceStatus(value:String)=when(value) { "running"->"进行中";"success"->"完成";"cancelled"->"已取消";"degraded"->"已降级";"failed"->"失败";else->value }
internal fun elapsedLabel(ms:Long)=if(ms<1000) "$ms ms" else String.format(Locale.ROOT,"%.2f s",ms/1000.0)
@Composable internal fun SearchTraceCard(data:JSONObject) {
    val stages=data.optJSONObject("stages_ms")?:JSONObject();val before=data.optJSONObject("before")?:JSONObject();val after=data.optJSONObject("after")?:JSONObject()
    SectionCard {
        Text("本次检索",style=MaterialTheme.typography.titleMedium)
        InfoRow("方案",traceModel(data.optString("model")))
        InfoRow("状态",traceStatus(data.optString("status")));InfoRow("总耗时",elapsedLabel(data.optLong("elapsed_ms")))
        for((key,label) in listOf("model_wait" to "模型切换与等待","model_load" to "检索模型加载","query_encode" to "搜索词编码","lexical" to "文字匹配","recall" to "候选召回"))
            if(stages.has(key)) InfoRow(label,elapsedLabel(stages.optLong(key)))
        if(data.has("query_cache")) InfoRow("搜索词向量",if(data.optBoolean("query_cache")) "复用缓存" else "本次计算")
        if(data.has("model_was_loaded")) InfoRow("检索模型",if(data.optBoolean("model_was_loaded")) "已加载" else "本次加载")
        if(data.has("scanned_vectors")) InfoRow(if(data.optString("mode")=="TEXT") "扫描文字向量" else "扫描图片向量",data.optInt("scanned_vectors").toString())
        if(data.has("coarse_candidates")) InfoRow("粗召回候选",data.optInt("coarse_candidates").toString())
        if(data.has("fallback")) InfoRow("提示",data.optString("fallback"))
        InfoRow("采样进程内存峰值",fileSize(data.optLong("sampled_peak_pss_kb")*1024))
        if(after.length()>0) {
            InfoRow("温度状态","${before.optInt("thermal_status")} → ${after.optInt("thermal_status")}（0 正常，3 严重）")
            InfoRow("电池温度","${before.optDouble("battery_temperature_c")} → ${after.optDouble("battery_temperature_c")} ℃")
            InfoRow("电量","${before.optInt("battery_percent")} → ${after.optInt("battery_percent")}%${if(after.optBoolean("charging")) " · 充电中" else ""}")
            InfoRow("进程 CPU 时间",elapsedLabel((after.optLong("process_cpu_ms")-before.optLong("process_cpu_ms")).coerceAtLeast(0)))
        }
        Text("内存每秒采样，可能错过瞬时峰值；电池温度不代表芯片温度，电量变化不等于模型耗电量。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun PerformanceCards(data:JSONObject) {
    SectionCard {
        Text("设备与存储",style=MaterialTheme.typography.titleMedium)
        InfoRow("设备",data.optString("device","读取中"));InfoRow("图库数量",data.optInt("authorized_photos").toString())
        InfoRow("设备内存",fileSize(data.optLong("ram_total_bytes")))
        InfoRow("安装包",fileSize(data.optLong("apk_bytes")))
        InfoRow("原模型私有缓存",fileSize(data.optLong("original_model_cache_bytes")))
        InfoRow("索引数据库",fileSize(data.optLong("database_bytes")))
        InfoRow("可用存储",fileSize(data.optLong("available_bytes")))
        InfoRow("采样历史内存峰值",fileSize(data.optLong("peak_process_pss_kb")*1024))
        if(data.optBoolean("emulator")) Text("当前是模拟器；耗时和内存不能代表真机。",style=MaterialTheme.typography.bodySmall)
    }
    Disclosure("准备信息耗时") {
        SectionCard {
            val stages=data.optJSONObject("preparation_latency")?:JSONObject()
            for((key,label) in listOf("ocr" to "OCR","meaning" to "文字含义","similar" to "相近特征") + if(BuildConfig.HAS_DESCRIPTION) listOf("description" to "画面描述") else emptyList()) {
                val v=stages.optJSONObject(key)?:JSONObject()
                InfoRow(label,if(v.optInt("samples")==0) "尚未记录" else "${v.optInt("samples")} 次 · 中位 ${elapsedLabel(v.optLong("p50_ms"))} · P95 ${elapsedLabel(v.optLong("p95_ms"))}")
            }
            Text("各阶段最近 100 次实际处理，包含首次加载；跳过已有记录不计入。",style=MaterialTheme.typography.bodySmall)
        }
    }
    val rows=data.optJSONArray("recent_searches")
    if(rows!=null && rows.length()>0) Disclosure("最近检索 · ${rows.length()} 条") {
        for(i in rows.length()-1 downTo 0) {
            val row=rows.getJSONObject(i)
            SectionCard {
                Text("${if(row.optString("mode")=="TEXT") "文字" else "画面"} · ${traceModel(row.optString("model"))}",style=MaterialTheme.typography.titleSmall)
                InfoRow(traceStatus(row.optString("status")),elapsedLabel(row.optLong("elapsed_ms")))
                Disclosure("阶段与资源") { SearchTraceCard(row) }
            }
        }
    }
}
