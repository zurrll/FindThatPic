package dev.localphotos.shitu.core

data class DescriptionFeatures(val embedding:FloatArray,val width:Int,val height:Int) {
    companion object {
        const val DIMENSIONS=1024
        const val BYTES=DIMENSIONS*4
        const val MODEL_NAME="Chinese-CLIP RN50"
        const val MODEL_LABEL="$MODEL_NAME · INT8"
        const val VERSION="cnclip-rn50-717ba21:q8:fit224-bicubic:bert52-lower:1024-v1"
    }
}

/** Pillow-compatible separable bicubic filtering. The original is never changed. */
object DescriptionPixels {
    private data class Taps(val start:Int,val weights:DoubleArray)
    private fun taps(source:Int,target:Int):List<Taps> {
        val scale=source.toDouble()/target;val filter=maxOf(1.0,scale)
        fun cubic(x:Double):Double {
            val a=kotlin.math.abs(x)
            return when { a<1 -> ((1.5*a-2.5)*a)*a+1; a<2 -> ((-0.5*a+2.5)*a-4)*a+2;else->0.0 }
        }
        return List(target) { out ->
            val center=(out+0.5)*scale
            val start=maxOf(0,(center-2*filter+0.5).toInt())
            val end=minOf(source,(center+2*filter+0.5).toInt())
            val weights=DoubleArray(end-start) { cubic((start+it-center+0.5)/filter) };val sum=weights.sum()
            for(i in weights.indices) weights[i]/=sum
            Taps(start,weights)
        }
    }
    fun fit(source:IntArray,width:Int,height:Int):IntArray {
        require(width>0 && height>0 && source.size==width*height)
        val scale=minOf(224.0/width,224.0/height)
        val w=kotlin.math.round(width*scale).toInt().coerceIn(1,224)
        val h=kotlin.math.round(height*scale).toInt().coerceIn(1,224)
        val horizontal=IntArray(w*height);val xs=taps(width,w);val ys=taps(height,h)
        fun pack(r:Double,g:Double,b:Double)=0xff000000.toInt() or (((r+0.5).toInt().coerceIn(0,255)) shl 16) or (((g+0.5).toInt().coerceIn(0,255)) shl 8) or (b+0.5).toInt().coerceIn(0,255)
        for(y in 0 until height) for(x in 0 until w) {
            var r=0.0;var g=0.0;var b=0.0;val t=xs[x]
            for(i in t.weights.indices) {
                val p=source[y*width+t.start+i];val a=p ushr 24;val k=t.weights[i]
                r+=(((p ushr 16) and 255)*a+255*(255-a))/255.0*k
                g+=(((p ushr 8) and 255)*a+255*(255-a))/255.0*k
                b+=((p and 255)*a+255*(255-a))/255.0*k
            }
            horizontal[y*w+x]=pack(r,g,b)
        }
        val output=IntArray(224*224) { 0xff7b7568.toInt() };val left=(224-w)/2;val top=(224-h)/2
        for(y in 0 until h) for(x in 0 until w) {
            var r=0.0;var g=0.0;var b=0.0;val t=ys[y]
            for(i in t.weights.indices) {
                val p=horizontal[(t.start+i)*w+x];val k=t.weights[i]
                r+=((p ushr 16) and 255)*k;g+=((p ushr 8) and 255)*k;b+=(p and 255)*k
            }
            output[(y+top)*224+x+left]=pack(r,g,b)
        }
        return output
    }
}
