package dev.localphotos.shitu.core

/** BERT tokenizer configuration bundled with bge-small-zh-v1.5: cased, no accent stripping. */
class WordPiece(private val vocab: Map<String,Int>,private val lowercase:Boolean=false) {
    fun encode(text:String, maxLength:Int=256): LongArray {
        require(maxLength>=2)
        val ids=mutableListOf(vocab.getValue("[CLS]").toLong())
        val words=mutableListOf<String>()
        val word=StringBuilder()
        fun flush() { if(word.isNotEmpty()) { words+=word.toString();word.setLength(0) } }
        fun basic(value:String) {
            val normalized=if(lowercase) java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFD)
                .codePoints().filter { Character.getType(it)!=Character.NON_SPACING_MARK.toInt() }.toArray().let { String(it,0,it.size) } else value
            normalized.codePoints().toArray().forEach { cp ->
            val type=Character.getType(cp)
            val han=cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2a6df ||
                cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f || cp in 0x2b820..0x2ceaf ||
                cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
            val punctuation=type in setOf(Character.CONNECTOR_PUNCTUATION.toInt(),Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),Character.END_PUNCTUATION.toInt(),Character.OTHER_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),Character.FINAL_QUOTE_PUNCTUATION.toInt()) ||
                (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126)
            when {
                Character.isWhitespace(cp) || type==Character.SPACE_SEPARATOR.toInt() -> flush()
                cp==0 || cp==0xfffd || type==Character.CONTROL.toInt() || type==Character.FORMAT.toInt() -> Unit
                han || punctuation -> { flush();words+=String(Character.toChars(cp)) }
                else -> word.appendCodePoint(cp)
            }
        };flush() }
        var offset=0
        // Literal special tokens follow the upstream tokenizer's added-token handling.
        Regex("\\[(?:PAD|UNK|CLS|SEP|MASK)]").findAll(text).forEach { match ->
            basic(text.substring(offset,match.range.first));words+=match.value;offset=match.range.last+1
        }
        basic(text.substring(offset))
        flush()
        for(token in words) {
            if(ids.size>=maxLength-1) break
            val pieces=mutableListOf<Int>()
            var start=0
            if(token.codePointCount(0,token.length)>100) pieces+=vocab.getValue("[UNK]") else while(start<token.length) {
                var end=token.length
                var found:Int?=null
                while(end>start) {
                    val piece=(if(start==0) "" else "##")+token.substring(start,end)
                    found=vocab[piece]
                    if(found!=null) break
                    end=token.offsetByCodePoints(end,-1)
                }
                if(found==null) { pieces.clear();pieces+=vocab.getValue("[UNK]");break }
                pieces+=found;start=end
            }
            ids+=pieces.take(maxLength-1-ids.size).map(Int::toLong)
        }
        ids+=vocab.getValue("[SEP]").toLong()
        return ids.toLongArray()
    }
}
