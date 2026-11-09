package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal object UiIcons {
    private fun icon(name:String,path:String)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).addPath(
        PathParser().parsePathString(path).toNodes(),stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round).build()
    val Back=icon("Back","M15 5 L8 12 L15 19")
    val Search=icon("Search","M20 20 L15.5 15.5 M17 10 A7 7 0 1 1 3 10 A7 7 0 1 1 17 10")
    val Image=icon("Image","M5 3 L19 3 Q21 3 21 5 L21 19 Q21 21 19 21 L5 21 Q3 21 3 19 L3 5 Q3 3 5 3 M3 17 L9 11 L14 16 L17 13 L21 17 M16 7 L16.01 7")
    val Similar=icon("Similar","M9 3 L19 3 Q21 3 21 5 L21 15 M3 9 Q3 7 5 7 L15 7 Q17 7 17 9 L17 19 Q17 21 15 21 L5 21 Q3 21 3 19 Z M3 17 L8 12 L12 16 L14 14 L17 17")
    val Star=icon("Star","M12 3 L14.8 8.8 L21 9.7 L16.5 14.1 L17.6 20.3 L12 17.4 L6.4 20.3 L7.5 14.1 L3 9.7 L9.2 8.8 Z")
    val Info=icon("Info","M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M12 11 L12 17 M12 7 L12.01 7")
    val Settings=icon("Settings","M4 6 L20 6 M4 12 L20 12 M4 18 L20 18 M8 3 L8 9 M16 9 L16 15 M10 15 L10 21")
    val Text=icon("Text","M4 5 L20 5 M12 5 L12 20 M8 20 L16 20 M4 9 L4 5 M20 9 L20 5")
    val Folder=icon("Folder","M3 7 Q3 5 5 5 L10 5 L12 8 L19 8 Q21 8 21 10 L21 18 Q21 20 19 20 L5 20 Q3 20 3 18 Z")
    val Check=icon("Check","M5 12 L10 17 L20 7")
    val More=icon("More","M5 12 L5.01 12 M12 12 L12.01 12 M19 12 L19.01 12")
    val Down=icon("Down","M6 9 L12 15 L18 9")
    val Close=icon("Close","M6 6 L18 18 M18 6 L6 18")
    val External=icon("External","M14 4 L20 4 L20 10 M20 4 L11 13 M20 13 L20 19 Q20 20 19 20 L5 20 Q4 20 4 19 L4 5 Q4 4 5 4 L11 4")
    val Share=icon("Share","M15 8 A3 3 0 1 0 15 2 A3 3 0 1 0 15 8 M5 15 A3 3 0 1 0 5 9 A3 3 0 1 0 5 15 M15 22 A3 3 0 1 0 15 16 A3 3 0 1 0 15 22 M8 10.5 L12 6.5 M8 13.5 L12 17.5")
    val Filter=icon("Filter","M3 5 L21 5 L14 13 L14 19 L10 21 L10 13 Z")
    val Select=icon("Select","M3 6 L5 8 L8 4 M3 13 L5 15 L8 11 M11 6 L21 6 M11 13 L21 13 M11 20 L21 20")
    val Chevron=icon("Chevron","M9 6 L15 12 L9 18")
}

@Composable internal fun FullPage(onDismiss:()->Unit,color:Color=MaterialTheme.colorScheme.background,content:@Composable ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val window=(LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window,color) {
            // Apply after the dialog's window parameters have reached the decor view.
            withFrameNanos { }
            window?.let {
                val controller=WindowCompat.getInsetsController(it,it.decorView)
                controller.isAppearanceLightStatusBars=color.luminance()>.5f
                controller.isAppearanceLightNavigationBars=color.luminance()>.5f
            }
        }
        Surface(Modifier.fillMaxSize(),color=color) { Box(Modifier.safeDrawingPadding().imePadding()) { content() } }
    }
}
@Composable internal fun PageHeader(title:String,onBack:()->Unit,tag:String="sheetDone",action:(@Composable RowScope.()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(end=16.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack,modifier=Modifier.testTag(tag)) { Icon(UiIcons.Back,"返回") }
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        action?.invoke(this)
    }
}
@Composable internal fun SectionCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable internal fun SettingRow(title:String,subtitle:String="",onClick:()->Unit,modifier:Modifier=Modifier,icon:ImageVector=UiIcons.Settings) {
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),color=Color.Transparent) {
        Row(Modifier.padding(horizontal=20.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title,style=MaterialTheme.typography.bodyLarge)
                if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(UiIcons.Chevron,null,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
internal fun fileSize(bytes:Long):String=when {
    bytes>=1024L*1024*1024 -> String.format(java.util.Locale.ROOT,"%.1f GB",bytes/1073741824.0)
    bytes>=1024*1024 -> String.format(java.util.Locale.ROOT,"%.1f MB",bytes/1048576.0)
    bytes>=1024 -> "${bytes/1024} KB"
    else -> "$bytes B"
}
