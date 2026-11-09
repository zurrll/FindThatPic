package dev.localphotos.shitu.core

import dev.localphotos.shitu.data.Hit
import dev.localphotos.shitu.data.Photo
import dev.localphotos.shitu.data.PhotoDatabase
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.localphotos.shitu.work.SearchTrace
import android.os.SystemClock
import org.json.JSONObject

class SearchEngine(private val db:PhotoDatabase,private val embedder:LocalEmbedder) {
    suspend fun search(query:String,folder:String?,since:Long,semantic:Boolean,browseLimit:Int=60,filter:PhotoFilter=PhotoFilter.ALL,
        options:SearchOptions=SearchOptions(),onKeywords:((List<Hit>)->Unit)?=null,onSemanticUnavailable:(()->Unit)?=null,trace:SearchTrace?=null):List<Hit> {
        if(query.isBlank()) return db.recent(folder,since,browseLimit,filter,options).map { Hit(it,TextTools.snippet(it.text,""),"",0.0) }
        val lexicalStart=SystemClock.elapsedRealtime()
        val lexical=db.lexical(query,folder,since,filter,options)
        trace?.elapsed("lexical",SystemClock.elapsedRealtime()-lexicalStart);trace?.put("literal_candidates",lexical.count { it.second>=10.0 })
        val scores=mutableMapOf<Long,Double>();val photos=mutableMapOf<Long,Photo>();val reasons=mutableMapOf<Long,String>()
        val snippets=mutableMapOf<Long,String>()
        val passages=mutableMapOf<Long,String>()
        val lexicalScores=lexical.associate { it.first.id to it.second }
        fun exactReason(photo:Photo)=if(TextTools.normalize(photo.name).contains(TextTools.normalize(query)) && !TextTools.normalize(photo.text).contains(TextTools.normalize(query))) "文件名匹配" else "文字匹配"
        lexical.forEachIndexed { rank,(photo,score) ->
            scores[photo.id]=(if(score>=10) 1.0 else 0.0)+1.0/(60+rank)
            photos[photo.id]=photo;reasons[photo.id]=when { score>=10.0 -> exactReason(photo);score>=2.0 -> "断词 / 近似候选";else -> "部分字词候选" }
        }
        val semanticScores=mutableMapOf<Long,Float>()
        var cutoff:Float?=null;var best:Float?=null
        fun hits():List<Hit> {
            val relevance=compareByDescending<Map.Entry<Long,Double>> { it.value }
                .thenByDescending { photos[it.key]?.added?:0L }.thenBy { it.key }
            fun date(id:Long)=photos.getValue(id).let { if(options.dateField==DateField.TAKEN) it.taken else it.added }
            val ordering=if(options.order==ResultOrder.RELEVANCE) relevance else
                compareBy<Map.Entry<Long,Double>> { if(date(it.key)>0) 0 else 1 }.thenComparator { a,b ->
                    if(options.order==ResultOrder.OLDEST) date(a.key).compareTo(date(b.key)) else date(b.key).compareTo(date(a.key))
                }.then(relevance)
            return scores.entries.sortedWith(ordering).take(100).map { (id,score) ->
                val photo=photos.getValue(id)
                val excerpt=snippets[id]?:if(reasons[id]=="文件名匹配") photo.name else TextTools.snippet(photo.text,query)
                val trace=JSONObject().put("formatVersion",1).put("capturedAt",System.currentTimeMillis())
                    .put("query",query.take(4000)).put("semanticEnabled",semantic && !options.originalOnly).put("reason",reasons.getValue(id))
                    .put("lexicalScore",lexicalScores[id]?:JSONObject.NULL).put("similarity",semanticScores[id]?:JSONObject.NULL)
                    .put("cutoff",cutoff?:JSONObject.NULL).put("bestSimilarity",best?:JSONObject.NULL)
                    .put("rankingScore",score).put("semanticPrepared",photo.semantic).put("fingerprint",photo.fingerprint)
                    .put("search_id",trace?.id).put("matchedText",passages[id]?:excerpt).put("notes","分数用于排序")
                Hit(photo,excerpt,reasons.getValue(id),score,trace.toString())
            }
        }
        onKeywords?.invoke(hits().filter { (lexicalScores[it.photo.id]?:0.0)>=2.0 })
        val hasMeaning=semantic && !options.originalOnly && db.hasMeaningIndex(folder,since,filter,options)
        if(hasMeaning) {
            embedder.searching=true
            try {
                val vector=embedder.encode(TextTools.embeddingText(query),query=true,trace=trace)
                val recallStart=SystemClock.elapsedRealtime()
                val nearest=db.nearestPassages(vector,folder,since,filter,options) { photos,vectors ->trace?.put("scanned_photos",photos);trace?.put("scanned_vectors",vectors) }
                trace?.elapsed("recall",SystemClock.elapsedRealtime()-recallStart);trace?.put("coarse_candidates",nearest.size)
                best=nearest.firstOrNull()?.similarity
                cutoff=SemanticRanking.cutoff(best?:1f)
                nearest.forEach { semanticScores[it.photo.id]=it.similarity }
                val accepted=nearest.filter { it.similarity>=cutoff!! }
                val acceptedIds=accepted.mapTo(hashSetOf()) { it.photo.id }
                scores.clear()
                lexical.forEach { (photo,score) ->
                    // Shared characters alone are insufficient when the prepared meaning index disagrees.
                    if(score>=2.0 || !photo.semantic || photo.id in acceptedIds) {
                        scores[photo.id]=SemanticRanking.score(score,null)
                        if(!photo.semantic && score<2.0) reasons[photo.id]="文字候选 · 尚未准备含义"
                    }
                }
                accepted.forEach { match ->
                    val photo=match.photo;val lex=lexicalScores[photo.id]
                    scores[photo.id]=SemanticRanking.score(lex,match.similarity)
                    // Exact text keeps the lexical excerpt. Otherwise show the passage that matched.
                    if(lex==null || lex<10.0) {
                        photos[photo.id]=photo
                        snippets[photo.id]=TextTools.snippet(match.passage,query,140)
                        passages[photo.id]=match.passage
                    }
                    reasons[photo.id]=if(lex!=null && lex>=10.0) exactReason(photo) else "含义相近 · 图中文字"
                }
                onKeywords?.invoke(hits())
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { onSemanticUnavailable?.invoke() }
            finally { embedder.searching=false }
        }
        trace?.put("returned_candidates",scores.size.coerceAtMost(100))
        return hits().filter { db.isEligible(it.photo.id) && db.get(it.photo.id,brief=true)?.fingerprint==it.photo.fingerprint }
    }
}
