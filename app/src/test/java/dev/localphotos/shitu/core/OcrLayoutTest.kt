package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    @Test fun repeatedChatMessagesAtDifferentPositionsArePreserved() {
        val lines=listOf(
            OcrLine("收到",OcrBox(10f,10f,70f,40f),0),
            OcrLine("收到",OcrBox(11f,11f,71f,41f),1),
            OcrLine("收到",OcrBox(10f,100f,70f,130f),1))
        assertEquals(2,OcrLayout.merge(lines).size)
    }
    @Test fun completeOverlapResultReplacesClippedFragment() {
        val short=OcrLine("disco",OcrBox(20f,30f,120f,60f),0,true)
        val whole=OcrLine("discovery",OcrBox(20f,30f,210f,60f),1,false)
        assertEquals(listOf("discovery"),OcrLayout.merge(listOf(short,whole)).map { it.text })
    }
    @Test fun repairsSameRowAcrossTilesButKeepsSeparateBubbles() {
        val one=OcrLine("disco",OcrBox(10f,20f,110f,50f),0,true)
        val two=OcrLine("very",OcrBox(114f,21f,190f,51f),1,true)
        val far=OcrLine("other",OcrBox(400f,20f,500f,50f),1)
        val result=OcrLayout.merge(listOf(one,two,far)).map { it.text }
        assertTrue(result.contains("discovery"));assertTrue(result.contains("other"))
    }
    @Test fun allExifOrientationsMapBackToOriginalCoordinates() {
        val box=OcrBox(14f,23f,115f,89f)
        for(o in 1..8) assertEquals("orientation $o",box,OcrLayout.transform(OcrLayout.transform(box,400,300,o),400,300,o,true))
    }
    @Test fun plannerAvoidsThinTailAndCoversEveryPixel() {
        val starts=OcrLayout.starts(6200)
        assertEquals(0,starts.first());assertEquals(4400,starts.last())
        assertTrue(starts.zipWithNext().all { (a,b)->b>a && b-a<=1560 })
        assertEquals(listOf(0,10),OcrLayout.starts(1810))
    }
    @Test fun brokenWordsAreWeakMatchesAndIntactWordsRemainStronger() {
        val raw="Try disco\nvery today\n费\n用"
        val variants=TextTools.lineVariants(raw)
        assertTrue(variants.contains("discovery"));assertTrue(variants.contains("费用"))
        val score=TextTools.tolerantScore("discovery",raw,variants)
        assertTrue(score>0 && score<10)
        assertTrue(TextTools.tolerantScore("discovery","discovery","")>score)
        assertTrue(TextTools.matchQuery("discovery").contains("zzgramdis"))
    }
    @Test fun oneWrongEnglishLetterIsToleratedButCodesAreNotRewritten() {
        assertEquals(2.0,TextTools.tolerantScore("discovery","discovcry",""),0.0)
        assertEquals(0.0,TextTools.tolerantScore("AB-123","AB-128",""),0.0)
        assertEquals("",TextTools.lineVariants("AB\n123"))
        assertFalse(TextTools.oneEditApart("discovery","different"))
    }
    @Test fun geometricCrossLineCandidatesRequireNearbyAlignedLines() {
        val a=OcrLine("disco",OcrBox(10f,10f,100f,40f),0)
        val b=OcrLine("very",OcrBox(10f,50f,90f,80f),0)
        assertEquals("discovery",OcrLayout.searchVariants(listOf(a,b)))
        assertEquals("",OcrLayout.searchVariants(listOf(a,b.copy(box=b.box.shift(400f,0f)))))
        assertEquals("",OcrLayout.searchVariants(listOf(a,b.copy(box=b.box.shift(0f,200f)))))
    }
}
