package dev.localphotos.shitu.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrBox(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    val width get()=right-left
    val height get()=bottom-top
    val area get()=max(0f,width)*max(0f,height)
    fun shift(x:Float,y:Float)=OcrBox(left+x,top+y,right+x,bottom+y)
    fun union(b:OcrBox)=OcrBox(min(left,b.left),min(top,b.top),max(right,b.right),max(bottom,b.bottom))
    fun overlap(b:OcrBox):Float {
        val area=max(0f,min(right,b.right)-max(left,b.left))*max(0f,min(bottom,b.bottom)-max(top,b.top))
        return area/min(this.area,b.area).coerceAtLeast(1f)
    }
}

data class OcrLine(val text:String,val box:OcrBox,val tile:Int,val clipped:Boolean=false,val confidence:Float=0f)

/** Geometry is kept independent of Android so all eight EXIF transforms can be tested. */
object OcrLayout {
    fun searchVariants(lines:List<OcrLine>):String = lines.zipWithNext().mapNotNull { (a,b) ->
        val h=max(a.box.height,b.box.height).coerceAtLeast(1f)
        val gap=b.box.top-a.box.bottom
        val horizontal=min(a.box.right,b.box.right)-max(a.box.left,b.box.left)
        val sameColumn=horizontal>min(a.box.width,b.box.width)*0.3f || abs(a.box.left-b.box.left)<h
        if(sameColumn && gap in -h*0.2f..h*1.2f && min(a.box.height,b.box.height)>h*0.55f)
            TextTools.lineVariants(a.text+"\n"+b.text).takeIf { it.isNotBlank() } else null
    }.distinct().joinToString("\n")
    fun transform(box:OcrBox,width:Int,height:Int,orientation:Int,inverse:Boolean=false):OcrBox {
        fun point(x:Float,y:Float):Pair<Float,Float> = if(inverse) when(orientation) {
            2 -> width-x to y;3 -> width-x to height-y;4 -> x to height-y
            5 -> y to x;6 -> y to height-x;7 -> width-y to height-x;8 -> width-y to x
            else -> x to y
        } else when(orientation) {
            2 -> width-x to y;3 -> width-x to height-y;4 -> x to height-y
            5 -> y to x;6 -> height-y to x;7 -> height-y to width-x;8 -> y to width-x
            else -> x to y
        }
        val points=listOf(point(box.left,box.top),point(box.right,box.top),point(box.left,box.bottom),point(box.right,box.bottom))
        return OcrBox(points.minOf { it.first },points.minOf { it.second },points.maxOf { it.first },points.maxOf { it.second })
    }
    fun starts(length:Int,tile:Int=1800,overlap:Int=240):List<Int> {
        require(length>0 && tile>overlap && overlap>=0)
        if(length<=tile) return listOf(0)
        val result=mutableListOf(0)
        while(result.last()+tile<length) result+=min(result.last()+tile-overlap,length-tile)
        return result
    }
    fun merge(input:List<OcrLine>):List<OcrLine> {
        val kept=mutableListOf<OcrLine>()
        for(line in input.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })) {
            val key=TextTools.normalize(line.text).filterNot(Char::isWhitespace)
            // Compare spatial neighbours, not all lines in a long screenshot.
            val duplicate=(kept.lastIndex downTo max(0,kept.size-128)).firstOrNull { i ->
                val previous=kept[i]
                val other=TextTools.normalize(previous.text).filterNot(Char::isWhitespace)
                line.tile!=previous.tile && line.box.overlap(previous.box)>=0.6f &&
                    (key==other || key.contains(other) || other.contains(key) || TextTools.oneEditApart(key,other))
            }
            if(duplicate!=null) {
                val old=kept[duplicate]
                fun quality(l:OcrLine)=l.text.length+(if(l.clipped) -5f else 5f)+l.confidence*3
                if(quality(line)>quality(old)) kept[duplicate]=line
            } else kept+=line
        }
        // Repair adjacent fragments of a single row across tile boundaries.
        val rows=mutableListOf<OcrLine>()
        for(line in kept.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })) {
            val neighbour=(rows.lastIndex downTo max(0,rows.size-64)).firstOrNull { i ->
                val old=rows[i];val h=min(old.box.height,line.box.height).coerceAtLeast(1f)
                val vertical=min(old.box.bottom,line.box.bottom)-max(old.box.top,line.box.top)
                val gap=max(old.box.left,line.box.left)-min(old.box.right,line.box.right)
                old.tile!=line.tile && vertical/h>0.75f && gap in -h*0.1f..h*0.4f &&
                    max(old.box.height,line.box.height)/h<1.6f
            }
            if(neighbour==null) rows+=line else {
                val old=rows[neighbour]
                val (a,b)=if(old.box.left<=line.box.left) old to line else line to old
                val joined=a.text.trimEnd()+b.text.trimStart()
                rows[neighbour]=OcrLine(joined,a.box.union(b.box),-1,a.clipped && b.clipped,min(a.confidence,b.confidence))
            }
        }
        return rows.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
    }
}
