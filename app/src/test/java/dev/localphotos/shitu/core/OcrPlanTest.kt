package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test

class OcrPlanTest {
    @Test fun ordinaryHighResolutionImagesUseOneBoundedOverview() {
        for((w,h) in listOf(4000 to 3000,3000 to 4000,8000 to 6000,4608 to 3456,1080 to 2400)) {
            val plan=OcrPlan.base(w,h)
            assertEquals(1,plan.size)
            val r=plan.single();val dw=(w.toLong()+r.sampleSize-1)/r.sampleSize;val dh=(h.toLong()+r.sampleSize-1)/r.sampleSize
            assertTrue(dw*dh<=4_000_000);assertTrue(maxOf(dw,dh)<=3200)
            assertEquals(w,r.right);assertEquals(h,r.bottom)
        }
    }
    @Test fun longImagesPreserveNativeScaleAndBalancedCoverage() {
        val plan=OcrPlan.base(1080,10000)
        assertTrue(plan.size>1);assertTrue(plan.all { it.sampleSize==1 && it.kind=="grid" })
        assertEquals(0,plan.first().top);assertEquals(10000,plan.last().bottom)
        for(length in listOf(1801,2200,4000,6200,10000)) {
            val axis=OcrPlan.axis(length)
            assertEquals(0,axis.first().first);assertEquals(length,axis.last().second)
            assertTrue(axis.all { it.second-it.first<=1800 })
            assertTrue(axis.zipWithNext().all { (a,b) -> a.second-b.first in 239..242 })
        }
    }
    @Test fun refinementIsEvidenceBasedDeduplicatedAndBounded() {
        val base=OcrPlan.base(4000,3000)
        val lines=listOf(500f to 500f,550f to 520f,3500f to 2500f,2000f to 1500f).map { (x,y) ->
            OcrLine("small readable label",OcrBox(x,y,x+100,y+24),0,confidence=0.9f)
        }
        val extra=OcrPlan.refinements(lines,base,4000,3000,1)
        assertEquals(2,extra.size)
        assertTrue(extra.all { it.kind=="detail" && it.sampleSize==1 })
        assertTrue(extra[0].box.overlap(extra[1].box)<0.5f)
        assertTrue(OcrPlan.refinements(emptyList(),base,4000,3000,1).isEmpty())
        assertTrue(OcrPlan.refinements(lines.map { it.copy(text=".") },base,4000,3000,1).isEmpty())
    }
    @Test fun nativeOverviewDoesNotGetNeedlessFollowup() {
        val plan=OcrPlan.base(1080,2400)
        val line=OcrLine("small label",OcrBox(10f,10f,100f,20f),0,true)
        assertTrue(OcrPlan.refinements(listOf(line),plan,1080,2400,1).isEmpty())
    }
    @Test fun shortEnoughForOldWholeImageLimitStillSplitsBothLongDirections() {
        for((w,h) in listOf(3000 to 600,600 to 3000,2400 to 500,500 to 2400)) {
            val plan=OcrPlan.base(w,h)
            assertTrue(plan.size>1)
            assertTrue(plan.all { it.sampleSize==1 && it.kind=="grid" })
            assertEquals(0,plan.first().left);assertEquals(0,plan.first().top)
            assertEquals(w,plan.last().right);assertEquals(h,plan.last().bottom)
        }
        assertEquals(1,OcrPlan.base(1500,300).size)
    }
}
