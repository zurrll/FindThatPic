package dev.localphotos.shitu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private enum class GuideTopic { PREPARE, EXCLUDE, TEXT, SIMILAR, SCENE }

private data class GuideStep(val topic: GuideTopic, val title: String, val scenario: String, val instruction: String)

@Composable
internal fun GettingStarted(onClose: () -> Unit) {
    val steps = remember {
        buildList {
            add(GuideStep(GuideTopic.PREPARE, "先补全搜索信息", "想让图库里的图片能被搜到？",
                "授权照片后，点首页的「补全信息」。可以先处理 100 张。"))
            add(GuideStep(GuideTopic.EXCLUDE, "隐藏目录", "如果有不想被识别的目录，",
                "点「设置 → 排除目录」，勾选要隐藏的目录。"))
            add(GuideStep(GuideTopic.TEXT, "搜索文字", "记得图片里有的文字？图片的文件名？",
                if (BuildConfig.HAS_DESCRIPTION) "在搜索框右侧选「文字」，输入记得的词。近义词也能找哦。"
                else "直接在搜索框输入记得的词。近义词也能找哦。"))
            add(GuideStep(GuideTopic.SIMILAR, "找相近", "手头有一张图片，想找类似的图片？",
                "打开这张图片，点底部的「找相近」。"))
            if (BuildConfig.HAS_DESCRIPTION) {
                add(GuideStep(GuideTopic.SCENE, "描述画面", "记得图片的画面但是没有用来参考的图？",
                    "那就描述出来。点搜索框右侧的下拉菜单，选「描述画面」，试试「一只橘猫」。"))
            }
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]
    fun back() { if (index == 0) onClose() else index-- }

    FullPage(::back) {
        Column(Modifier.fillMaxSize().testTag("gettingStarted")) {
            PageHeader("操作指引", ::back, tag = "guideBack") {
                TextButton(onClick = onClose, modifier = Modifier.testTag("guideSkip")) { Text("跳过") }
            }
            key(index) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1} / ${steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(step.title, style = MaterialTheme.typography.headlineSmall)
                    Text(step.scenario, style = MaterialTheme.typography.bodyLarge)
                    Text(step.instruction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GuideImage(step.topic)
                }
            }
            Button(onClick = { if (index == steps.lastIndex) onClose() else index++ },
                modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp).testTag("guideNext")) {
                Text(if (index == steps.lastIndex) "完成" else "下一步")
            }
        }
    }
}

@Composable
private fun GuideImage(topic: GuideTopic) {
    val scheme = MaterialTheme.colorScheme
    val dark = topic == GuideTopic.SIMILAR
    val shape = RoundedCornerShape(14.dp)
    val ink = if (dark) Color(0xFFF2F4F2) else scheme.onSurface
    Surface(Modifier.fillMaxWidth().testTag("guideIllustration"), shape = shape,
        color = if (dark) Color(0xFF171B19) else scheme.surface,
        contentColor = ink, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (topic) {
                GuideTopic.PREPARE, GuideTopic.TEXT, GuideTopic.SCENE -> GuideHome(topic)
                GuideTopic.EXCLUDE -> {
                    GuideHeader("图库设置", UiIcons.Back)
                    PreviewLabel("补全搜索信息")
                    GuideRing { PreviewLabel("排除目录", UiIcons.Folder) }
                    PreviewLabel("照片访问权限")
                }
                GuideTopic.SIMILAR -> {
                    GuideHeader("", UiIcons.Back)
                    PreviewPhoto(Modifier.fillMaxWidth().height(118.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        GuideRing { PreviewLabel("找相近", UiIcons.Similar, ink) }
                        PreviewLabel("在相册看", color = ink)
                        PreviewLabel("分享", color = ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHome(topic: GuideTopic) {
    GuideHeader("拾图", end = UiIcons.Settings)
    val showMenu = topic == GuideTopic.SCENE
    Box {
        val searchField = @Composable {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.Search, null, Modifier.size(16.dp))
                Text("搜截图里的字", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.HAS_DESCRIPTION) {
                    if (showMenu) GuideRing { PreviewLabel("文字", UiIcons.Down) }
                    else PreviewLabel("文字", UiIcons.Down)
                }
            }
        }
        if (topic == GuideTopic.TEXT) GuideRing(content = searchField) else searchField()
    }
    if (showMenu) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(5.dp)) {
                PreviewLabel("文字搜索")
                GuideRing { PreviewLabel("描述画面", UiIcons.Image) }
            }
        }
    } else {
        PreviewLabel("全部目录", UiIcons.Down)
        if (topic == GuideTopic.PREPARE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("有图片待补全", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GuideRing { PreviewLabel("补全信息", color = MaterialTheme.colorScheme.primary) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { PreviewPhoto(Modifier.weight(1f).height(38.dp), it) }
        }
    }
}

@Composable
private fun GuideHeader(title: String, start: ImageVector? = null, end: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        start?.let { Icon(it, null, Modifier.size(18.dp).padding(end = 3.dp)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        end?.let { Icon(it, null, Modifier.size(18.dp)) }
    }
}

@Composable
private fun GuideRing(content: @Composable () -> Unit) {
    val red = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFF8583) else Color(0xFFD32F2F)
    Box(Modifier.border(2.dp, red, RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 1.dp)) { content() }
}

@Composable
private fun PreviewLabel(label: String, icon: ImageVector? = null, color: Color = LocalContentColor.current) {
    Row(Modifier.padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, Modifier.size(14.dp), tint = color) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PreviewPhoto(modifier: Modifier, variant: Int = 0) {
    val sky = listOf(Color(0xFFB1CDD8), Color(0xFFE5CDB2), Color(0xFFCED4E0))[variant]
    val hill = listOf(Color(0xFF547E6D), Color(0xFF9B805F), Color(0xFF738195))[variant]
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(sky)
        drawCircle(Color(0xFFFFF2D6), radius = size.height * .11f, center = Offset(size.width * .73f, size.height * .27f))
        drawPath(Path().apply {
            moveTo(0f,size.height*.8f);lineTo(size.width*.28f,size.height*.39f)
            lineTo(size.width*.56f,size.height*.72f);lineTo(size.width*.83f,size.height*.49f)
            lineTo(size.width,size.height*.71f);lineTo(size.width,size.height);lineTo(0f,size.height);close()
        }, hill)
    }
}
