package dev.localphotos.shitu.core

import kotlin.math.ceil
import kotlin.math.roundToInt

data class OcrRegion(val left:Int,val top:Int,val right:Int,val bottom:Int,
    val sampleSize:Int=1,val kind:String="grid",val reason:String="原图分块") {
    val box get()=OcrBox(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat())
}

/** Pixel geometry and a bounded refinement budget; no camera/app/content-specific rules. */
object OcrPlan {
    const val MAX_REFINEMENTS=2
    fun base(width:Int,height:Int):List<OcrRegion> {
        require(width>0 && height>0)
        val longImage=maxOf(width,height).toLong()>=minOf(width,height).toLong()*3
        if(!longImage || maxOf(width,height)<=1800) {
            var sample=1
            fun dimension(n:Int)=(n.toLong()+sample-1)/sample
            while(dimension(width)*dimension(height)>4_000_000 || maxOf(dimension(width),dimension(height))>3200) sample*=2
            return listOf(OcrRegion(0,0,width,height,sample,"overview",if(sample==1) "尺寸受控，整图识别" else "常规比例大图，采样整图识别"))
        }
        val xs=axis(width);val ys=axis(height)
        require(xs.size.toLong()*ys.size<=256) { "image_too_large" }
        return ys.flatMap { y -> xs.map { x -> OcrRegion(x.first,y.first,x.second,y.second,reason="长图保留原始文字大小，均匀分块") } }
    }
    /** Equal coverage with approximately 240 px overlap, avoiding nearly identical tail tiles. */
    fun axis(length:Int,maxTile:Int=1800,overlap:Int=240):List<Pair<Int,Int>> {
        require(length>0 && maxTile>overlap && overlap>=0)
        if(length<=maxTile) return listOf(0 to length)
        val count=1+ceil((length-maxTile).toDouble()/(maxTile-overlap)).toInt()
        require(count<=256) { "image_too_large" }
        val tile=ceil((length.toLong()+overlap.toLong()*(count-1)).toDouble()/count).toInt()
        return (0 until count).map { i ->
            val start=(i.toDouble()*(length-tile)/(count-1)).roundToInt()
            start to start+tile
        }
    }
    fun refinements(lines:List<OcrLine>,base:List<OcrRegion>,width:Int,height:Int,orientation:Int):List<OcrRegion> {
        val overview=base.singleOrNull()?.takeIf { it.kind=="overview" }
        if(overview?.sampleSize==1) return emptyList()
        val candidates=lines.filter { line ->
            val useful=line.text.count { it.isLetterOrDigit() }>=3 && (line.confidence==0f || line.confidence>=0.45f)
            useful && if(overview!=null) line.box.height/overview.sampleSize in 6f..18f else line.clipped
        }.sortedByDescending { it.text.length }
        val result=mutableListOf<OcrRegion>()
        for(line in candidates) {
            val raw=OcrLayout.transform(line.box,width,height,orientation,inverse=true)
            val w=minOf(width,1800);val h=minOf(height,900)
            val left=(((raw.left+raw.right)/2).toInt()-w/2).coerceIn(0,width-w)
            val top=(((raw.top+raw.bottom)/2).toInt()-h/2).coerceIn(0,height-h)
            val region=OcrRegion(left,top,left+w,top+h,kind=if(overview!=null) "detail" else "seam",
                reason=if(overview!=null) "整图检测到偏小文字，局部按原分辨率补看" else "仍有文字靠近内部分块边界，移位补看")
            // A crop fully covered by an existing native tile adds no new seam context.
            if(base.any { it.sampleSize==1 && it.left<=left && it.top<=top && it.right>=left+w && it.bottom>=top+h }) continue
            if(result.any { it.box.overlap(region.box)>=0.5f }) continue
            result+=region
            if(result.size==MAX_REFINEMENTS) break
        }
        return result
    }
}
