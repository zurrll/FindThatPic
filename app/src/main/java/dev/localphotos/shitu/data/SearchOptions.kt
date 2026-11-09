package dev.localphotos.shitu.data

import java.util.Locale

/** All dates are Unix seconds; the end is exclusive. Empty folder sets include every folder. */
data class SearchOptions(
    val folders: Set<String> = emptySet(),
    val source: SourceKind? = null,
    val dateField: DateField = DateField.ADDED,
    val after: Long = 0,
    val before: Long = 0,
    val undatedOnly: Boolean = false,
    val format: ImageFormat = ImageFormat.ALL,
    val longOnly: Boolean = false,
    val order: ResultOrder = ResultOrder.RELEVANCE,
    val originalOnly: Boolean = false,
    val markedOnly: Boolean = false,
) {
    val hasMetadataFilters get() = folders.isNotEmpty() || source != null || after > 0 || before > 0 || undatedOnly || format != ImageFormat.ALL || longOnly || markedOnly
}
enum class DateField(val label: String, val column: String) { ADDED("存入时间", "added"), TAKEN("拍摄时间", "taken") }
enum class ResultOrder(val label: String) { RELEVANCE("相关度"), NEWEST("时间从新到旧"), OLDEST("时间从旧到新") }
enum class ImageFormat(val label: String, val mime: String) {
    ALL("全部格式", ""), JPEG("JPEG", "image/jpeg"), PNG("PNG", "image/png"), GIF("GIF", "image/gif"), WEBP("WebP", "image/webp"), HEIC("HEIC", "image/heic")
}
/** Directory hints only. They do not identify the app that originally created an image. */
enum class SourceKind(val label: String) {
    CAMERA("相机目录"), SCREENSHOTS("截图目录"), DOWNLOADS("下载目录"), OTHER("其他目录");
    companion object {
        fun fromFolder(folder: String): SourceKind {
            val parts = folder.lowercase(Locale.ROOT).split('/').toSet()
            return when {
                parts.any { it in setOf("screenshots", "screenshot", "截屏", "截图") } -> SCREENSHOTS
                "camera" in parts || "相机" in parts -> CAMERA
                "download" in parts || "downloads" in parts -> DOWNLOADS
                else -> OTHER
            }
        }
    }
}
