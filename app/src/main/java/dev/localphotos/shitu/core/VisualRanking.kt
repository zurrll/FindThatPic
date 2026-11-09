package dev.localphotos.shitu.core

import java.util.PriorityQueue

/** The original model floor remains; a relative window labels confidence instead of deleting candidates. */
object VisualRanking {
    const val VERSION="visual-v3:soft-window"
    const val RESULTS=40
    const val FLOOR=0.55f
    const val MARGIN=0.15f
    fun supported(score:VisualScore)=score.cosine>=FLOOR
    fun strongCutoff(scores:List<VisualScore>):Float {
        // A duplicate should not raise the threshold for a broader family of related pictures.
        val best=scores.filter { !it.close && supported(it) }.maxOfOrNull { it.cosine }?:FLOOR
        return maxOf(FLOOR,best-MARGIN)
    }
    data class Candidate(val id:Long,val score:VisualScore,val fingerprint:String="")
    data class Selection(val strongCutoff:Float,val candidates:List<Candidate>)
    /** Shared by the streaming database query and offline replay; never retains the whole library. */
    class Collector {
        private val comparator=compareBy<Candidate> { it.score.rank }.thenByDescending { it.id }
        private val heap=PriorityQueue(RESULTS+1,comparator)
        var supported=0;private set
        fun add(candidate:Candidate) {
            if(!VisualRanking.supported(candidate.score)) return
            supported++
            heap.add(candidate);if(heap.size>RESULTS) heap.poll()
        }
        fun finish():Selection {
            return Selection(strongCutoff(heap.map { it.score }),heap.sortedWith(comparator.reversed()))
        }
    }
}
