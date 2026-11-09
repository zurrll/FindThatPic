package dev.localphotos.shitu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private data class GuideStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val path: String,
    val tip: String
)

@Composable
internal fun GettingStarted(onClose: () -> Unit) {
    val steps = remember {
        listOf(
            GuideStep(UiIcons.Check, "先补全搜索信息",
                "允许读取照片后，先让拾图读出图片里的字，并提取查找相近图片所需的信息。",
                "首页 → 补全搜索信息",
                "可以先处理 100 张。暂停后可继续，已完成的图片会复用。"),
            GuideStep(UiIcons.Folder, "隐藏不想搜的目录",
                "缓存图、下载图混在图库里？把不需要的目录排除，这些图片就不会出现在拾图中。",
                "设置 → 排除目录",
                "排除也会应用到子目录。原图留在手机上，随时可以恢复显示。"),
            GuideStep(UiIcons.Search, "试试这样搜",
                if (BuildConfig.HAS_DESCRIPTION) "记得截图里有「取件码」，就用文字搜索。记得照片里有「窗边的猫」，就用描述找图。"
                else "记得截图里有「取件码」？输入这几个字试试。也可以搜文件名，或用记得的近义词找截图。",
                if (BuildConfig.HAS_DESCRIPTION) "首页 → 文字搜索 / 描述找图" else "首页 → 搜索框",
                "还找不到时，先看对应图片的信息是否已经补全。"),
            GuideStep(UiIcons.Similar, "用一张图找相近",
                "打开一张图片，点「找相近」。在结果里打开另一张图，还可以接着找。",
                "打开图片 → 找相近",
                "想找更多候选，先补全图库里的相近图片信息。")
        )
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]
    fun back() {
        if (index == 0) onClose() else index--
    }
    FullPage(::back) {
        Column(Modifier.fillMaxSize().testTag("gettingStarted")) {
            PageHeader("操作指引", ::back, tag = "guideBack") {
                TextButton(onClick = onClose, modifier = Modifier.testTag("guideSkip")) { Text("跳过") }
            }
            key(index) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("第 ${index + 1} 步，共 ${steps.size} 步",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(step.icon, null, Modifier.padding(14.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(step.title, style = MaterialTheme.typography.headlineMedium)
                    Text(step.description, style = MaterialTheme.typography.bodyLarge)
                    SectionCard {
                        Text(step.path, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(step.tip, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(
                onClick = { if (index == steps.lastIndex) onClose() else index++ },
                modifier = Modifier.fillMaxWidth().padding(24.dp).heightIn(min = 48.dp).testTag("guideNext")
            ) { Text(if (index == steps.lastIndex) "完成" else "下一步") }
        }
    }
}
