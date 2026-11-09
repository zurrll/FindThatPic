package dev.localphotos.shitu.core

import android.os.SystemClock
import dev.localphotos.shitu.data.*
import dev.localphotos.shitu.work.SearchTrace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.PriorityQueue

class DescriptionSearch(private val db:PhotoDatabase,private val encoder:DescriptionEmbedder) {
    suspend fun find(query:String,options:SearchOptions,filter:PhotoFilter,trace:SearchTrace?=null):List<Hit> {
        val vector=encoder.text(query,trace)
        val context=currentCoroutineContext()
        data class Candidate(val id:Long,val score:Float,val fingerprint:String)
        val comparator=compareBy<Candidate> { it.score }.thenByDescending { it.id }
        val heap=PriorityQueue(101,comparator);var scanned=0
        val tick=SystemClock.elapsedRealtime()
        val visit:(Long,FloatArray,String)->Unit={ id,feature,fingerprint ->
            context.ensureActive();scanned++;val score=TextTools.dot(vector,feature)
            if(score.isFinite()) { heap.add(Candidate(id,score,fingerprint));if(heap.size>100) heap.poll() }
        }
        db.eachDescription(options,filter,visit)
        trace?.elapsed("recall",SystemClock.elapsedRealtime()-tick);trace?.put("scanned_vectors",scanned);trace?.put("coarse_candidates",heap.size)
        val hits=heap.sortedWith(comparator.reversed()).mapNotNull { candidate ->
            db.get(candidate.id,brief=true)?.takeIf { it.fingerprint==candidate.fingerprint && db.isEligible(it.id) }?.let { photo ->
                val debug=JSONObject().put("kind","description").put("query",query).put("modelVersion",DescriptionFeatures.VERSION).put("search_id",trace?.id)
                    .put("similarity",candidate.score.toDouble())
                    .put("notes","按画面与描述的相似度排序；分数不是概率，候选不保证满足全部条件，也未可靠判断图库是否存在答案。")
                Hit(photo,photo.name,"画面候选",candidate.score.toDouble(),debug.toString())
            }
        }
        fun ordered(rows:List<Hit>):List<Hit> {
            if(options.order==ResultOrder.RELEVANCE) return rows
            fun date(hit:Hit)=if(options.dateField==DateField.TAKEN) hit.photo.taken else hit.photo.added
            return rows.sortedWith(compareBy<Hit> { date(it)<=0 }.thenComparator { a,b -> if(options.order==ResultOrder.OLDEST) date(a).compareTo(date(b)) else date(b).compareTo(date(a)) })
        }
        val result=hits.filter { db.isEligible(it.photo.id) && db.get(it.photo.id,brief=true)?.fingerprint==it.photo.fingerprint }
        trace?.put("returned_candidates",result.size)
        return ordered(result)
    }
}
