package dev.localphotos.shitu.data


data class Photo(
    val id: Long = 0, val key: String, val uri: String, val name: String,
    val folder: String, val modified: Long, val size: Long, val width: Int,
    val height: Int, val added: Long, val generation: Long = 0,
    val state: String = "pending", val text: String = "", val error: String = "",
    val semantic: Boolean = false,
    val reviewStatus:String="",val reviewNote:String="",
    val taken:Long=0, val mime:String="", val marked:Boolean=false,
) {
    val fingerprint get() = "$modified:$size:$width:$height:$generation"
}
data class Folder(val path: String, val count: Int)
data class LibraryStats(val total: Int = 0, val done: Int = 0, val empty: Int = 0,
    val failed: Int = 0, val semantic: Int = 0, val folders: List<Folder> = emptyList(),val marked:Int=0) {
    val processed get() = done + empty + failed
}
data class Hit(val photo: Photo, val snippet: String, val reason: String, val score: Double, val matchDebug:String="")
data class SemanticMatch(val photo:Photo,val similarity:Float,val passage:String)
data class IndexProgress(val running: Boolean = false, val done: Int = 0, val target: Int = 0,
    val phase: String = "等待开始", val elapsedMs: Long = 0, val lastMs: Long = 0, val paused: Boolean = false)

enum class PhotoFilter(val label:String) {
    ALL("全部"), PENDING("待识别"), DONE("有文字"), EMPTY("无文字"), FAILED("失败"),
    ISSUES("已标问题"), REVIEWED("已评价"), LEGACY("旧版 OCR"), LONG("长图")
}
data class PhotoDetails(val photo:Photo,val blocks:String,val debug:String,val variants:String,
    val previousText:String,val version:String,val ocrMs:Long,val expectedText:String,val query:String,val matchDebug:String="")

/** Null ids/options selects every cached record, including excluded or unavailable ones. */
data class ClearScope(val ids:List<Long>?=null,val options:SearchOptions?=null,val filter:PhotoFilter=PhotoFilter.ALL)
object PhotoLimits { const val SAMPLE_BATCH=500; const val OCR_BATCH=500 }

data class VisualStats(val total:Int=0,val ready:Int=0,val failed:Int=0)
enum class SearchMode(val label:String) { TEXT("文字"), DESCRIPTION("画面") }
data class PreparationKinds(val text:Boolean=true,val similar:Boolean=true,val description:Boolean=true) {
    val stages get()=buildList { if(text) { add("ocr");add("meaning") };if(similar) add("similar");if(description) add("description") }
}
data class PreparationStats(val total:Int=0,val ocr:Int=0,val ocrFailed:Int=0,val meaning:Int=0,val withText:Int=0,
    val similar:Int=0,val similarFailed:Int=0,val description:Int=0,val descriptionFailed:Int=0,val complete:Int=0) {
    operator fun plus(b:PreparationStats)=PreparationStats(total+b.total,ocr+b.ocr,ocrFailed+b.ocrFailed,meaning+b.meaning,withText+b.withText,
        similar+b.similar,similarFailed+b.similarFailed,description+b.description,descriptionFailed+b.descriptionFailed,complete+b.complete)
}
