package dev.localphotos.shitu.core

import kotlin.math.*

/** A different vector space from OCR text embeddings. Never compare the two. */
data class VisualFeatures(val embedding:FloatArray,val hash:Long,val layout:ByteArray,val width:Int,val height:Int) {
    companion object {
        const val VERSION="mnv4-3ba07f1:preclassifier1280:fit224-rgb-v1:phash32-layout16"
        const val DIMENSIONS=1280
    }
}
data class VisualScore(val cosine:Float,val appearance:Float,val hashDistance:Int,val close:Boolean) {
    val rank:Float get()=if(close) 2f+cosine else cosine*0.85f+appearance*0.15f
}
object VisualMath {
    private val basis=Array(8) { u -> DoubleArray(32) { x -> cos((2*x+1)*u*PI/64) } }
    /** 32x32 grayscale DCT. The DC coefficient is deliberately excluded. */
    fun hash(gray:FloatArray):Long {
        require(gray.size==1024)
        val coefficients=DoubleArray(64) { k ->
            val u=k%8;val v=k/8
            var sum=0.0
            for(y in 0..31) for(x in 0..31) sum+=gray[y*32+x]*basis[u][x]*basis[v][y]
            sum
        }
        val median=coefficients.drop(1).sorted()[31]
        var bits=0L
        for(i in 1..63) if(coefficients[i]>median) bits=bits or (1L shl i)
        return bits
    }
    fun score(a:VisualFeatures,b:VisualFeatures):VisualScore {
        require(a.embedding.size==b.embedding.size && a.layout.size==768 && b.layout.size==768)
        val cosine=TextTools.dot(a.embedding,b.embedding).coerceIn(-1f,1f)
        var difference=0L
        for(i in a.layout.indices) difference+=abs((a.layout[i].toInt() and 255)-(b.layout[i].toInt() and 255))
        val appearance=(1f-difference.toFloat()/(768*255)).coerceIn(0f,1f)
        val distance=java.lang.Long.bitCount(a.hash xor b.hash)
        val ratio=abs(ln((a.width.toDouble()/a.height)/(b.width.toDouble()/b.height)))
        // A candidate for close appearance, never proof that two files contain identical information.
        return VisualScore(cosine,appearance,distance,distance<=6 && appearance>=0.94f && ratio<=0.04 && cosine>=0.60f)
    }
}
