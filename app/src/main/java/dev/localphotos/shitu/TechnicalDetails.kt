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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localphotos.shitu.core.*
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun TechnicalDetails(vm:PhotoViewModel,data:InspectorData,onBack:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d=data.detail;val p=d.photo;val f=data.features
    val ocr=remember(d.debug) { runCatching { JSONObject(d.debug) }.getOrDefault(JSONObject()) }
    val visual=f.optJSONObject("visual_features");val description=f.optJSONObject("description_features")
    val passages=f.optJSONArray("passages")?:JSONArray()
    var tab by remember(p.id) { mutableStateOf("概览") };var copied by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val report=remember(data) { JSONObject().put("photo",JSONObject().put("id",p.id).put("name",p.name).put("folder",p.folder).put("uri",p.uri).put("width",p.width).put("height",p.height).put("bytes",p.size).put("added",p.added).put("taken",p.taken).put("fingerprint",p.fingerprint))
        .put("ocr",ocr).put("ocrVersion",d.version).put("ocrMs",d.ocrMs).put("ocrText",p.text).put("searchVariants",d.variants).put("previousText",d.previousText).put("features",f).put("match",data.match).toString(2) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("技术详情",onBack,tag="closeTechnical",action={TextButton(onClick={copyText(context,"图片诊断",report);copied=true},modifier=Modifier.testTag("copyDiagnostics")) { Text(if(copied) "已复制" else "复制诊断") }})
        val tabs=listOf("概览","OCR","文字含义","相近特征")+if(BuildConfig.HAS_DESCRIPTION) listOf("画面特征") else emptyList()
        ScrollableTabRow(selectedTabIndex=tabs.indexOf(tab),edgePadding=16.dp,containerColor=MaterialTheme.colorScheme.background) {
            tabs.forEach { title ->Tab(selected=tab==title,onClick={tab=title},text={Text(title)},modifier=Modifier.testTag("detail_$title")) }
        }
        key(tab) { Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("technicalContent"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            when(tab) {
                "概览"->{
                    SectionCard {
                        Text("搜索信息",style=MaterialTheme.typography.titleMedium)
                        InfoRow("图片文字",stateLabel(p.state))
                        InfoRow("文字含义",if(p.semantic) "已准备 · ${passages.length()} 段" else if(p.state=="empty") "无文字，无需计算" else "尚未准备")
                        InfoRow("相近图片",featureStatus(visual,p.fingerprint,VisualFeatures.VERSION,1280))
                        if(BuildConfig.HAS_DESCRIPTION) InfoRow("画面描述",featureStatus(description,p.fingerprint,DescriptionFeatures.VERSION,512))
                    }
                    MatchCard(data.match,d.query)
                    data.match.optJSONObject("search")?.let { SearchTraceCard(it) }
                    SectionCard {
                        Text("原始文件",style=MaterialTheme.typography.titleMedium)
                        InfoRow("名称",p.name);InfoRow("目录",p.folder);InfoRow("格式",p.mime.ifBlank { "未知" })
                        InfoRow("尺寸","${p.width} × ${p.height}");InfoRow("文件大小",fileSize(p.size))
                        InfoRow("存入时间",dateLabel(p.added,"未知"));InfoRow("拍摄时间",dateLabel(p.taken,"未提供"))
                        Disclosure("内部标识") { InfoRow("媒体 ID",p.id.toString());InfoRow("URI",p.uri);InfoRow("内容指纹",p.fingerprint) }
                    }
                    if(p.reviewNote.isNotBlank()) SectionCard { Text("旧版保存的说明",style=MaterialTheme.typography.titleMedium);SelectableValue(p.reviewNote) }
                }
                "OCR"->{
                    OcrOverlay(data,ocr)
                    SectionCard {
                        Text("识别记录",style=MaterialTheme.typography.titleMedium)
                        InfoRow("状态",stateLabel(p.state));InfoRow("引擎",OcrEngine.engineLabel(d.version))
                        InfoRow("处理版本",d.version);InfoRow("总耗时","${d.ocrMs} ms")
                        InfoRow("处理输入","${ocr.optJSONArray("tiles")?.length()?:0} 次 · 基础 ${ocr.optInt("baseCount")} / 补看 ${ocr.optInt("refinementCount")}")
                        InfoRow("文字行","${ocr.optInt("rawLineCount")} → ${ocr.optInt("mergedLineCount")}")
                        if(p.error.isNotBlank()) InfoRow("错误",p.error)
                        Disclosure("处理参数") { InfoRow("策略",ocr.optString("strategy","旧记录未提供"));InfoRow("EXIF 方向",ocr.optInt("orientation",1).toString());InfoRow("识别坐标尺寸","${ocr.optInt("uprightWidth",p.width)} × ${ocr.optInt("uprightHeight",p.height)}") }
                    }
                    SectionCard { Text("识别文字 · ${p.text.length} 字符",style=MaterialTheme.typography.titleMedium);SelectableValue(p.text.ifBlank { "暂无文字" }) }
                    Disclosure("逐块耗时与输入尺寸") {
                        val tiles=ocr.optJSONArray("tiles")?:JSONArray()
                        for(i in 0 until tiles.length()) {
                            val t=tiles.getJSONObject(i)
                            SectionCard { Text("输入 ${i+1} · ${t.optString("reason","识别输入")}",style=MaterialTheme.typography.titleSmall)
                                InfoRow("解码 / 识别","${t.optLong("decodeMs")} / ${t.optLong("ocrMs")} ms")
                                InfoRow("输入像素","${t.optInt("decodedWidth")} × ${t.optInt("decodedHeight")}");InfoRow("采样倍率",t.optInt("sampleSize",1).toString()) }
                        }
                    }
                    Disclosure("原始输出与历史") {
                        SectionCard { Text("逐块原始文字");SelectableValue(ocr.optString("rawText",p.text)) }
                        if(d.variants.isNotBlank()) SectionCard { Text("检索变体");Text("跨行候选，不代表原文。",style=MaterialTheme.typography.bodySmall);SelectableValue(d.variants) }
                        if(d.previousText.isNotBlank()) SectionCard { Text("上次识别结果");SelectableValue(d.previousText) }
                    }
                    OutlinedButton(onClick={vm.rerun(listOf(p.id))},enabled=!state.workActive,modifier=Modifier.testTag("rerunOne")) { Text("重新识别这张图片的文字") }
                }
                "文字含义"->{
                    SectionCard { Text("文字向量",style=MaterialTheme.typography.titleMedium)
                        InfoRow("模型","BGE-small-zh-v1.5 · INT8");InfoRow("状态",if(p.semantic) "已准备" else if(p.state=="empty") "无文字，无需计算" else "尚未准备")
                        InfoRow("段落数",passages.length().toString());InfoRow("向量维度","512 / 段");InfoRow("最大输入","256 token / 段")
                        InfoRow("索引版本",f.optString("meaningVersion","未记录")) }
                    for(i in 0 until passages.length()) {
                        val passage=passages.getJSONObject(i)
                        SectionCard { Text("段落 ${i+1}",style=MaterialTheme.typography.titleSmall);SelectableValue(passage.optString("text").ifBlank { "旧记录未保存分段文字" });InfoRow("向量维度",passage.optInt("dimensions").toString()) }
                    }
                }
                "相近特征"->{
                    FeatureCard("相近图片特征","MobileNetV4 Conv Small",visual,p.fingerprint,VisualFeatures.VERSION,1280)
                    SectionCard { Text("比较方式",style=MaterialTheme.typography.titleMedium)
                        InfoRow("模型输入","整图等比放入 224 × 224");InfoRow("视觉向量","1280 维 · L2 归一化")
                        InfoRow("指纹","63 位 pHash");InfoRow("颜色布局","16 × 16 RGB")
                        InfoRow("排序版本",VisualRanking.VERSION);InfoRow("普通排序","模型相似度 85% ＋ 颜色布局 15%")
                        InfoRow("最多候选","40 张");InfoRow("时间与 OCR","不参与相近分数")
                        visual?.let { InfoRow("pHash",it.optString("phash"));InfoRow("布局缓存","${it.optInt("layoutBytes")} B") } }
                }
                else->{
                    FeatureCard("画面描述特征","Chinese-CLIP B/16 · INT8",description,p.fingerprint,DescriptionFeatures.VERSION,512)
                    SectionCard { Text("图片与查询处理",style=MaterialTheme.typography.titleMedium)
                        InfoRow("模型输入","整图等比放入 224 × 224，均值色留边")
                        InfoRow("图像采样","最长边不超过 1536，双三次缩放")
                        InfoRow("向量","图像与文字均为 512 维单位向量")
                        InfoRow("描述长度","最多 52 token，超出截断")
                        InfoRow("排序","同模型的图文向量点积，最多 100 项")
                        Text("不生成图片标题，不依赖 OCR 文字。相似度是排序分数，不是相关概率。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(12.dp))
        } }
    }
}
internal fun featureStatus(value:JSONObject?,fingerprint:String,version:String,dimensions:Int):String=when {
    value==null ->"尚未准备"
    value.optString("fingerprint")!=fingerprint || value.optString("version")!=version ->"旧特征，待更新"
    value.optString("error").isNotBlank() ->"准备失败 · ${value.optString("error") }"
    value.optInt("bytes")!=dimensions*4 ->"特征不完整"
    else ->"已准备"
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
                "description"->{ InfoRow("画面相似度",score("similarity"));InfoRow("模型版本",value.optString("modelVersion")) }
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
    if(value.length>30000) Text("界面显示前 30000 字符，完整内容可批量打包。",style=MaterialTheme.typography.bodySmall)
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
