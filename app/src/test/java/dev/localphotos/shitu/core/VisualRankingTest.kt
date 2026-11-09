package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VisualRankingTest {
    private fun score(cosine:Float,appearance:Float=0.95f,close:Boolean=false)=VisualScore(cosine,appearance,20,close)
    private fun collect(scores:List<VisualScore>):List<Long> {
        val collector=VisualRanking.Collector()
        scores.forEachIndexed { i,s -> collector.add(VisualRanking.Candidate(i.toLong(),s)) }
        return collector.finish().candidates.map { it.id }
    }
    @Test fun moderateModelEvidenceDoesNotRequireIdenticalLayout() {
        val scores=listOf(score(0.72f,0.30f),score(0.64f,0.90f),score(0.58f,0.70f),score(0.54f))
        assertEquals(listOf(1L,0L,2L),collect(scores))
        assertTrue(collect(listOf(score(0.54f),score(0.40f))).isEmpty())
    }
    @Test fun anAbruptScoreGapDoesNotProveTheBroaderRelationIsWrong() {
        val scores=listOf(score(0.88f),score(0.87f),score(0.75f),score(0.74f))
        assertEquals(listOf(0L,1L,2L,3L),collect(scores))
    }
    @Test fun duplicatesDoNotSuppressTheBroaderVisualFamily() {
        val scores=listOf(score(0.99f,close=true),score(0.72f),score(0.60f),score(0.56f))
        assertEquals(listOf(0L,1L,2L,3L),collect(scores))
        assertEquals(listOf(0L),collect(listOf(score(0.61f,close=true))))
    }
    @Test fun relativeWindowLabelsWeakerCandidatesWithoutRemovingThem() {
        val collector=VisualRanking.Collector()
        listOf(score(0.95f),score(0.70f),score(0.56f)).forEachIndexed { i,s -> collector.add(VisualRanking.Candidate(i.toLong(),s)) }
        val selection=collector.finish()
        assertEquals(0.80f,selection.strongCutoff,0.00001f)
        assertEquals(3,selection.candidates.size)
        assertEquals(2,selection.candidates.count { it.score.cosine<selection.strongCutoff })
    }
    @Test fun retainsEveryOriginalPolicyResultAcrossDifferentCandidateDistributions() {
        val random=Random(342)
        repeat(100) {
            val scores=List(160) { score(random.nextFloat(),random.nextFloat(),random.nextInt(10)==0) }
            val old=scores.indices.filter { scores[it].cosine>=0.55f }
                .sortedWith(compareByDescending<Int> { scores[it].rank }.thenBy { it }).take(40)
            val cutoff=maxOf(0.55f,(old.maxOfOrNull { scores[it].cosine }?:0.55f)-0.15f)
            val expected=old.filter { scores[it].close || scores[it].cosine>=cutoff }.map(Int::toLong)
            assertTrue(collect(scores).containsAll(expected))
        }
    }
    @Test fun theStreamingCollectorRemainsBoundedAndDeterministic() {
        assertEquals((0L..39L).toList(),collect(List(10000) { score(0.90f) }))
    }
}
