package dev.localphotos.shitu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localphotos.shitu.core.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TechnicalDetails(vm:PhotoViewModel,data:InspectorData,onBack:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d=data.detail
    val p=d.photo
    val f=data.features
    val ocr=remember(d.debug) { runCatching { JSONObject(d.debug) }.getOrDefault(JSONObject()) }
    val visual=f.optJSONObject("visual_features")
    val description=f.optJSONObject("description_features")
    val passages=f.optJSONArray("passages")?:JSONArray()
    var tab by remember(p.id) { mutableStateOf("概览") }
    var more by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val tabs=listOf("概览","OCR","特征","匹配")

    Column(Modifier.fillMaxSize()) {
        PageHeader("技术详情",onBack,tag="closeTechnical") {
            Box {
                IconButton(onClick={more=true},modifier=Modifier.testTag("technicalMore")) { Icon(UiIcons.More,"更多操作") }
                DropdownMenu(more,onDismissRequest={more=false}) {
                    DropdownMenuItem(text={Text("复制诊断")},onClick={
                        more=false
                        val photo=JSONObject().put("id",p.id).put("name",p.name).put("folder",p.folder).put("uri",p.uri)
                            .put("width",p.width).put("height",p.height).put("bytes",p.size).put("added",p.added).put("taken",p.taken).put("fingerprint",p.fingerprint)
                        val report=JSONObject().put("photo",photo).put("ocr",ocr).put("ocrVersion",d.version).put("ocrMs",d.ocrMs)
                            .put("ocrText",p.text).put("searchVariants",d.variants).put("previousText",d.previousText).put("features",f).put("match",data.match)
                        copyText(context,"图片诊断",report.toString(2))
                    },modifier=Modifier.testTag("copyDiagnostics"))
                    if(tab=="OCR") DropdownMenuItem(text={Text("重新识别文字")},onClick={more=false;vm.rerun(listOf(p.id))},
                        enabled=!state.workActive,modifier=Modifier.testTag("rerunOne"))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(9.dp),
            verticalAlignment=Alignment.CenterVertically) {
            PhotoImage(p,Modifier.size(36.dp),size=96)
            Column(Modifier.weight(1f)) {
                Text(p.name,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${p.width} × ${p.height}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SecondaryTabRow(selectedTabIndex=tabs.indexOf(tab),containerColor=MaterialTheme.colorScheme.background) {
            tabs.forEach { title -> Tab(selected=tab==title,onClick={tab=title},text={Text(title)},modifier=Modifier.testTag("detail_$title")) }
        }
        key(tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=10.dp)
                .testTag("technicalContent"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                when(tab) {
                    "概览" -> {
                        QuietHeading("搜索信息")
                        MetricRow("图片文字",stateLabel(p.state))
                        MetricRow("文字含义",if(p.semantic) "已准备 · ${passages.length()} 段" else if(p.state=="empty") "无文字" else "尚未准备")
                        MetricRow("相近图片",featureStatus(visual,p.fingerprint,VisualFeatures.VERSION,1280))
                        MetricRow("画面描述",featureStatus(description,p.fingerprint,DescriptionFeatures.VERSION,DescriptionFeatures.DIMENSIONS))
                        Disclosure("原始文件") {
                            InfoRow("目录",p.folder)
                            MetricRow("格式",p.mime.ifBlank { "未知" })
                            MetricRow("文件大小",fileSize(p.size))
                            MetricRow("存入时间",dateLabel(p.added,"未知"))
                            MetricRow("拍摄时间",dateLabel(p.taken,"未提供"))
                            Disclosure("内部标识") { InfoRow("媒体 ID",p.id.toString());InfoRow("URI",p.uri);InfoRow("内容指纹",p.fingerprint) }
                        }
                        if(p.reviewNote.isNotBlank()) Disclosure("旧版保存的说明") { SelectableValue(p.reviewNote) }
                    }
                    "OCR" -> {
                        val timings=ocr.optJSONObject("timings")
                        val tiles=ocr.optJSONArray("tiles")?:JSONArray()
                        val count=tiles.length()
                        QuietHeading("OCR 总耗时")
                        Text(if(p.state=="done" || p.state=="empty") elapsedLabel(d.ocrMs) else stateLabel(p.state),
                            style=MaterialTheme.typography.headlineMedium)
                        val stats=buildList {
                            if(count>0) add("$count 块")
                            if(ocr.optInt("refinementCount")>0) add("补看 ${ocr.optInt("refinementCount")} 次")
                            if(timings?.has("boxCount")==true) add("${timings.optInt("boxCount")} 个文字框")
                            if(timings?.has("recognitionCalls")==true) add("${timings.optInt("recognitionCalls")} 次识别")
                        }
                        if(stats.isNotEmpty()) Text(stats.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if(timings!=null) {
                            QuietHeading("各步骤耗时")
                            OcrTimingsCard(timings,if(ocr.has("mergeMs")) ocr.optLong("mergeMs") else null)
                        } else Text("这张图暂无分段计时。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Disclosure(if(count==0) "逐块耗时与输入尺寸" else "逐块耗时 · $count 块") {
                            if(count==0) Text("暂无分块记录")
                            for(i in 0 until count) {
                                val t=tiles.optJSONObject(i)?:continue
                                QuietHeading("第 ${i+1} 块 · ${t.optString("reason","识别输入")}")
                                MetricRow("解码",elapsedLabel(t.optLong("decodeMs")))
                                MetricRow("识别流程",elapsedLabel(t.optLong("ocrMs")))
                                MetricRow("输入像素","${t.optInt("decodedWidth")} × ${t.optInt("decodedHeight")}")
                                MetricRow("采样倍率",t.optInt("sampleSize",1).toString())
                                t.optJSONObject("timings")?.let { OcrTimingsCard(it) }
                            }
                        }
                        Disclosure("识别位置",tag="openOcrOverlay") { OcrOverlay(data,ocr) }
                        Disclosure("识别原文与处理参数") {
                            MetricRow("引擎",OcrEngine.engineLabel(d.version))
                            InfoRow("处理版本",d.version)
                            if(p.error.isNotBlank()) InfoRow("错误",p.error)
                            MetricRow("EXIF 方向",ocr.optInt("orientation",1).toString())
                            MetricRow("文字行","${ocr.optInt("rawLineCount")} → ${ocr.optInt("mergedLineCount")}")
                            QuietHeading("识别文字 · ${p.text.length} 字符")
                            SelectableValue(p.text)
                            Disclosure("逐块原始输出与历史") {
                                SelectableValue(ocr.optString("rawText",p.text))
                                if(d.variants.isNotBlank()) { QuietHeading("检索变体");SelectableValue(d.variants) }
                                if(d.previousText.isNotBlank()) { QuietHeading("上次识别结果");SelectableValue(d.previousText) }
                            }
                        }
                    }
                    "特征" -> {
                        QuietHeading("准备耗时")
                        MetricRow("文字含义 · BGE",if(f.has("meaningMs")) elapsedLabel(f.optLong("meaningMs")) else "—")
                        MetricRow("相近图片 · MobileNetV4",visual?.let { elapsedLabel(it.optLong("elapsedMs")) }.orEmpty())
                        val currentDescription=description?.takeIf { it.optString("version")==DescriptionFeatures.VERSION }
                        MetricRow("画面描述 · RN50",currentDescription?.let { elapsedLabel(it.optLong("elapsedMs")) }?:"—")
                        Disclosure("文字含义",tag="detail_文字含义") {
                            MetricRow("模型","BGE-small-zh-v1.5 · INT8")
                            MetricRow("段落数",passages.length().toString())
                            MetricRow("向量维度","512 / 段")
                            MetricRow("最大输入","256 token / 段")
                            InfoRow("索引版本",f.optString("meaningVersion","未记录"))
                            for(i in 0 until passages.length()) {
                                val passage=passages.getJSONObject(i)
                                QuietHeading("段落 ${i+1}")
                                SelectableValue(passage.optString("text").ifBlank { "旧记录未保存分段文字" })
                            }
                        }
                        Disclosure("相近特征",tag="detail_相近特征") {
                            FeatureCard("相近图片特征","MobileNetV4 Conv Small",visual,p.fingerprint,VisualFeatures.VERSION,1280)
                            MetricRow("排序版本",VisualRanking.VERSION)
                            MetricRow("指纹 / 颜色布局","63 位 pHash / 16 × 16 RGB")
                            MetricRow("最多候选","40 张")
                            visual?.let { MetricRow("pHash",it.optString("phash"));MetricRow("布局缓存","${it.optInt("layoutBytes")} B") }
                        }
                        Disclosure("画面特征",tag="detail_画面特征") {
                            FeatureCard("画面描述特征",DescriptionFeatures.MODEL_LABEL,description,p.fingerprint,DescriptionFeatures.VERSION,DescriptionFeatures.DIMENSIONS)
                            MetricRow("图文向量","${DescriptionFeatures.DIMENSIONS} 维")
                            MetricRow("描述长度","最多 52 token")
                        }
                    }
                    else -> {
                        MatchCard(data.match,d.query)
                        data.match.optJSONObject("search")?.let { trace ->
                            Disclosure("本次检索的耗时与资源") { SearchTraceCard(trace) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
internal fun featureStatus(value:JSONObject?,fingerprint:String,version:String,dimensions:Int):String=when {
    value==null ->"尚未准备"
    value.optString("fingerprint")!=fingerprint || value.optString("version")!=version ->"旧特征，待更新"
    value.optString("error").isNotBlank() ->"准备失败 · ${value.optString("error") }"
    value.optInt("bytes")!=dimensions*4 ->"特征不完整"
    else ->"已准备"
}
@Composable private fun OcrTimingsCard(value:JSONObject,mergeMs:Long?=null) {
    val labels=listOf("detectMs" to "检测推理","boxesMs" to "提取文字框","recognizeMs" to "识别推理",
        "decodeTextMs" to "转为文字","decodeImageMs" to "图块解码","inputMs" to "像素转换","loadMs" to "模型准备")
    for((key,label) in labels) if(value.has(key)) MetricRow(label,elapsedLabel(value.optLong(key)))
    if(mergeMs!=null) MetricRow("整理文字",elapsedLabel(mergeMs))
}

@Composable private fun FeatureCard(title:String,model:String,value:JSONObject?,fingerprint:String,version:String,dimensions:Int) {
    SectionCard { Text(title,style=MaterialTheme.typography.titleMedium);InfoRow("模型",model)
        InfoRow("状态",featureStatus(value,fingerprint,version,dimensions))
        if(value!=null) { InfoRow("版本",value.optString("version"));InfoRow("缓存向量","${value.optInt("dimensions")} 维 · ${fileSize(value.optLong("bytes"))}")
            InfoRow("解码尺寸","${value.optInt("width")} × ${value.optInt("height")}");InfoRow("准备耗时","${value.optLong("elapsedMs")} ms")
            Disclosure("缓存校验") { InfoRow("缓存指纹",value.optString("fingerprint"));InfoRow("当前指纹",fingerprint) } }
    }
}
@Composable private fun MatchCard(value:JSONObject,query:String) {
    SectionCard {
        Text("这次为什么找到它",style=MaterialTheme.typography.titleMedium)
        if(value.length()==0) Text("从搜索结果或相近结果打开图片后可查看。",style=MaterialTheme.typography.bodyMedium)
        else {
            val kind=value.optString("kind")
            InfoRow("方式",when(kind) { "visual"->"相近图片";"description"->"画面描述";else->"文字搜索" })
            if(kind=="visual") InfoRow("参照图片 ID",value.optLong("seedId").toString()) else InfoRow("查询",value.optString("query",query))
            fun score(key:String)=if(!value.has(key) || value.isNull(key)) "未计算" else String.format(java.util.Locale.ROOT,"%.4f",value.optDouble(key))
            when(kind) {
                "visual"->{ InfoRow("匹配原因",value.optString("reason"));InfoRow("模型 / 布局","${score("cosine")} / ${score("appearance")}");InfoRow("排序分",score("rank"));InfoRow("指纹差异","${value.optInt("hashDistance")} / 63");InfoRow("扫描候选",value.optInt("scanned").toString()) }
                "description"->{
                    val model=value.optString("modelVersion")
                    InfoRow("画面相似度",score("similarity"))
                    InfoRow("模型",traceModel(model))
                    InfoRow("模型版本",model)
                }
                else->{ InfoRow("匹配原因",value.optString("reason"));InfoRow("文字分",score("lexicalScore"));InfoRow("含义相似度",score("similarity"));if(value.optString("matchedText").isNotBlank()) SelectableValue(value.optString("matchedText")) }
            }
            Disclosure("完整匹配记录") { SelectableValue(value.toString(2),mono=true) }
        }
    }
}
@Composable internal fun InfoRow(label:String,value:String) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value.ifBlank { "未提供" },style=MaterialTheme.typography.bodyMedium) }
    }
}
@Composable internal fun Disclosure(title:String,tag:String="",content:@Composable ()->Unit) {
    var open by remember(title) { mutableStateOf(false) }
    TextButton(onClick={open=!open},modifier=if(tag.isBlank()) Modifier else Modifier.testTag(tag)) { Text(if(open) "收起$title" else title);Spacer(Modifier.width(6.dp));Text(if(open) "−" else "+") }
    if(open) content()
}
@Composable internal fun SelectableValue(value:String,mono:Boolean=false) {
    SelectionContainer { Text(value.take(30000).ifBlank { "暂无内容" },style=MaterialTheme.typography.bodyMedium,fontFamily=if(mono) FontFamily.Monospace else FontFamily.Default) }
    if(value.length>30000) Text("界面显示前 30000 字符。",style=MaterialTheme.typography.bodySmall)
}
@Composable private fun OcrOverlay(data:InspectorData,debug:JSONObject) {
    var overlay by remember { mutableStateOf("无框") }
    SectionCard {
        Text("识别位置",style=MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            PhotoImage(data.detail.photo,Modifier.fillMaxSize(),900,fit=true)
            val source=if(data.detail.version=="legacy") null else when(overlay) { "整理框"->runCatching { JSONArray(data.detail.blocks) }.getOrDefault(JSONArray());"原始框"->debug.optJSONArray("rawLines");"处理块"->debug.optJSONArray("tiles");else->null }
            val w=debug.optInt("uprightWidth",data.detail.photo.width).coerceAtLeast(1);val h=debug.optInt("uprightHeight",data.detail.photo.height).coerceAtLeast(1)
            Canvas(Modifier.fillMaxSize()) {
                val scale=minOf(size.width/w,size.height/h);val x=(size.width-w*scale)/2;val y=(size.height-h*scale)/2
                for(i in 0 until minOf(source?.length()?:0,1500)) {
                    val row=source!!.optJSONObject(i)?:continue;val b=row.optJSONArray("box")?:continue
                    if(b.length()!=4) continue
                    drawRect(if(overlay=="处理块") { if(row.optBoolean("rescue")) Color(0xFF42A5F5) else Color(0xFFFFA726) } else Color(0xFFE53935),Offset(x+b.optDouble(0).toFloat()*scale,y+b.optDouble(1).toFloat()*scale),Size(((b.optDouble(2)-b.optDouble(0)).toFloat()*scale).coerceAtLeast(0f),((b.optDouble(3)-b.optDouble(1)).toFloat()*scale).coerceAtLeast(0f)),style=Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("无框","整理框","原始框","处理块").forEach { mode ->FilterChip(overlay==mode,{overlay=mode},label={Text(mode)},modifier=Modifier.testTag("overlay_$mode")) }
        }
        if(data.detail.version=="legacy") Text("旧记录未保存统一坐标，不叠加识别框。",style=MaterialTheme.typography.bodySmall)
        else if(overlay=="处理块") Text("橙色为基础输入，蓝色为局部补看。",style=MaterialTheme.typography.bodySmall)
    }
}
