package dev.localphotos.shitu

import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphotos.shitu.core.OcrEngine
import dev.localphotos.shitu.core.OcrPlan
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LongImageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun horizontalAndVerticalLongImagesKeepNativePixelsAtEndsAndSeams()=runBlocking {
        // 3000x600 was previously below the whole-image size limit. Also check a wider strip.
        for((width,height) in listOf(3000 to 600,600 to 3000,6000 to 600)) {
            val file=File(context.cacheDir,"long-$width-$height.png")
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=38f }
            val plan=OcrPlan.base(width,height)
            if(width>height) {
                canvas.drawText("LEFT-START",35f,100f,paint)
                canvas.drawText("RIGHT-END",width-280f,400f,paint)
                canvas.drawText("seam discovery",plan.first().right-150f,250f,paint)
            } else {
                canvas.drawText("TOP-START",35f,100f,paint)
                canvas.drawText("BOTTOM-END",35f,height-80f,paint)
                canvas.drawText("seam discovery",35f,plan.first().bottom-8f,paint)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            try {
                OcrEngine(context).use { engine ->
                    val output=engine.recognize(Uri.fromFile(file));val debug=JSONObject(output.debug)
                    val text=output.text.lowercase()
                    assertTrue(text,text.contains(if(width>height) "left-start" else "top-start"))
                    assertTrue(text,text.contains(if(width>height) "right-end" else "bottom-end"))
                    assertTrue(text,text.contains("discovery"))
                    assertTrue(debug.getInt("baseCount")>1)
                    val tiles=debug.getJSONArray("tiles")
                    for(i in 0 until tiles.length()) assertEquals(1,tiles.getJSONObject(i).getInt("sampleSize"))
                }
            } finally { file.delete() }
        }
    }
}
