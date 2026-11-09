package dev.localphotos.shitu.lab

import android.content.Context
import android.net.Uri
import dev.localphotos.shitu.BuildConfig
import android.os.Build
import dev.localphotos.shitu.data.PhotoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only called by an explicit export action. No network, image decoding or original-file writes. */
class SampleExporter(private val context:Context,private val db:PhotoDatabase) {
    data class Result(val file:File,val count:Int,val images:Int,val skipped:Int)
    suspend fun export(ids:List<Long>,includeImages:Boolean,includePaths:Boolean,note:String,performance:String,onProgress:(Int,Int,Long)->Unit={_,_,_->}):Result=withContext(Dispatchers.IO) {
        require(ids.isNotEmpty()) { "请至少选择一张图片" }
        val selected=ids.distinct()
        val directory=File(context.cacheDir,"samples").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { it.deleteRecursively() }
        val token=UUID.randomUUID().toString().take(8)
        val work=File(directory,"work-$token").apply { mkdirs() }
        val partial=File(directory,"samples-$token.partial")
        val target=File(directory,"shitu-lab-${System.currentTimeMillis()}-$token.zip")
        val manifest=JSONArray();var bytes=0L;var imageCount=0;var skipped=0
        var lastProgress=0L
        fun progress(done:Int,currentBytes:Long=bytes) {
            val now=android.os.SystemClock.elapsedRealtime()
            if(done==selected.size || now-lastProgress>=200) { lastProgress=now;onProgress(done,selected.size,currentBytes) }
        }
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                fun textEntry(path:String,value:String) {
                    zip.setLevel(6);zip.putNextEntry(ZipEntry(path));zip.write(value.toByteArray(Charsets.UTF_8));zip.closeEntry()
                }
                textEntry("README.txt","拾图 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）测试样本\n每个 samples/ 子目录对应一张选中的图片。\nimage.* 是原文件的逐字节副本（如已选择包含原图）；不会重压缩。\nocr.txt 是整理后的文字；ocr-debug.json 保留逐块原始输出、坐标、耗时与处理模式。\nprevious-ocr.txt 是重跑前的上一版文字；review.json 是人工评价、预期文字和复现查询。\nsearch-variants.txt 是低权重检索变体，不应当作 OCR 原文。\nvisual-features.json 附带已有画面特征，未准备时不自动计算。\nmanifest.json 记录复制错误和校验值；未包含原图时只能检查文字与诊断。\n分享前请自行检查包内图片、OCR 和备注是否愿意提供。\n")
                textEntry("performance.json",performance)
                for((i,id) in selected.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val detail=db.details(id)
                    requireNotNull(detail) { "选中的图片记录已变化，请刷新后重选" }
                    val photo=detail.photo
                    val base="samples/${(i+1).toString().padStart(4,'0')}"
                    val row=JSONObject().put("sample",base).put("photoId",photo.id).put("width",photo.width).put("height",photo.height)
                        .put("marked",photo.marked).put("mime",photo.mime).put("added",photo.added).put("taken",photo.taken).put("originalBytes",photo.size).put("modified",photo.modified).put("state",photo.state).put("error",photo.error)
                        .put("ocrVersion",detail.version).put("ocrMs",detail.ocrMs).put("fingerprint",photo.fingerprint)
                    if(includePaths) row.put("sourceName",photo.name).put("sourceFolder",photo.folder).put("sourceUri",photo.uri)
                    if(includeImages) {
                        val staged=File(work,"original")
                        val digest=MessageDigest.getInstance("SHA-256")
                        var copied=0L
                        var available=false
                        val input=try { context.contentResolver.openInputStream(Uri.parse(photo.uri)) }
                            catch(_:SecurityException) { row.put("imageError","permission_changed");null }
                            catch(_:IOException) { row.put("imageError","file_unavailable");null }
                        if(input==null) {
                            skipped++;if(!row.has("imageError")) row.put("imageError","file_unavailable")
                        } else {
                            var readFailed=false
                            input.use { source ->
                                // Output failures (for example full storage) abort the ZIP, rather than silently skipping originals.
                                staged.outputStream().buffered().use { output ->
                                    val buffer=ByteArray(64*1024)
                                    while(true) {
                                        currentCoroutineContext().ensureActive()
                                        val n=try { source.read(buffer) } catch(_:IOException) { readFailed=true;break }
                                        if(n<0) break
                                        output.write(buffer,0,n);digest.update(buffer,0,n);copied+=n
                                        progress(i,bytes+copied)
                                    }
                                }
                            }
                            if(readFailed) { skipped++;row.put("imageError","file_unavailable") } else available=true
                        }
                        if(available) {
                            val extension=when(runCatching { context.contentResolver.getType(Uri.parse(photo.uri)) }.getOrNull()) {
                                "image/jpeg"->"jpg";"image/png"->"png";"image/webp"->"webp";"image/gif"->"gif";"image/heic"->"heic";"image/avif"->"avif"
                                else->photo.name.substringAfterLast('.',"bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }?:"bin"
                            }
                            val path="$base/image.$extension"
                            zip.setLevel(0);zip.putNextEntry(ZipEntry(path))
                            staged.inputStream().use { input ->
                                val buffer=ByteArray(64*1024)
                                while(true) {
                                    currentCoroutineContext().ensureActive()
                                    val n=input.read(buffer);if(n<0) break
                                    zip.write(buffer,0,n)
                                }
                            }
                            zip.closeEntry()
                            row.put("image",path).put("copiedBytes",copied).put("sha256",digest.digest().joinToString("") { "%02x".format(it) })
                            bytes+=copied;imageCount++
                        }
                        staged.delete()

                    }
                    textEntry("$base/ocr.txt",photo.text)
                    textEntry("$base/previous-ocr.txt",detail.previousText)
                    textEntry("$base/ocr-debug.json",detail.debug)
                    textEntry("$base/search-match.json",detail.matchDebug.ifBlank { "{}" })
                    textEntry("$base/merged-lines.json",detail.blocks)
                    textEntry("$base/search-variants.txt",detail.variants)
                    db.visualExport(id)?.let { features ->
                        textEntry("$base/visual-features.json",features.toString())
                        row.put("visualFeaturesFile","$base/visual-features.json")
                    }
                    if(BuildConfig.HAS_DESCRIPTION) db.descriptionExport(id)?.let { features ->
                        textEntry("$base/description-features.json",features.toString())
                        row.put("descriptionFeaturesFile","$base/description-features.json")
                    }
                    textEntry("$base/review.json",JSONObject().put("status",photo.reviewStatus).put("note",photo.reviewNote)
                        .put("expectedText",detail.expectedText).put("query",detail.query).toString(2))
                    manifest.put(row);progress(i+1)
                }
                textEntry("manifest.json",JSONObject().put("formatVersion",1).put("appVersion",BuildConfig.VERSION_NAME).put("appBuild",BuildConfig.VERSION_CODE).put("createdAt",System.currentTimeMillis())
                    .put("edition",BuildConfig.FLAVOR).apply { if(BuildConfig.HAS_DESCRIPTION) put("descriptionModelVersion",dev.localphotos.shitu.core.DescriptionFeatures.VERSION) }.put("visualRankingVersion",dev.localphotos.shitu.core.VisualRanking.VERSION).put("device",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("includeImages",includeImages).put("includeSourcePaths",includePaths)
                    .put("note",note.take(4000)).put("copiedImages",imageCount).put("skippedImages",skipped).put("samples",manifest).toString(2))
            }
            check(partial.renameTo(target)) { "无法完成样本文件" }
            onProgress(selected.size,selected.size,bytes)
            Result(target,selected.size,imageCount,skipped)
        } catch(e:Exception) { partial.delete();target.delete();if(e is IOException) throw IOException("打包未完成，请检查可用空间和文件访问状态。",e) else throw e }
        finally { work.deleteRecursively() }
    }
}
