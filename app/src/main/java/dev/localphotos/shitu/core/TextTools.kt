package dev.localphotos.shitu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

object TextTools {
    private val simplified by lazy {
        checkNotNull(TextTools::class.java.getResourceAsStream("/opencc/TSCharacters.txt")) { "Missing character dictionary" }
            .bufferedReader().useLines { lines -> lines.filter { it.contains('\t') }.associate { line ->
                val fields=line.split('\t',limit=2)
                fields[0].codePointAt(0) to fields[1].substringBefore(' ')
            } }
    }
    // Character-level retrieval folding only. Stored OCR and original images stay intact.
    fun normalize(text: String): String {
        return embeddingText(Normalizer.normalize(text, Normalizer.Form.NFKC)).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
    // Unlike keyword folding, preserve punctuation/width, case and accents for the model.
    fun embeddingText(text:String):String {
        return buildString { text.codePoints().toArray().forEach { cp ->
            val replacement=simplified[cp]
            if(replacement==null) appendCodePoint(cp) else append(replacement)
        } }.trim()
    }

    // Explicit CJK unigrams/bigrams avoid the default SQLite tokenizer treating
    // an entire Chinese sentence as one word. ASCII numbers/codes stay intact.
    fun tokens(text: String): Set<String> {
        val output = linkedSetOf<String>()
        val s = normalize(text)
        Regex("[\\p{IsHan}]+|[a-z0-9]+(?:[-_.][a-z0-9]+)*").findAll(s).forEach { match ->
            val word = match.value
            if (word.first().code in 0x3400..0x9fff) {
                word.forEach { output.add(it.toString()) }
                word.windowed(2).forEach(output::add)
            } else output.add(word)
        }
        return output
    }
    fun matchQuery(query: String):String {
        val all=tokens(query)
        val normal=all.filter { it.length>1 }.ifEmpty { all.toList() }
        val grams=if(normalize(query).matches(Regex("[a-z]{5,32}"))) latinGrams(normalize(query)) else emptySet()
        return (normal+grams).take(64).joinToString(" OR ") { "\"$it\"" }
    }
    private fun latinGrams(text:String)=Regex("[a-z]{3,32}").findAll(normalize(text))
        .flatMap { it.value.windowed(3).asSequence() }.map { "zzgram$it" }.toSet()
    /** These are explicitly weak alternatives; the original text is never overwritten. */
    fun lineVariants(text:String):String {
        val lines=text.lines().map(::normalize).filter(String::isNotBlank)
        val variants=mutableListOf<String>()
        for(i in 0 until lines.lastIndex) {
            val a=lines[i];val b=lines[i+1]
            val left=Regex("[a-z]{2,24}-?$").find(a)?.value
            val right=Regex("^[a-z]{2,24}").find(b)?.value
            if(left!=null && right!=null && left.removeSuffix("-").length+right.length<=32)
                variants+=left.removeSuffix("-")+right
            if(a.lastOrNull()?.let { Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN }==true &&
                b.firstOrNull()?.let { Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN }==true)
                variants+=a.takeLast(4)+b.take(4)
        }
        return variants.distinct().joinToString("\n")
    }
    fun indexTokens(text:String,variants:String):Set<String> = tokens(text)+tokens(variants)+latinGrams(text)+latinGrams(variants)
    fun tolerantScore(query:String,text:String,variants:String):Double {
        val original=lexicalScore(query,text)
        if(original>=10) return original
        val q=normalize(query)
        if(q.length>=2 && variants.lineSequence().any { normalize(it).contains(q) }) return maxOf(original,3.0)
        if(q.matches(Regex("[a-z]{5,32}")) && Regex("[a-z]{4,33}").findAll(normalize(text)+"\n"+variants)
                .any { oneEditApart(q,it.value) }) return maxOf(original,2.0)
        return original
    }
    fun oneEditApart(a:String,b:String):Boolean {
        if(kotlin.math.abs(a.length-b.length)>1 || minOf(a.length,b.length)<4) return false
        var i=0;var j=0;var edits=0
        while(i<a.length && j<b.length) {
            if(a[i]==b[j]) { i++;j++;continue }
            if(++edits>1) return false
            if(a.length>=b.length) i++
            if(b.length>=a.length) j++
        }
        return edits+(a.length-i)+(b.length-j)<=1
    }
    fun lexicalScore(query: String, text: String): Double {
        val q = normalize(query)
        val t = normalize(text)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (t.contains(q)) return 10.0 + q.length.coerceAtMost(100) / 100.0
        val terms = tokens(q).filter { it.length > 1 }.ifEmpty { tokens(q).toList() }
        if (terms.isEmpty()) return 0.0
        val targetTokens=tokens(t)
        val coverage = terms.count { it in targetTokens }.toDouble() / terms.size
        return if (coverage >= 0.45) coverage else 0.0
    }
    fun chunks(text: String, maxLength: Int = 200, overlap: Int = 30): List<String> {
        require(maxLength > overlap && overlap >= 0)
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < s.length) {
            val end = (start + maxLength).coerceAtMost(s.length)
            parts += s.substring(start, end)
            if (end == s.length) break
            start = end - overlap
        }
        return parts
    }
    fun snippet(text: String, query: String, maxLength: Int = 100): String {
        val s = text.replace('\n', ' ')
        val q = normalize(query)
        val at = normalize(s).indexOf(q).coerceAtLeast(0)
        val start = (at - 20).coerceAtLeast(0).coerceAtMost(s.length)
        return (if (start > 0) "…" else "") + s.substring(start, (start + maxLength).coerceAtMost(s.length))
    }
    fun pack(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach(::putFloat) }.array()
    fun unpack(bytes: ByteArray): FloatArray = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer().let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }
    fun unit(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        if (norm > 0) for (i in vector.indices) vector[i] /= norm
        return vector
    }
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
