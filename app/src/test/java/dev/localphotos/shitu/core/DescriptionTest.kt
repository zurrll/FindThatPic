package dev.localphotos.shitu.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class DescriptionTest {
    @Test fun bicubicPixelsMatchIndependentPillowReferences() {
        DataInputStream(javaClass.classLoader!!.getResourceAsStream("cnclip-resize.bin")!!).use { input ->
            repeat(input.readInt()) {
                val width=input.readInt();val height=input.readInt();val source=IntArray(width*height) { input.readInt() }
                val expected=ByteArray(224*224*3);input.readFully(expected)
                val actual=DescriptionPixels.fit(source,width,height);var maximum=0
                for(i in actual.indices) for(c in 0..2) maximum=maxOf(maximum,abs(((actual[i] ushr (16-8*c)) and 255)-(expected[i*3+c].toInt() and 255)))
                assertTrue("$width x $height maximum RGB difference $maximum",maximum<=2)
            }
        }
    }
    @Test fun modelSwitchesSerializeConcurrentInferenceAndReleasePreviousResources()=runBlocking<Unit> {
        val active=AtomicInteger();val peak=AtomicInteger();val switches=mutableListOf<ModelKind?>()
        val gate=ModelGate { switches.add(it) }
        coroutineScope {
            repeat(24) { i -> launch(Dispatchers.Default) {
                gate.run(ModelKind.entries[i%ModelKind.entries.size]) {
                    val count=active.incrementAndGet();peak.updateAndGet { maxOf(it,count) };delay(3);active.decrementAndGet()
                }
            } }
        }
        assertEquals(1,peak.get());assertTrue(switches.isNotEmpty())
        gate.release();assertNull(switches.last())
        try { gate.run(ModelKind.TEXT) { throw CancellationException() } } catch(_:CancellationException) { }
        assertEquals(7,gate.run(ModelKind.DESCRIPTION_TEXT) { 7 })
    }
}
