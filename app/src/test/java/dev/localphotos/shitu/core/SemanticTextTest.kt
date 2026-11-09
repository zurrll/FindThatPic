package dev.localphotos.shitu.core

import org.junit.Assert.*
import org.junit.Test

class SemanticTextTest {
    @Test fun boundariesKeepSentencesAndTailWithBoundedChunks() {
        val first="甲".repeat(145)+"。"
        val second="换新手机前请在旧手机打开微信，选择聊天记录迁移与备份。"
        val text=first+second+"乙".repeat(140)+"。尾部校验完成。"
        val parts=SemanticText.passages(text)
        assertTrue(parts.all { it.length<=200 })
        assertTrue(parts.any { second in it })
        assertTrue(parts.last().endsWith("尾部校验完成。"))
    }
    @Test fun longUnbrokenTextIsCoveredAndSurrogatesAreNotSplit() {
        val text=(1..900).joinToString("") { "图😀${it}文" }
        val parts=SemanticText.passages(text)
        var end=0
        parts.forEach { part ->
            val at=text.indexOf(part,(end-200).coerceAtLeast(0))
            assertTrue("Gap at $end -> $at",at>=0 && at<=end)
            assertFalse(Character.isLowSurrogate(part.first()))
            assertFalse(Character.isHighSurrogate(part.last()))
            end=at+part.length
        }
        assertEquals(text.length,end)
    }
    @Test fun whitespaceAndSymbolOnlyTextDoNotCreateVectors() {
        assertTrue(SemanticText.passages("\n --- 😀 • \n").isEmpty())
        assertEquals(listOf("订单 AB-123"),SemanticText.passages("\n订单 AB-123\n"))
    }
    @Test fun retrievalFoldingPreservesCaseAndAccentsForModel() {
        assertEquals("费用 ＡＰＩ Café，",TextTools.embeddingText("費用 ＡＰＩ Café，"))
        assertEquals("费用 api café",TextTools.normalize("費用 ＡＰＩ Café"))
    }
    @Test fun sharedCharactersDoNotDoubleCountAWeakMeaningMatch() {
        val good=SemanticRanking.score(null,0.62f)
        val incidental=SemanticRanking.score(0.8,0.55f)
        assertTrue(good>incidental)
        assertTrue(good>SemanticRanking.score(3.0,null))
        assertTrue(SemanticRanking.score(12.0,null)>good)
    }
    @Test fun tokenizerKeepsCaseAccentsAndRemovesInvisibleControls() {
        val vocab=listOf("[PAD]","[UNK]","[CLS]","[SEP]","[MASK]","CAFÉ","café","A","B","AB","中").withIndex().associate { it.value to it.index }
        val tokenizer=WordPiece(vocab)
        assertArrayEquals(longArrayOf(2,5,6,1,9,10,4,3),tokenizer.encode("CAFÉ café CAFE A\u200bB中 [MASK]"))
    }
}
