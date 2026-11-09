package dev.localphotos.shitu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localphotos.shitu.data.Hit
import dev.localphotos.shitu.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private object Thumbnails {
    val permits=Semaphore(3)
    val cache=object:LruCache<String,Bitmap>(12*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024 }
}
@Composable internal fun PhotoImage(photo:Photo,modifier:Modifier=Modifier,size:Int=300,fit:Boolean=false) {
    val context=LocalContext.current
    val key="${photo.key}:${photo.fingerprint}:$size"
    key(key) {
    val bitmap by produceState<Bitmap?>(Thumbnails.cache.get(key),key) {
        value=withContext(Dispatchers.IO) { Thumbnails.permits.withPermit {
            Thumbnails.cache.get(key)?:runCatching { context.contentResolver.loadThumbnail(Uri.parse(photo.uri),Size(size,size),null) }.getOrNull()?.also { Thumbnails.cache.put(key,it) }
        } }
    }
    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),contentDescription=photo.name,modifier=modifier,contentScale=if(fit) ContentScale.Fit else ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) { Text("图片",fontSize=12.sp) }
    }
}
@Composable internal fun PhotoCard(hit:Hit,selecting:Boolean,selected:Boolean,onClick:()->Unit,onLongClick:(()->Unit)?=null,showSnippet:Boolean=true) {
    Column(Modifier.testTag("photo_${hit.photo.id}").clip(RoundedCornerShape(4.dp)).combinedClickable(onClick=onClick,onLongClick=onLongClick)) {
        Box {
            PhotoImage(hit.photo,Modifier.fillMaxWidth().aspectRatio(1f))
            if(selecting) Checkbox(selected,{onClick()},modifier=Modifier.align(Alignment.TopEnd))
        }
        if(showSnippet && hit.reason.isNotBlank() && hit.snippet.isNotBlank() && hit.snippet!=hit.photo.name) {
            Text(hit.snippet,Modifier.padding(horizontal=4.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
internal fun safeStart(context:Context,intent:Intent) {
    try { context.startActivity(intent) } catch(_:Exception) { Toast.makeText(context,"暂时无法打开，可检查图片权限或可用应用。",Toast.LENGTH_LONG).show() }
}
