package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test

class TextToolsTest {
    @Test fun traditionalOcrFormsMatchSimplifiedQueries() {
        assertEquals("全屋费用明细",TextTools.normalize("全屋費用明細"))
        assertTrue("费用" in TextTools.tokens("全屋費用明細"))
    }
    @Test fun chineseSubstringsAndMixedCodesAreSearchable() {
        val tokens=TextTools.tokens("装修费用明细 AB-123 ＷｉＦｉ")
        assertTrue("费用" in tokens)
        assertTrue("费" in tokens)
        assertTrue("ab-123" in tokens)
        assertTrue("wifi" in tokens)
    }
    @Test fun unrelatedCommonCharacterDoesNotBecomeAGoodMatch() {
        assertEquals(0.0,TextTools.lexicalScore("租房合同","我的房间里有一只猫"),0.0)
        assertTrue(TextTools.lexicalScore("合同","租房合同编号 123")>=10)
    }
    @Test fun longScreenshotChunksCoverAllTextIncludingTail() {
        val text=(1..1000).joinToString(" ")
        val chunks=TextTools.chunks(text)
        assertTrue(chunks.all { it.length<=200 })
        assertTrue(chunks.last().endsWith("1000"))
        for(i in 0 until chunks.lastIndex) assertEquals(chunks[i].takeLast(30),chunks[i+1].take(30))
    }
    @Test fun tokenizerHandlesChineseEnglishAccentsAndUnknownWords() {
        val tokens=listOf("[PAD]","[UNK]","[CLS]","[SEP]","装","修","CAFÉ","play","##ing","!")
        val tokenizer=WordPiece(tokens.withIndex().associate { it.value to it.index })
        assertArrayEquals(longArrayOf(2,4,5,6,7,8,9,1,3),tokenizer.encode("装修 CAFÉ playing! xyz"))
        assertEquals(5,tokenizer.encode("装修装修装修",5).size)
    }
    @Test fun vectorStoragePreservesCosineAndRejectsDimensions() {
        val v=TextTools.unit(floatArrayOf(3f,4f))
        assertArrayEquals(v,TextTools.unpack(TextTools.pack(v)),0f)
        assertEquals(1f,TextTools.dot(v,v),0.0001f)
        assertEquals(-1f,TextTools.dot(v,floatArrayOf(1f)),0f)
    }
}
