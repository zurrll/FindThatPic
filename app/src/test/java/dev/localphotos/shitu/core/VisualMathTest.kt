package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test

class VisualMathTest {
    @Test fun hashDiscardsOverallBrightnessAndRanksAppearanceSeparatelyFromMeaning() {
        val gray=FloatArray(1024) { i -> ((i*37+i/32*19)%190).toFloat() }
        assertEquals(0,java.lang.Long.bitCount(VisualMath.hash(gray) xor VisualMath.hash(gray.map { it*2 }.toFloatArray())))
        val a=VisualFeatures(floatArrayOf(1f,0f),VisualMath.hash(gray),ByteArray(768) { 70 },200,100)
        val copy=a.copy(embedding=floatArrayOf(0.999f,0.0447f),width=400,height=200)
        val differentColor=a.copy(layout=ByteArray(768) { 230.toByte() })
        val differentShape=a.copy(width=100,height=200)
        assertTrue(VisualMath.score(a,copy).close)
        assertFalse(VisualMath.score(a,differentColor).close)
        assertFalse(VisualMath.score(a,differentShape).close)
        assertTrue(VisualMath.score(a,copy).rank>VisualMath.score(a,differentColor).rank)
    }
}
