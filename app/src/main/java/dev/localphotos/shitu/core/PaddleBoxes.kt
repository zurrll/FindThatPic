package dev.localphotos.shitu.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** DB probability map to oriented line crops, using component hulls and rectangular expansion. */
internal object PaddleBoxes {
    private data class Point(val x: Int, val y: Int)
    private data class Frame(val ux: Float, val uy: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
        val width get() = r - l
        val height get() = b - t
        fun corners(padding: Float): FloatArray {
            val x = floatArrayOf(l - padding, r + padding, r + padding, l - padding)
            val y = floatArrayOf(t - padding, t - padding, b + padding, b + padding)
            return FloatArray(8) { i ->
                val p = i / 2
                if (i % 2 == 0) x[p] * ux - y[p] * uy else x[p] * uy + y[p] * ux
            }
        }
    }

    fun detect(probability: FloatArray, width: Int, height: Int, checkpoint: () -> Unit): List<FloatArray> {
        val visited = BooleanArray(probability.size)
        val queue = IntArray(probability.size)
        val left = IntArray(height) { width }
        val right = IntArray(height) { -1 }
        val result = mutableListOf<FloatArray>()
        var candidates = 0
        for (start in probability.indices) {
            if (start % width == 0) checkpoint()
            if (visited[start] || probability[start] <= .20f) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            val top = start / width
            var bottom = top
            while (head < tail) {
                if (head % 4096 == 0) checkpoint()
                val index = queue[head++]
                val y = index / width
                val x = index % width
                bottom = maxOf(bottom, y)
                left[y] = minOf(left[y], x)
                right[y] = maxOf(right[y], x)
                for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val next = ny * width + nx
                        if (!visited[next] && probability[next] > .20f) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val points = ArrayList<Point>((bottom - top + 1) * 2)
            // The left and right endpoints of each row suffice for the convex hull.
            for (y in top..bottom) {
                points += Point(left[y], y)
                if (left[y] != right[y]) points += Point(right[y], y)
                left[y] = width
                right[y] = -1
            }
            if (tail < 6 || bottom - top < 2) continue
            if (++candidates > 3000) break
            val frame = enclosingFrame(hull(points)) ?: continue
            if (minOf(frame.width, frame.height) < 3f || score(frame, probability, width, height) < .40f) continue
            val padding = frame.width * frame.height * 1.4f / (2 * (frame.width + frame.height))
            result += frame.corners(padding)
        }
        return result
    }

    private fun hull(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        fun half(sequence: List<Point>): MutableList<Point> {
            val result = mutableListOf<Point>()
            for (p in sequence) {
                while (result.size >= 2) {
                    val a = result[result.lastIndex - 1]
                    val b = result.last()
                    if ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) > 0) break
                    result.removeAt(result.lastIndex)
                }
                result += p
            }
            return result
        }
        val lower = half(sorted)
        val upper = half(sorted.asReversed())
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun enclosingFrame(points: List<Point>): Frame? {
        if (points.size < 3) return null
        var best: Frame? = null
        var area = Float.POSITIVE_INFINITY
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            var dx = (b.x - a.x).toFloat()
            var dy = (b.y - a.y).toFloat()
            // Keep the local x axis pointing right, within 45° of horizontal.
            if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                val old = dx
                dx = dy
                dy = -old
            }
            if (dx < 0) {
                dx = -dx
                dy = -dy
            }
            val length = hypot(dx, dy)
            if (length == 0f) continue
            val ux = dx / length
            val uy = dy / length
            var left = Float.POSITIVE_INFINITY
            var top = left
            var right = Float.NEGATIVE_INFINITY
            var bottom = right
            for (point in points) {
                val x = point.x * ux + point.y * uy
                val y = point.y * ux - point.x * uy
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
            val size = (right - left) * (bottom - top)
            if (size < area) {
                area = size
                best = Frame(ux, uy, left, top, right, bottom)
            }
        }
        return best
    }

    private fun score(frame: Frame, probabilities: FloatArray, width: Int, height: Int): Float {
        val points = frame.corners(0f)
        val xs = listOf(points[0], points[2], points[4], points[6])
        val ys = listOf(points[1], points[3], points[5], points[7])
        val left = floor(xs.min()).toInt().coerceAtLeast(0)
        val right = ceil(xs.max()).toInt().coerceAtMost(width - 1)
        val top = floor(ys.min()).toInt().coerceAtLeast(0)
        val bottom = ceil(ys.max()).toInt().coerceAtMost(height - 1)
        var sum = 0f
        var count = 0
        for (y in top..bottom) for (x in left..right) {
            val u = x * frame.ux + y * frame.uy
            val v = y * frame.ux - x * frame.uy
            if (u >= frame.l && u <= frame.r && v >= frame.t && v <= frame.b) {
                sum += probabilities[y * width + x]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }
}
