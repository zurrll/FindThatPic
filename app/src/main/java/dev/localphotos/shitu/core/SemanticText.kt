package dev.localphotos.shitu.core

/** Keep nearby OCR lines together and prefer sentence/line boundaries over fixed character cuts. */
object SemanticText {
    private val boundaries=setOf('\n','。','！','？','!','?','；',';')
    fun passages(text:String,maxLength:Int=200,overlap:Int=40):List<String> {
        require(maxLength>=20 && overlap in 0 until maxLength/2)
        val source=text.trim()
        if(source.isEmpty()) return emptyList()
        val output=linkedSetOf<String>()
        var start=0
        while(start<source.length) {
            var end=minOf(start+maxLength,source.length)
            if(end<source.length) {
                // Do not split a UTF-16 surrogate pair.
                if(Character.isHighSurrogate(source[end-1])) end--
                val boundary=(end-1 downTo start+maxLength/2).firstOrNull { source[it] in boundaries || source[it].isWhitespace() }
                if(boundary!=null) end=boundary+1
            }
            val part=source.substring(start,end).trim()
            if(part.any { it.isLetterOrDigit() }) output+=part
            if(end==source.length) break
            var next=(end-overlap).coerceAtLeast(start+1)
            val boundary=(next until end).firstOrNull { source[it] in boundaries || source[it].isWhitespace() }
            if(boundary!=null) next=boundary+1
            if(next<source.length && Character.isLowSurrogate(source[next])) next++
            start=next
        }
        return output.toList()
    }
}
