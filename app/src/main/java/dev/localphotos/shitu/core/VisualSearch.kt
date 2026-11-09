package dev.localphotos.shitu.core

import android.net.Uri
import android.os.SystemClock
import dev.localphotos.shitu.data.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class VisualSearch(private val db:PhotoDatabase,private val embedder:VisualEmbedder) {
    private val preparation=Mutex()
    suspend fun prepare(id:Long):VisualFeatures=preparation.withLock {
        check(db.isEligible(id)) { "图片已不可访问或已被排除" }
        db.visual(id)?.let { return@withLock it }
        val photo=db.get(id,brief=true)?:error("图片已不可访问")
        val start=SystemClock.elapsedRealtime()
        val features=embedder.encode(Uri.parse(photo.uri))
        currentCoroutineContext().ensureActive()
        check(db.saveVisual(photo,features,SystemClock.elapsedRealtime()-start)) { "图片已变化，请重新打开" }
        features
    }
    suspend fun find(id:Long,options:SearchOptions=SearchOptions()):List<Hit> {
        val context=currentCoroutineContext()
        val fingerprint=db.get(id,brief=true)?.fingerprint
        val seed=prepare(id)
        val ranking=VisualRanking.Collector()
        var scanned=0
        db.eachVisual(options) { candidate,features,expectedFingerprint ->
            context.ensureActive()
            if(candidate!=id) {
                scanned++
                val score=VisualMath.score(seed,features)
                ranking.add(VisualRanking.Candidate(candidate,score,expectedFingerprint))
            }
        }
        check(db.isEligible(id) && db.get(id,brief=true)?.fingerprint==fingerprint) { "参照图片已变化，请重新打开" }
        val selection=ranking.finish()
        return selection.candidates.mapNotNull { candidate ->
            context.ensureActive()
            // Exclusions/deletions can change while the cursor is being scanned.
            db.get(candidate.id,brief=true)?.takeIf { it.fingerprint==candidate.fingerprint && db.isEligible(it.id) }?.let { photo ->
                val s=candidate.score
                val reason=if(s.close) "外观很接近" else if(s.cosine>=selection.strongCutoff) "画面相近" else "可能相近"
                val debug=JSONObject().put("kind","visual").put("seedId",id).put("modelVersion",VisualFeatures.VERSION)
                    .put("rankingVersion",VisualRanking.VERSION).put("scanned",scanned).put("supported",ranking.supported).put("cosine",s.cosine.toDouble()).put("appearance",s.appearance.toDouble()).put("hashDistance",s.hashDistance)
                    .put("cutoff",VisualRanking.FLOOR.toDouble()).put("strongCutoff",selection.strongCutoff.toDouble()).put("rank",s.rank.toDouble()).put("reason",reason)
                    .put("notes","分数用于排序，不是同图概率；画面或界面相近均可相关，不要求文字相同、轮廓一致或时间接近。")
                Hit(photo,photo.name,reason,s.rank.toDouble(),debug.toString())
            }
        }
    }
}
