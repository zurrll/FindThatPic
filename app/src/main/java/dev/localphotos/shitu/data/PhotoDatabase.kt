package dev.localphotos.shitu.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.localphotos.shitu.core.TextTools
import dev.localphotos.shitu.core.VisualFeatures
import dev.localphotos.shitu.core.DescriptionFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PhotoDatabase(context: Context, name: String = "photos.db") : SQLiteOpenHelper(context, name, null, 10) {
    private val visualRevision=MutableStateFlow(0L)
    /** A count cannot detect replacements or a changed search scope. Publish after successful writes. */
    val visualChanges=visualRevision.asStateFlow()
    private fun visualChanged() { visualRevision.update { it+1 } }
    private val columns="_id,media_key,uri,name,folder,modified,size,width,height,added,generation,state,ocr_text,error,semantic,review_status,review_note,taken,mime,marked"
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,
            media_key TEXT UNIQUE NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL,
            folder TEXT NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL,
            width INTEGER NOT NULL, height INTEGER NOT NULL, added INTEGER NOT NULL,
            generation INTEGER NOT NULL, fingerprint TEXT NOT NULL, seen INTEGER NOT NULL,
            available INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL DEFAULT 'pending',
            ocr_text TEXT NOT NULL DEFAULT '', blocks TEXT NOT NULL DEFAULT '[]',
            error TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0,
            ocr_ms INTEGER NOT NULL DEFAULT 0, semantic INTEGER NOT NULL DEFAULT 0, meaning_ms INTEGER)""")
        db.execSQL("CREATE INDEX photo_scope ON photos(available, state, added DESC)")
        db.execSQL("CREATE INDEX photo_folder ON photos(folder)")
        db.execSQL("CREATE VIRTUAL TABLE photo_fts USING fts4(tokens, tokenize=unicode61)")
        db.execSQL("CREATE TABLE vectors (photo_id INTEGER NOT NULL, chunk INTEGER NOT NULL, embedding BLOB NOT NULL, passage TEXT NOT NULL DEFAULT '', PRIMARY KEY(photo_id,chunk))")
        addVisualTable(db)
        addDescriptionTable(db)
        addCollectionColumns(db)
        addMetadataColumns(db)
        addLabColumns(db)
        db.execSQL("ALTER TABLE photos ADD COLUMN match_debug TEXT NOT NULL DEFAULT ''")
    }
    private fun addVisualTable(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS visual_features(photo_id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL, version TEXT NOT NULL, embedding BLOB NOT NULL, phash INTEGER NOT NULL, layout BLOB NOT NULL, decoded_width INTEGER NOT NULL, decoded_height INTEGER NOT NULL, error TEXT NOT NULL DEFAULT '', elapsed_ms INTEGER NOT NULL DEFAULT 0)")
    }
    private fun addDescriptionTable(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS description_features(photo_id INTEGER PRIMARY KEY,fingerprint TEXT NOT NULL,version TEXT NOT NULL,embedding BLOB NOT NULL,decoded_width INTEGER NOT NULL,decoded_height INTEGER NOT NULL,error TEXT NOT NULL DEFAULT '',elapsed_ms INTEGER NOT NULL DEFAULT 0)")
    }
    private fun addCollectionColumns(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN marked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE photos ADD COLUMN marked_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX photo_marked ON photos(marked,marked_at)")
        db.execSQL("CREATE TABLE excluded_folders(path TEXT PRIMARY KEY NOT NULL)")
    }
    private val eligible="available=1 AND NOT EXISTS (SELECT 1 FROM excluded_folders e WHERE substr(photos.folder,1,length(e.path))=e.path)"
    fun excludedFolders():Set<String> = readableDatabase.rawQuery("SELECT path FROM excluded_folders ORDER BY path",null).use { c ->
        buildSet { while(c.moveToNext()) add(c.getString(0)) }
    }
    fun setFolderExcluded(path:String,excluded:Boolean) {
        val clean=path.trim().trim('/')+"/"
        require(clean!="/" && clean.split('/').none { it==".." || it=="." }) { "请输入相对图库目录，例如 Pictures/Example/" }
        if(excluded) writableDatabase.execSQL("INSERT OR IGNORE INTO excluded_folders(path) VALUES (?)",arrayOf(clean))
        else writableDatabase.delete("excluded_folders","path=?",arrayOf(clean))
        visualChanged()
    }
    fun isEligible(id:Long):Boolean = readableDatabase.rawQuery("SELECT 1 FROM photos WHERE _id=? AND $eligible",arrayOf(id.toString())).use { it.moveToFirst() }
    fun mark(ids:List<Long>,marked:Boolean) {
        val db=writableDatabase;db.beginTransaction()
        try {
            ids.distinct().forEach { id -> db.execSQL("UPDATE photos SET marked=?,marked_at=? WHERE _id=?",
                arrayOf<Any>(if(marked) 1 else 0,if(marked) System.currentTimeMillis() else 0L,id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if(ids.isNotEmpty()) visualChanged()
    }
    fun saveFeedback(id:Long,note:String,query:String,matchDebug:String) {
        val previous=get(id)?:return
        review(id,if(note.isBlank()) previous.reviewStatus else if(previous.reviewStatus.isBlank()) "other" else previous.reviewStatus,
            note,details(id)?.expectedText.orEmpty(),query,matchDebug)
    }
    fun saveMatch(id:Long,query:String,debug:String) {
        if(debug.isBlank()) return
        val trace=runCatching { org.json.JSONObject(debug) }.getOrElse { return }
        if(!trace.has("query") && query.isNotBlank()) trace.put("query",query.take(500))
        // A fresh search trace must not rewrite the query attached to an older human note.
        writableDatabase.execSQL("UPDATE photos SET match_debug=? WHERE _id=?",arrayOf<Any>(trace.toString().take(32000),id))
    }
    /** Diagnostics read metadata and passage text, never decode images or load model sessions. */
    fun searchDiagnostics(id:Long):org.json.JSONObject {
        val result=org.json.JSONObject()
        for(table in listOf("visual_features","description_features")) {
            readableDatabase.rawQuery("SELECT version,fingerprint,length(embedding),decoded_width,decoded_height,error,elapsed_ms"+
                (if(table=="visual_features") ",phash,length(layout)" else "")+" FROM $table WHERE photo_id=?",arrayOf(id.toString())).use { c ->
                if(c.moveToFirst()) result.put(table,org.json.JSONObject().put("version",c.getString(0)).put("fingerprint",c.getString(1))
                    .put("bytes",c.getInt(2)).put("dimensions",c.getInt(2)/4).put("width",c.getInt(3)).put("height",c.getInt(4))
                    .put("error",c.getString(5)).put("elapsedMs",c.getLong(6)).apply {
                        if(table=="visual_features") put("phash",java.lang.Long.toUnsignedString(c.getLong(7),16)).put("layoutBytes",c.getInt(8))
                    })
            }
        }
        readableDatabase.rawQuery("SELECT meaning_ms FROM photos WHERE _id=? AND semantic=1",arrayOf(id.toString())).use { c ->
            if(c.moveToFirst() && !c.isNull(0)) result.put("meaningMs",c.getLong(0))
        }
        val passages=org.json.JSONArray()
        readableDatabase.rawQuery("SELECT chunk,passage,length(embedding) FROM vectors WHERE photo_id=? ORDER BY chunk",arrayOf(id.toString())).use { c ->
            while(c.moveToNext()) passages.put(org.json.JSONObject().put("index",c.getInt(0)).put("text",c.getString(1)).put("dimensions",c.getInt(2)/4))
        }
        result.put("passages",passages)
        readableDatabase.rawQuery("SELECT version FROM index_versions WHERE name='semantic'",null).use { c -> if(c.moveToFirst()) result.put("meaningVersion",c.getString(0)) }
        return result
    }
    private fun clearWhere(target:ClearScope):Pair<String,Array<String>> = when {
        target.ids!=null -> {
            if(target.ids.isEmpty()) "0" to emptyArray() else "_id IN (${target.ids.joinToString { "?" }})" to target.ids.map { it.toString() }.toTypedArray()
        }
        target.options!=null -> scope(null,0,target.filter,target.options)
        else -> "1" to emptyArray()
    }
    private fun clearScopes(target:ClearScope):List<ClearScope> = target.ids?.distinct()?.chunked(900)?.map { ClearScope(ids=it) }?:listOf(target)
    fun recognitionCount(target:ClearScope):Int = clearScopes(target).sumOf { part ->
        val (where,args)=clearWhere(part)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos WHERE $where AND (state<>'pending' OR previous_text<>'' OR ocr_text<>'' OR semantic=1 OR match_debug<>'')",args).use { it.moveToFirst();it.getInt(0) }
    }
    /** Clears derived data together; user marks and notes survive, and originals are never opened. */
    fun clearRecognition(target:ClearScope):Int {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val count=recognitionCount(target)
            for(part in clearScopes(target)) {
            val (where,args)=clearWhere(part)
            db.execSQL("DELETE FROM photo_fts WHERE rowid IN (SELECT _id FROM photos WHERE $where)",args)
            db.execSQL("DELETE FROM vectors WHERE photo_id IN (SELECT _id FROM photos WHERE $where)",args)
            db.execSQL("UPDATE photos SET state='pending',ocr_text='',search_text='',blocks='[]',ocr_debug='{}',ocr_version='legacy',previous_text='',match_debug='',error='',attempts=0,ocr_ms=0,semantic=0 WHERE $where",args)
            }
            db.setTransactionSuccessful();return count
        } finally { db.endTransaction() }
    }
    private fun addMetadataColumns(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN taken INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE photos ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE photos ADD COLUMN source TEXT NOT NULL DEFAULT 'OTHER'")
        db.execSQL("ALTER TABLE photos ADD COLUMN search_name TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX photo_taken ON photos(available,taken)")
        db.execSQL("CREATE INDEX photo_source ON photos(available,source)")
        // Populate cached metadata without touching OCR or vectors. MediaStore fills MIME/taken on refresh.
        db.rawQuery("SELECT _id,name,folder FROM photos",null).use { c ->
            while(c.moveToNext()) db.execSQL("UPDATE photos SET source=?,search_name=? WHERE _id=?",
                arrayOf<Any>(SourceKind.fromFolder(c.getString(2)).name,TextTools.normalize(c.getString(1)),c.getLong(0)))
        }
    }
    private fun addLabColumns(db:SQLiteDatabase) {
        for((name,default) in listOf("search_text" to "", "ocr_debug" to "{}", "ocr_version" to "legacy",
            "review_status" to "", "review_note" to "", "expected_text" to "", "review_query" to "", "previous_text" to ""))
            db.execSQL("ALTER TABLE photos ADD COLUMN $name TEXT NOT NULL DEFAULT '$default'")
        db.execSQL("CREATE INDEX photo_review ON photos(available,review_status)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<10) db.execSQL("ALTER TABLE photos ADD COLUMN meaning_ms INTEGER")
        if(oldVersion<8) addDescriptionTable(db)
        if(oldVersion<7) addVisualTable(db)
        if(oldVersion<6) addCollectionColumns(db)
        if(oldVersion<5) addMetadataColumns(db)
        if(oldVersion<2) addLabColumns(db)
        if(oldVersion<4) db.execSQL("ALTER TABLE photos ADD COLUMN match_debug TEXT NOT NULL DEFAULT ''")
        if(oldVersion<3) {
            db.execSQL("ALTER TABLE vectors ADD COLUMN passage TEXT NOT NULL DEFAULT ''")
            db.execSQL("DELETE FROM vectors")
            db.execSQL("UPDATE photos SET semantic=0")
        }
    }
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS index_versions (name TEXT PRIMARY KEY, version TEXT NOT NULL)")
        // Keep old OCR results until the user explicitly reruns selected samples. Per-photo versions identify them.
        val versions=mapOf("ocr" to "mlkit-cn16.0.1:tiles1800:v1","semantic" to "bge-75c43b0:cased-v2:passages-v1:cls256","keyword" to "nfkc-opencc-1.1.9:lab-v2")
        db.beginTransaction()
        try {
            versions.forEach { (name,version) ->
                val old=db.rawQuery("SELECT version FROM index_versions WHERE name=?",arrayOf(name)).use { if(it.moveToFirst()) it.getString(0) else null }
                if(name=="keyword" && old!=version) {
                    db.execSQL("DELETE FROM photo_fts")
                    db.rawQuery("SELECT _id,ocr_text,search_text FROM photos WHERE state='done'",null).use { c ->
                        while(c.moveToNext()) {
                            val variants=c.getString(2).ifBlank { TextTools.lineVariants(c.getString(1)) }
                            db.execSQL("UPDATE photos SET search_text=? WHERE _id=?",arrayOf(variants,c.getLong(0)))
                            db.execSQL("INSERT INTO photo_fts(rowid,tokens) VALUES (?,?)",arrayOf<Any>(c.getLong(0),TextTools.indexTokens(c.getString(1),variants).joinToString(" ")))
                        }
                    }
                }
                if(name!="keyword" && old!=null && old!=version) {
                    db.execSQL("DELETE FROM vectors")
                    db.execSQL("UPDATE photos SET semantic=0")
                    if(name=="ocr") {
                        db.execSQL("DELETE FROM photo_fts")
                        db.execSQL("UPDATE photos SET state='pending',ocr_text='',blocks='[]',error=''")
                    }
                }
                db.execSQL("INSERT OR REPLACE INTO index_versions VALUES (?,?)",arrayOf(name,version))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun upsertBatch(photos: List<Photo>, seen: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            photos.forEach { photo ->
                val previous = db.rawQuery("SELECT _id,fingerprint FROM photos WHERE media_key=?", arrayOf(photo.key)).use {
                    if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
                }
                val values = ContentValues().apply {
                    put("media_key", photo.key); put("uri", photo.uri); put("name", photo.name)
                    put("folder", photo.folder); put("modified", photo.modified); put("size", photo.size)
                    put("width", photo.width); put("height", photo.height); put("added", photo.added)
                    put("generation", photo.generation); put("fingerprint", photo.fingerprint)
                    put("seen", seen); put("available", 1)
                    put("taken",photo.taken); put("mime",photo.mime)
                    put("source",SourceKind.fromFolder(photo.folder).name); put("search_name",TextTools.normalize(photo.name))
                }
                if (previous == null) db.insertOrThrow("photos", null, values)
                else {
                    if (previous.second != photo.fingerprint) {
                        values.put("state", "pending"); values.put("ocr_text", ""); values.put("blocks", "[]")
                        values.put("error", ""); values.put("attempts", 0); values.put("semantic", 0)
                        values.put("search_text","");values.put("ocr_debug","{}");values.put("ocr_version","legacy");values.put("previous_text","")
                        db.delete("photo_fts", "rowid=?", arrayOf(previous.first.toString()))
                        db.delete("vectors", "photo_id=?", arrayOf(previous.first.toString()))
                        db.delete("visual_features", "photo_id=?", arrayOf(previous.first.toString()))
                        db.delete("description_features", "photo_id=?", arrayOf(previous.first.toString()))
                    }
                    db.update("photos", values, "_id=?", arrayOf(previous.first.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if(photos.isNotEmpty()) visualChanged()
    }
    fun finishDiscovery(seen: Long) {
        val changed=writableDatabase.update("photos",ContentValues().apply { put("available",0) },"available=1 AND seen<>?",arrayOf(seen.toString()))
        if(changed>0) visualChanged()
    }
    fun hideAll() { writableDatabase.execSQL("UPDATE photos SET available=0");visualChanged() }
    fun invalidateVolume(volume: String) {
        writableDatabase.beginTransaction()
        try {
            val ids = readableDatabase.rawQuery("SELECT _id FROM photos WHERE media_key LIKE ?", arrayOf("$volume:%")).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
            ids.forEach { id ->
                writableDatabase.delete("vectors", "photo_id=?", arrayOf(id.toString()))
                writableDatabase.delete("visual_features", "photo_id=?", arrayOf(id.toString()))
                writableDatabase.delete("description_features", "photo_id=?", arrayOf(id.toString()))
                writableDatabase.delete("photo_fts", "rowid=?", arrayOf(id.toString()))
            }
            writableDatabase.delete("photos", "media_key LIKE ?", arrayOf("$volume:%"))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        visualChanged()
    }
    private fun scope(folder: String?, since: Long,filter:PhotoFilter=PhotoFilter.ALL,options:SearchOptions=SearchOptions()): Pair<String, Array<String>> {
        val where = StringBuilder(eligible)
        val args = mutableListOf<String>()
        if (folder != null) { where.append(" AND folder=?"); args += folder }
        if(options.folders.isNotEmpty()) {
            where.append(" AND folder IN (${options.folders.joinToString { "?" }})");args+=options.folders.sorted()
        }
        options.source?.let { where.append(" AND source=?");args+=it.name }
        val date=options.dateField.column
        if(options.undatedOnly) where.append(" AND $date<=0") else {
            if(options.after>0) { where.append(" AND $date>=?");args+=options.after.toString() }
            if(options.before>0) { where.append(" AND $date>0 AND $date<?");args+=options.before.toString() }
        }
        if(options.format!=ImageFormat.ALL) { where.append(" AND mime=?");args+=options.format.mime }
        if(options.markedOnly) where.append(" AND marked=1")
        if(options.longOnly) where.append(" AND width>0 AND height>0 AND (height>=width*3 OR width>=height*3)")
        if (since > 0) { where.append(" AND added>=?"); args += since.toString() }
        when(filter) {
            PhotoFilter.ALL -> Unit
            PhotoFilter.PENDING -> where.append(" AND state='pending'")
            PhotoFilter.DONE -> where.append(" AND state='done'")
            PhotoFilter.EMPTY -> where.append(" AND state='empty'")
            PhotoFilter.FAILED -> where.append(" AND state='failed'")
            PhotoFilter.ISSUES -> where.append(" AND review_status IN ('missed','broken','wrong','other')")
            PhotoFilter.REVIEWED -> where.append(" AND review_status<>''")
            PhotoFilter.LEGACY -> where.append(" AND ocr_version='legacy' AND state<>'pending'")
            PhotoFilter.LONG -> where.append(" AND (height>=width*3 OR width>=height*3)")
        }
        return where.toString() to args.toTypedArray()
    }
    fun stats(): LibraryStats {
        var total=0;var done=0;var empty=0;var failed=0;var semantic=0
        readableDatabase.rawQuery("SELECT state,COUNT(*),SUM(semantic) FROM photos WHERE $eligible GROUP BY state", null).use { c ->
            while(c.moveToNext()) {
                val count=c.getInt(1);total+=count;semantic+=c.getInt(2)
                when(c.getString(0)) { "done"->done+=count;"empty"->empty+=count;"failed"->failed+=count }
            }
        }
        val folders=readableDatabase.rawQuery("SELECT folder,COUNT(*) FROM photos WHERE available=1 OR EXISTS (SELECT 1 FROM excluded_folders e WHERE substr(photos.folder,1,length(e.path))=e.path) GROUP BY folder ORDER BY COUNT(*) DESC",null).use { c ->
            buildList { while(c.moveToNext()) add(Folder(c.getString(0),c.getInt(1))) }
        }
        val marked=readableDatabase.rawQuery("SELECT COUNT(*) FROM photos WHERE $eligible AND marked=1",null).use { it.moveToFirst();it.getInt(0) }
        return LibraryStats(total,done,empty,failed,semantic,folders,marked)
    }
    private fun dateOrder(options:SearchOptions):String {
        val date=options.dateField.column
        return "CASE WHEN $date>0 THEN 0 ELSE 1 END, $date ${if(options.order==ResultOrder.OLDEST) "ASC" else "DESC"},_id DESC"
    }
    fun recent(folder: String?, since: Long, limit: Int,filter:PhotoFilter=PhotoFilter.ALL,options:SearchOptions=SearchOptions()): List<Photo> {
        val (where,args)=scope(folder,since,filter,options)
        return readableDatabase.rawQuery("SELECT ${columns.replace("ocr_text","substr(ocr_text,1,160) AS ocr_text")} FROM photos WHERE $where ORDER BY ${dateOrder(options)} LIMIT ?",args+limit.toString()).use(::readPhotos)
    }
    /** Select a consecutive batch in the current date order, reading IDs only. */
    fun sampleIds(limit:Int,excluded:Set<Long>,filter:PhotoFilter,options:SearchOptions):List<Long> {
        require(limit>0)
        val (where,args)=scope(null,0,filter,options)
        val ids=readableDatabase.rawQuery("SELECT _id FROM photos WHERE $where ORDER BY ${dateOrder(options)}",args).use { c ->
            buildList { while(size<limit && c.moveToNext()) if(c.getLong(0) !in excluded) add(c.getLong(0)) }
        }
        return ids
    }
    fun pending(folder: String?, limit: Int, semantics: Boolean): List<Photo> {
        val (where,args)=scope(folder,0)
        val condition=if(semantics) "(state='pending' OR (state='done' AND semantic=0))" else "state='pending'"
        return readableDatabase.rawQuery("SELECT $columns FROM photos WHERE $where AND $condition ORDER BY added DESC,_id DESC LIMIT ?",args+limit.toString()).use(::readPhotos)
    }
    fun pendingIds(folder:String?,limit:Int,semantics:Boolean,options:SearchOptions=SearchOptions(),meaningsOnly:Boolean=false):List<Long> {
        val (where,args)=scope(folder,0,options=options)
        val condition=if(meaningsOnly) "(state='done' AND semantic=0)" else if(semantics) "(state='pending' OR (state='done' AND semantic=0))" else "state='pending'"
        return readableDatabase.rawQuery("SELECT _id FROM photos WHERE $where AND $condition ORDER BY added DESC,_id DESC LIMIT ?",args+limit.toString()).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
    }
    fun complete(id: Long, text: String, blocks: String, ms: Long, expectedFingerprint:String?=null,
        variants:String=TextTools.lineVariants(text),debug:String="{}",version:String="legacy"):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            val changed=db.update("photos",ContentValues().apply {
                put("state",if(text.isBlank()) "empty" else "done");put("ocr_text",text)
                put("blocks",blocks);put("ocr_ms",ms);put("error","")
                put("search_text",variants);put("ocr_debug",debug);put("ocr_version",version);put("semantic",0)
            },if(expectedFingerprint==null) "_id=? AND $eligible" else "_id=? AND fingerprint=? AND $eligible",
                if(expectedFingerprint==null) arrayOf(id.toString()) else arrayOf(id.toString(),expectedFingerprint))
            if(changed==0) { db.setTransactionSuccessful();return false }
            db.delete("photo_fts","rowid=?",arrayOf(id.toString()))
            db.delete("vectors","photo_id=?",arrayOf(id.toString()))
            db.execSQL("INSERT INTO photo_fts(rowid,tokens) VALUES (?,?)",arrayOf<Any>(id,TextTools.indexTokens(text,variants).joinToString(" ")))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun fail(id: Long, code: String,expectedFingerprint:String?=null) = writableDatabase.execSQL(
        "UPDATE photos SET state='failed',error=?,attempts=attempts+1 WHERE _id=? AND $eligible"+(if(expectedFingerprint!=null) " AND fingerprint=?" else ""),
        if(expectedFingerprint==null) arrayOf<Any>(code,id) else arrayOf<Any>(code,id,expectedFingerprint))
    fun retryFailures() = writableDatabase.execSQL("UPDATE photos SET state='pending',error='' WHERE $eligible AND state='failed'")
    fun saveVectors(id: Long, vectors: List<FloatArray>,expectedFingerprint:String?=null,passages:List<String> = emptyList(),elapsedMs:Long?=null) {
        require(passages.isEmpty() || passages.size==vectors.size)
        val db=writableDatabase;db.beginTransaction()
        try {
            if(!isEligible(id) || (expectedFingerprint!=null && get(id)?.fingerprint!=expectedFingerprint)) { db.setTransactionSuccessful();return }
            db.delete("vectors","photo_id=?",arrayOf(id.toString()))
            vectors.forEachIndexed { index,vector -> db.execSQL("INSERT INTO vectors(photo_id,chunk,embedding,passage) VALUES (?,?,?,?)",arrayOf(id,index,TextTools.pack(vector),passages.getOrElse(index) { "" })) }
            db.execSQL("UPDATE photos SET semantic=1,meaning_ms=? WHERE _id=?",arrayOf(elapsedMs,id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun lexical(query: String, folder: String?, since: Long,filter:PhotoFilter=PhotoFilter.ALL,options:SearchOptions=SearchOptions()): List<Pair<Photo,Double>> {
        val (where,args)=scope(folder,since,filter,options)
        val q=TextTools.normalize(query)
        if(q.isBlank()) return emptyList()
        val photos=linkedMapOf<Long,Pair<Photo,Double>>()
        fun consider(c:Cursor) {
            while(c.moveToNext()) {
                val photo=photo(c)
                val textScore=TextTools.tolerantScore(q,photo.text,c.getString(c.getColumnIndexOrThrow("search_text")))
                val nameScore=if(TextTools.normalize(photo.name).contains(q)) 10.0+q.length.coerceAtMost(100)/100.0 else 0.0
                val score=maxOf(textScore,nameScore)
                if(options.originalOnly && score<10.0) continue
                if(score>0) photos[photo.id]=photo.copy(text=TextTools.snippet(photo.text,q,480)) to score
            }
        }
        val escaped=q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
        readableDatabase.rawQuery("SELECT $columns,search_text FROM photos WHERE $where AND (ocr_text LIKE ? ESCAPE '\\' OR search_text LIKE ? ESCAPE '\\' OR search_name LIKE ? ESCAPE '\\') ORDER BY ${dateOrder(options)} LIMIT 200",args+arrayOf("%$escaped%","%$escaped%","%$escaped%")).use(::consider)
        val match=TextTools.matchQuery(q)
        if(match.isNotBlank()) readableDatabase.rawQuery("SELECT $columns,search_text FROM photos WHERE $where AND _id IN (SELECT rowid FROM photo_fts WHERE photo_fts MATCH ?) ORDER BY ${dateOrder(options)} LIMIT 2000",args+match).use(::consider)
        return photos.values.sortedByDescending { it.second }.take(200)
    }
    fun nearest(vector: FloatArray, folder: String?, since: Long,filter:PhotoFilter=PhotoFilter.ALL): List<Pair<Photo,Float>> {
        return nearestPassages(vector,folder,since,filter).map { it.photo to it.similarity }
    }
    fun hasMeaningIndex(folder:String?,since:Long,filter:PhotoFilter,options:SearchOptions):Boolean {
        val (where,args)=scope(folder,since,filter,options)
        return readableDatabase.rawQuery("SELECT 1 FROM photos WHERE $where AND semantic=1 LIMIT 1",args).use { it.moveToFirst() }
    }
    fun nearestPassages(vector:FloatArray,folder:String?,since:Long,filter:PhotoFilter=PhotoFilter.ALL,options:SearchOptions=SearchOptions(),onScanned:(Int,Int)->Unit={_,_->}):List<SemanticMatch> {
        val (where,args)=scope(folder,since,filter,options)
        val best=HashMap<Long,Pair<Float,String>>();var scanned=0
        readableDatabase.rawQuery("SELECT vectors.photo_id,vectors.embedding,vectors.passage FROM vectors JOIN photos ON photos._id=vectors.photo_id WHERE $where",args).use { c ->
            while(c.moveToNext()) {
                scanned++;val id=c.getLong(0);val score=TextTools.dot(vector,TextTools.unpack(c.getBlob(1)))
                if(score.isFinite() && score>(best[id]?.first?:-Float.MAX_VALUE)) best[id]=score to c.getString(2)
            }
        }
        onScanned(best.size,scanned)
        return best.entries.sortedWith(compareByDescending<Map.Entry<Long,Pair<Float,String>>> { it.value.first }.thenBy { it.key }).take(80)
            .mapNotNull { (id,value) -> get(id,brief=true)?.let { SemanticMatch(it,value.first,value.second.ifBlank { it.text }) } }
    }
    fun visualStats(options:SearchOptions=SearchOptions()):VisualStats {
        val (where,args)=scope(null,0,options=options)
        return readableDatabase.rawQuery("""SELECT COUNT(*),
            SUM(CASE WHEN f.error='' AND length(f.embedding)=${VisualFeatures.DIMENSIONS*4} THEN 1 ELSE 0 END),
            SUM(CASE WHEN f.error<>'' THEN 1 ELSE 0 END)
            FROM photos LEFT JOIN visual_features f ON f.photo_id=photos._id AND f.version=? AND f.fingerprint=photos.fingerprint WHERE $where""",
            arrayOf(VisualFeatures.VERSION)+args).use {
            it.moveToFirst();VisualStats(it.getInt(0),it.getInt(1),it.getInt(2))
        }
    }
    fun pendingVisualIds(limit:Int,options:SearchOptions=SearchOptions(),retry:Boolean=false):List<Long> {
        require(limit in 1..100000)
        val (where,args)=scope(null,0,options=options)
        return readableDatabase.rawQuery("""SELECT _id FROM photos WHERE $where AND NOT EXISTS
            (SELECT 1 FROM visual_features f WHERE f.photo_id=photos._id AND f.version=? AND f.fingerprint=photos.fingerprint ${if(retry) "AND f.error=''" else ""})
            ORDER BY added DESC,_id DESC LIMIT ?""",args+arrayOf(VisualFeatures.VERSION,limit.toString())).use { c ->
            buildList { while(c.moveToNext()) add(c.getLong(0)) }
        }
    }
    fun visualAttempted(id:Long):Boolean=readableDatabase.rawQuery("""SELECT 1 FROM visual_features f JOIN photos ON photos._id=f.photo_id
        WHERE photos._id=? AND $eligible AND f.version=? AND f.fingerprint=photos.fingerprint""",arrayOf(id.toString(),VisualFeatures.VERSION)).use { it.moveToFirst() }
    fun visual(id:Long):VisualFeatures?=readableDatabase.rawQuery("""SELECT f.embedding,f.phash,f.layout,f.decoded_width,f.decoded_height FROM visual_features f JOIN photos ON photos._id=f.photo_id
        WHERE photos._id=? AND $eligible AND f.version=? AND f.fingerprint=photos.fingerprint AND f.error=''""",arrayOf(id.toString(),VisualFeatures.VERSION)).use { c ->
        if(c.moveToFirst() && c.getBlob(0).size==VisualFeatures.DIMENSIONS*4) VisualFeatures(TextTools.unpack(c.getBlob(0)),c.getLong(1),c.getBlob(2),c.getInt(3),c.getInt(4)) else null
    }
    /** Fingerprint + eligibility check and write share a transaction, including failure records. */
    fun saveVisual(photo:Photo,features:VisualFeatures?,elapsedMs:Long,error:String=""):Boolean {
        if(features!=null) require(features.embedding.size==VisualFeatures.DIMENSIONS && features.embedding.all { it.isFinite() } && features.layout.size==768 && features.width>0 && features.height>0)
        val db=writableDatabase;db.beginTransaction()
        try {
            if(!isEligible(photo.id) || get(photo.id,brief=true)?.fingerprint!=photo.fingerprint) { db.setTransactionSuccessful();return false }
            db.execSQL("INSERT OR REPLACE INTO visual_features VALUES (?,?,?,?,?,?,?,?,?,?)",arrayOf(photo.id,photo.fingerprint,VisualFeatures.VERSION,
                features?.let { TextTools.pack(it.embedding) }?:byteArrayOf(),features?.hash?:0L,features?.layout?:byteArrayOf(),features?.width?:0,features?.height?:0,error,elapsedMs))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        visualChanged();return true
    }
    /** Stream vectors from SQLite; retain only the small top-k heap in the caller. */
    fun eachVisual(options:SearchOptions,visit:(Long,VisualFeatures,String)->Unit) {
        val (where,args)=scope(null,0,options=options)
        readableDatabase.rawQuery("""SELECT f.photo_id,f.embedding,f.phash,f.layout,f.decoded_width,f.decoded_height,photos.fingerprint FROM visual_features f JOIN photos ON photos._id=f.photo_id
            WHERE $where AND f.version=? AND f.fingerprint=photos.fingerprint AND f.error=''""",args+VisualFeatures.VERSION).use { c ->
            while(c.moveToNext()) {
                val bytes=c.getBlob(1);val layout=c.getBlob(3)
                if(bytes.size==VisualFeatures.DIMENSIONS*4 && layout.size==768 && c.getInt(4)>0 && c.getInt(5)>0)
                    visit(c.getLong(0),VisualFeatures(TextTools.unpack(bytes),c.getLong(2),layout,c.getInt(4),c.getInt(5)),c.getString(6))
            }
        }
    }
    private fun preparationCondition(stage:String,retry:Boolean):String=when(stage) {
        "ocr"->if(retry) "state IN ('pending','failed')" else "state='pending'"
        "meaning"->"state='done' AND semantic=0"
        "similar"->"NOT EXISTS (SELECT 1 FROM visual_features f WHERE f.photo_id=photos._id AND f.fingerprint=photos.fingerprint AND f.version='${VisualFeatures.VERSION}' AND ${if(retry) "f.error='' AND length(f.embedding)=5120" else "(f.error<>'' OR length(f.embedding)=5120)"})"
        "description"->"NOT EXISTS (SELECT 1 FROM description_features f WHERE f.photo_id=photos._id AND f.fingerprint=photos.fingerprint AND f.version='${DescriptionFeatures.VERSION}' AND ${if(retry) "f.error='' AND length(f.embedding)=${DescriptionFeatures.BYTES}" else "(f.error<>'' OR length(f.embedding)=${DescriptionFeatures.BYTES})"})"
        else->error("Unknown preparation stage")
    }
    fun pendingPreparationIds(limit:Int,options:SearchOptions,kinds:PreparationKinds,retry:Boolean=false):List<Long> {
        require(limit>0 && kinds.stages.isNotEmpty())
        val (where,args)=scope(null,0,options=options)
        val needed=kinds.stages.joinToString(" OR ") { "(${preparationCondition(it,retry)})" }
        return readableDatabase.rawQuery("SELECT _id FROM photos WHERE $where AND ($needed) ORDER BY ${dateOrder(options)} LIMIT ?",args+limit.toString()).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
    }
    fun pendingPreparationStage(ids:List<Long>,stage:String,retry:Boolean):Set<Long> = buildSet {
        for(batch in ids.distinct().chunked(900)) {
            val where=batch.joinToString { "?" }
            readableDatabase.rawQuery("SELECT _id FROM photos WHERE $eligible AND _id IN ($where) AND (${preparationCondition(stage,retry)})",batch.map(Long::toString).toTypedArray()).use { c -> while(c.moveToNext()) add(c.getLong(0)) }
        }
    }
    fun preparationStats(options:SearchOptions=SearchOptions(),ids:List<Long>?=null):PreparationStats {
        val (base,args)=scope(null,0,options=options)
        val parts=ids?.distinct()?.chunked(900)?.map { batch -> "$base AND _id IN (${batch.joinToString { "?" }})" to (args+batch.map(Long::toString)) }?:listOf(base to args)
        var total=PreparationStats()
        for((where,params) in parts) {
            val vr="v.error='' AND length(v.embedding)=5120"
            val dr="d.error='' AND length(d.embedding)=${DescriptionFeatures.BYTES}"
            val versions=arrayOf(VisualFeatures.VERSION,DescriptionFeatures.VERSION)
            val tr="(state='empty' OR (state='done' AND semantic=1))"
            readableDatabase.rawQuery("""SELECT COUNT(*),SUM(CASE WHEN state IN ('done','empty') THEN 1 ELSE 0 END),SUM(CASE WHEN state='failed' THEN 1 ELSE 0 END),
                SUM(CASE WHEN state='done' AND semantic=1 THEN 1 ELSE 0 END),SUM(CASE WHEN state='done' THEN 1 ELSE 0 END),
                SUM(CASE WHEN $vr THEN 1 ELSE 0 END),SUM(CASE WHEN v.error<>'' THEN 1 ELSE 0 END),
                SUM(CASE WHEN $dr THEN 1 ELSE 0 END),SUM(CASE WHEN d.error<>'' THEN 1 ELSE 0 END),
                SUM(CASE WHEN $tr AND $vr AND ($dr) THEN 1 ELSE 0 END)
                FROM photos LEFT JOIN visual_features v ON v.photo_id=photos._id AND v.fingerprint=photos.fingerprint AND v.version=?
                LEFT JOIN description_features d ON d.photo_id=photos._id AND d.fingerprint=photos.fingerprint AND d.version=?
                WHERE $where""",versions+params).use { c ->
                c.moveToFirst();total+=PreparationStats(c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3),c.getInt(4),c.getInt(5),c.getInt(6),c.getInt(7),c.getInt(8),c.getInt(9))
            }
        }
        return total
    }
    fun description(id:Long):DescriptionFeatures?=readableDatabase.rawQuery("""SELECT f.embedding,f.decoded_width,f.decoded_height FROM description_features f JOIN photos ON photos._id=f.photo_id
        WHERE _id=? AND $eligible AND f.version=? AND f.fingerprint=photos.fingerprint AND f.error=''""",arrayOf(id.toString(),DescriptionFeatures.VERSION)).use { c ->
        if(c.moveToFirst() && c.getBlob(0).size==DescriptionFeatures.BYTES) DescriptionFeatures(TextTools.unpack(c.getBlob(0)),c.getInt(1),c.getInt(2)) else null
    }
    fun saveDescription(photo:Photo,value:DescriptionFeatures?,elapsedMs:Long,error:String=""):Boolean {
        if(value!=null) require(value.embedding.size==DescriptionFeatures.DIMENSIONS && value.embedding.all { it.isFinite() } && value.width>0 && value.height>0)
        val db=writableDatabase;db.beginTransaction()
        try {
            if(!isEligible(photo.id) || get(photo.id,brief=true)?.fingerprint!=photo.fingerprint) { db.setTransactionSuccessful();return false }
            db.execSQL("INSERT OR REPLACE INTO description_features VALUES (?,?,?,?,?,?,?,?)",arrayOf(photo.id,photo.fingerprint,DescriptionFeatures.VERSION,value?.let { TextTools.pack(it.embedding) }?:byteArrayOf(),value?.width?:0,value?.height?:0,error,elapsedMs))
            db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }
    fun eachDescription(options:SearchOptions,filter:PhotoFilter,visit:(Long,FloatArray,String)->Unit) {
        val (where,args)=scope(null,0,filter,options)
        readableDatabase.rawQuery("""SELECT f.photo_id,f.embedding,photos.fingerprint FROM description_features f JOIN photos ON photos._id=f.photo_id
            WHERE $where AND f.version=? AND f.fingerprint=photos.fingerprint AND f.error=''""",args+DescriptionFeatures.VERSION).use { c ->
            while(c.moveToNext()) { val value=c.getBlob(1);if(value.size==DescriptionFeatures.BYTES) visit(c.getLong(0),TextTools.unpack(value),c.getString(2)) }
        }
    }
    fun get(id: Long,brief:Boolean=false): Photo? = readableDatabase.rawQuery("SELECT ${if(brief) columns.replace("ocr_text","substr(ocr_text,1,480) AS ocr_text") else columns} FROM photos WHERE _id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) photo(it) else null }
    fun details(id:Long):PhotoDetails?=readableDatabase.rawQuery("SELECT $columns,blocks,ocr_debug,search_text,previous_text,ocr_version,ocr_ms,expected_text,review_query,match_debug FROM photos WHERE _id=?",arrayOf(id.toString())).use { c ->
        if(!c.moveToFirst()) null else {
            fun s(n:String)=c.getString(c.getColumnIndexOrThrow(n))
            PhotoDetails(photo(c),s("blocks"),s("ocr_debug"),s("search_text"),s("previous_text"),s("ocr_version"),c.getLong(c.getColumnIndexOrThrow("ocr_ms")),s("expected_text"),s("review_query"),s("match_debug"))
        }
    }
    fun review(id:Long,status:String,note:String,expected:String,query:String,matchDebug:String?=null) {
        require(status in setOf("","good","missed","broken","wrong","other"))
        writableDatabase.update("photos",ContentValues().apply {
            put("review_status",status);put("review_note",note.take(4000));put("expected_text",expected.take(4000));put("review_query",query.take(500));if(matchDebug!=null) put("match_debug",matchDebug.take(32000))
        },"_id=?",arrayOf(id.toString()))
    }
    fun requeue(ids:List<Long>) {
        val db=writableDatabase;db.beginTransaction()
        try {
            ids.distinct().forEach { id ->
                val available=db.rawQuery("SELECT available FROM photos WHERE _id=?",arrayOf(id.toString())).use { it.moveToFirst() && it.getInt(0)==1 }
                if(!available || !isEligible(id)) return@forEach
                db.execSQL("UPDATE photos SET previous_text=CASE WHEN state IN ('done','empty') THEN ocr_text ELSE previous_text END,state='pending',ocr_text='',search_text='',blocks='[]',ocr_debug='{}',error='',semantic=0 WHERE _id=? AND available=1",arrayOf(id))
                db.delete("photo_fts","rowid=?",arrayOf(id.toString()));db.delete("vectors","photo_id=?",arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun readPhotos(c: Cursor)=buildList { while(c.moveToNext()) add(photo(c)) }
    private fun photo(c: Cursor): Photo {
        fun s(n:String)=c.getString(c.getColumnIndexOrThrow(n))
        fun l(n:String)=c.getLong(c.getColumnIndexOrThrow(n))
        return Photo(l("_id"),s("media_key"),s("uri"),s("name"),s("folder"),l("modified"),l("size"),l("width").toInt(),l("height").toInt(),l("added"),l("generation"),s("state"),s("ocr_text"),s("error"),l("semantic")==1L,s("review_status"),s("review_note"),l("taken"),s("mime"),l("marked")==1L)
    }
}
