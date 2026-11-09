package dev.localphotos.shitu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphotos.shitu.data.Hit
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun stateLabel(value:String)=when(value) { "pending"->"尚未识别";"done"->"已识别到文字";"empty"->"未识别到文字";"failed"->"识别失败";else->value }
internal fun reviewLabel(value:String)=when(value) { "good"->"正常";"missed"->"搜不到";"broken"->"断词 / 漏字";"wrong"->"乱码 / 错字";"other"->"其他问题";else->"未评价" }
internal data class InspectorData(val detail:PhotoDetails,val features:JSONObject,val match:JSONObject)

@Composable internal fun PhotoInspector(vm:PhotoViewModel,id:Long,onDismiss:()->Unit,onSimilar:((Long)->Unit)?=null,match:Hit?=null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val openedMatch=remember(id) { match?:state.hits.firstOrNull { it.photo.id==id } }
    var photo by remember(id) { mutableStateOf(openedMatch?.photo) }
    var textPhoto by remember(id) { mutableStateOf<Photo?>(null) }
    var data by remember(id) { mutableStateOf<InspectorData?>(null) }
    var failure by remember(id) { mutableStateOf("") }
    var technical by remember(id) { mutableStateOf(false) }
    var text by remember(id) { mutableStateOf(false) }
    var more by remember(id) { mutableStateOf(false) }
    var copied by remember(id) { mutableStateOf(false) }
    val context=LocalContext.current
    LaunchedEffect(id) {
        try {
            photo=withContext(Dispatchers.IO) { vm.app.database.get(id,brief=true)?:error("图片已不可访问") }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { failure="图片暂时无法读取" }
    }
    LaunchedEffect(id,openedMatch?.matchDebug) {
        openedMatch?.takeIf { it.matchDebug.isNotBlank() }?.let { hit ->withContext(Dispatchers.IO) {
            val trace=runCatching { JSONObject(hit.matchDebug) }.getOrDefault(JSONObject())
            runCatching { vm.app.database.saveMatch(id,trace.optString("query"),hit.matchDebug) }
        } }
    }
    LaunchedEffect(id,text,technical,state.workActive,state.stats.processed) {
        if(!text && !technical) return@LaunchedEffect
        try {
            if(text && !technical) textPhoto=withContext(Dispatchers.IO) { vm.app.database.get(id) }
            if(technical) {
            data=withContext(Dispatchers.IO) {
                val detail=vm.app.database.details(id)?:error("图片已不可访问")
                InspectorData(detail,vm.app.database.searchDiagnostics(id),runCatching { JSONObject(openedMatch?.matchDebug?.takeIf { it.isNotBlank() }?:detail.matchDebug) }.getOrDefault(JSONObject()))
            }
            }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { failure="图片暂时无法读取" }
    }
    FullPage(onDismiss={if(technical) technical=false else if(text) text=false else onDismiss()},color=if(technical) MaterialTheme.colorScheme.background else Color(0xFF101715)) {
        val loaded=data
        if(technical) {
            if(loaded!=null) TechnicalDetails(vm,loaded,onBack={technical=false})
            else Column(Modifier.fillMaxSize()) {
                PageHeader("技术详情",{technical=false},tag="closeTechnical")
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { if(failure.isBlank()) CircularProgressIndicator() else Text(failure) }
            }
        }
        else MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB2E6D7),onPrimary=Color(0xFF00382E),background=Color(0xFF101715),surface=Color(0xFF101715))) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={if(text) text=false else onDismiss()},modifier=Modifier.testTag("closeInspector")) { Icon(UiIcons.Back,"返回") }
                        if(text) Text("图片文字",style=MaterialTheme.typography.titleMedium)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                        if(photo==null) { if(failure.isBlank()) CircularProgressIndicator() else Text(failure) }
                        else if(text) {
                            val content=textPhoto?.text
                            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                if(content==null) { if(failure.isBlank()) CircularProgressIndicator() else Text(failure) }
                                else {
                                    SelectionContainer { Text(content.take(30000).ifBlank { if(textPhoto?.state=="pending") "这张图片还没有准备文字信息。" else "未识别到文字。" },style=MaterialTheme.typography.bodyLarge) }
                                    if(content.isNotBlank()) OutlinedButton(onClick={copyText(context,"图片文字",content);copied=true},modifier=Modifier.testTag("copyOcr")) { Text(if(copied) "已复制" else "复制全部文字") }
                                }
                            }
                        } else photo?.let { key(it.key,it.fingerprint) { ZoomPhoto(it) } }
                    }
                    if(!text) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.16f))
                        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            val current=photo
                            if(onSimilar!=null) ViewerAction("找相近",UiIcons.Similar,Modifier.weight(1f).testTag("findSimilar"),enabled=current!=null) { onSimilar(id) }
                            ViewerAction("在相册看",UiIcons.External,Modifier.weight(1f).testTag("openInGallery"),enabled=current!=null) {
                                current?.let {
                                    safeStart(context,Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it.uri),it.mime.ifBlank { "image/*" })
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }
                            }
                            ViewerAction("分享",UiIcons.Share,Modifier.weight(1f).testTag("shareImage"),enabled=current!=null) {
                                current?.let { safeStart(context,Intent.createChooser(photoShareIntent(it),null)) }
                            }
                            Box(Modifier.weight(1f)) {
                                ViewerAction("更多",UiIcons.More,Modifier.fillMaxWidth().testTag("viewerMore")) { more=true }
                                DropdownMenu(more,onDismissRequest={more=false}) {
                                    DropdownMenuItem(text={Text("图片文字")},onClick={more=false;text=true},enabled=current!=null,modifier=Modifier.testTag("viewerText"))
                                    DropdownMenuItem(text={Text("技术详情")},onClick={more=false;technical=true},enabled=current!=null,modifier=Modifier.testTag("advancedInspector"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun photoShareIntent(photo:Photo):Intent {
    val uri=Uri.parse(photo.uri)
    return Intent(Intent.ACTION_SEND).apply {
        type=photo.mime.ifBlank { "image/*" }
        putExtra(Intent.EXTRA_STREAM,uri)
        clipData=ClipData.newRawUri("图片",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Composable private fun ViewerAction(label:String,icon:ImageVector,modifier:Modifier,enabled:Boolean=true,onClick:()->Unit) {
    val color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f)
    Column(modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick).heightIn(min=52.dp).padding(vertical=4.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp,Alignment.CenterVertically)) {
        Icon(icon,null,Modifier.size(20.dp),tint=color)
        Text(label,style=MaterialTheme.typography.labelSmall,color=color,maxLines=1)
    }
}

@Composable private fun ZoomPhoto(photo:Photo) {
    val context=LocalContext.current
    var failed by remember(photo.id,photo.fingerprint) { mutableStateOf(false) }
    val previewKey="${photo.key}:${photo.fingerprint}"
    val bitmap by produceState<Bitmap?>(PhotoPreviews.cache.get(previewKey),previewKey) {
        try {
            value=withContext(Dispatchers.IO) { PhotoPreviews.permit.withPermit {
                PhotoPreviews.cache.get(previewKey)?:ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(photo.uri))) { decoder,info,_ ->
                    val w=info.size.width;val h=info.size.height
                    val scale=minOf(1.0,4096.0/maxOf(w,h),sqrt(4_000_000.0/(w.toDouble()*h)))
                    decoder.setTargetSize((w*scale).roundToInt().coerceAtLeast(1),(h*scale).roundToInt().coerceAtLeast(1))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }.also { PhotoPreviews.cache.put(previewKey,it) }
            } }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { failed=true }
    }
    var zoom by remember(photo.id) { mutableFloatStateOf(1f) };var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("photoViewer"),contentAlignment=Alignment.Center) {
        val width=constraints.maxWidth.toFloat();val height=constraints.maxHeight.toFloat()
        fun bounded(value:Offset)=Offset(value.x.coerceIn(-width*(zoom-1)/2,width*(zoom-1)/2),value.y.coerceIn(-height*(zoom-1)/2,height*(zoom-1)/2))
        val gestures=Modifier.pointerInput(photo.id) { detectTapGestures(onDoubleTap={zoom=if(zoom>1f) 1f else 2.5f;offset=Offset.Zero}) }
            .pointerInput(photo.id,width,height) { detectTransformGestures { _,pan,change,_ ->zoom=(zoom*change).coerceIn(1f,6f);offset=if(zoom==1f) Offset.Zero else bounded(offset+pan) } }
        val image=bitmap
        if(image!=null) Image(image.asImageBitmap(),photo.name,modifier=Modifier.fillMaxSize().then(gestures).graphicsLayer { scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y },contentScale=ContentScale.Fit)
        else if(failed) Text("无法显示图片，可点「在相册看」",modifier=Modifier.padding(24.dp))
        else {
            PhotoImage(photo,Modifier.fillMaxSize(),fit=true)
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}
private object PhotoPreviews {
    val permit=Semaphore(1)
    val cache=object:LruCache<String,Bitmap>(20*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount }
}
internal fun copyText(context:Context,label:String,value:String) {
    if(value.length>200_000) { android.widget.Toast.makeText(context,"内容过长，无法复制",android.widget.Toast.LENGTH_SHORT).show();return }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,value))
}
