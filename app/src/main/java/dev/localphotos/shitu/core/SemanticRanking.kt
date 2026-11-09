package dev.localphotos.shitu.core

object SemanticRanking {
    // Kept from the previous release; these are retrieval heuristics, not probabilities.
    fun cutoff(best:Float)=maxOf(0.40f,best-0.12f)
    fun score(lexical:Double?,similarity:Float?):Double = when {
        lexical!=null && lexical>=10.0 -> 3.0+(similarity?:0f)
        similarity!=null -> 1.0+similarity+0.04*(lexical?.coerceIn(0.0,1.0)?:0.0)
        else -> 0.1+0.03*(lexical?.coerceIn(0.0,3.0)?:0.0)
    }
}
