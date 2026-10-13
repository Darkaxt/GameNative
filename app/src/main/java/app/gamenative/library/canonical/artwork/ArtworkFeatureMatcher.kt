package app.gamenative.library.canonical.artwork

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/** Bounded off-Main work. Mat buffers are released per call; OpenCV owns algorithm-handle cleanup. */
@Singleton
class ArtworkFeatureMatcher @Inject constructor() {
    fun extract(width: Int, height: Int, argb: IntArray, contentHash: String): ArtworkFeatures? = native {
        if (width !in 64..4096 || height !in 64..4096 || width.toLong() * height > 4_194_304 ||
            argb.size.toLong() != width.toLong() * height || !ArtworkFeatures.HASH.matches(contentHash)
        ) return@native null
        val opaque = argb.count { it ushr 24 >= 224 }
        if (opaque.toLong() * 100 < argb.size.toLong() * 95) return@native null
        val longSide = max(width, height)
        val workingSide = if (longSide < 600) min(longSide * 3, 600) else min(longSide, 768)
        val scale = workingSide.toDouble() / longSide
        val workingWidth = (width * scale).toInt()
        val workingHeight = (height * scale).toInt()
        if (min(workingWidth, workingHeight) < 64) return@native null
        val gray = Mat(height, width, CvType.CV_8UC1)
        val working = Mat()
        val mask = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val detector = ORB.create(ArtworkFeatures.MAX_FEATURES, 1.2f, 8, 15, 0, 2, ORB.HARRIS_SCORE, 31, 8)
        try {
            gray.put(0, 0, ByteArray(argb.size) { index ->
                val pixel = argb[index]
                ((77 * (pixel ushr 16 and 255) + 150 * (pixel ushr 8 and 255) + 29 * (pixel and 255)) ushr 8).toByte()
            })
            Imgproc.resize(gray, working, Size(workingWidth.toDouble(), workingHeight.toDouble()),
                0.0, 0.0, if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            detector.detectAndCompute(working, mask, keypoints, descriptors)
            val points = keypoints.toArray()
            if (points.size < MIN_INLIERS || descriptors.cols() != ArtworkFeatures.DESCRIPTOR_BYTES) return@native null
            val positions = FloatArray(points.size * 2)
            points.forEachIndexed { index, point ->
                positions[index * 2] = (point.pt.x / workingWidth).toFloat()
                positions[index * 2 + 1] = (point.pt.y / workingHeight).toFloat()
            }
            val bytes = ByteArray(points.size * ArtworkFeatures.DESCRIPTOR_BYTES)
            descriptors.get(0, 0, bytes)
            ArtworkFeatures(contentHash, workingWidth, workingHeight, positions, bytes).takeIf(ArtworkFeatures::isValid)
        } finally {
            listOf(gray, working, mask, keypoints, descriptors).forEach(Mat::release)
        }
    }

    fun compare(first: ArtworkFeatures, second: ArtworkFeatures): ArtworkMatch = native {
        if (!first.isValid() || !second.isValid()) return@native ArtworkMatch(false)
        // Stable orientation and RNG seed make persisted relationships independent of import order.
        val (left, right) = if (first.contentHash <= second.contentHash) first to second else second to first
        val leftDescriptors = Mat(left.count, ArtworkFeatures.DESCRIPTOR_BYTES, CvType.CV_8UC1)
        val rightDescriptors = Mat(right.count, ArtworkFeatures.DESCRIPTOR_BYTES, CvType.CV_8UC1)
        val forward = arrayListOf<MatOfDMatch>()
        val reverse = arrayListOf<MatOfDMatch>()
        val from = MatOfPoint2f()
        val to = MatOfPoint2f()
        val inlierMask = Mat()
        var transform: Mat? = null
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        try {
            leftDescriptors.put(0, 0, left.descriptors)
            rightDescriptors.put(0, 0, right.descriptors)
            matcher.knnMatch(leftDescriptors, rightDescriptors, forward, 2)
            matcher.knnMatch(rightDescriptors, leftDescriptors, reverse, 2)
            fun filtered(values: List<MatOfDMatch>) = values.mapNotNull { row ->
                val pair = row.toArray()
                pair.firstOrNull()?.takeIf { pair.size == 2 && it.distance <= MAX_HAMMING && it.distance < LOWE_RATIO * pair[1].distance }
            }
            val reverseBest = filtered(reverse).associate { it.queryIdx to it.trainIdx }
            val matches = filtered(forward).filter { reverseBest[it.trainIdx] == it.queryIdx }
                .sortedWith(compareBy({ it.queryIdx }, { it.trainIdx }))
            if (matches.size < MIN_INLIERS) return@native ArtworkMatch(false, matches = matches.size)
            fun point(features: ArtworkFeatures, index: Int) = Point(
                features.points[index * 2] * features.width.toDouble(),
                features.points[index * 2 + 1] * features.height.toDouble(),
            )
            val leftPoints = matches.map { point(left, it.queryIdx) }
            val rightPoints = matches.map { point(right, it.trainIdx) }
            from.fromList(leftPoints)
            to.fromList(rightPoints)
            Core.setRNGSeed(0x474e4152)
            transform = Calib3d.estimateAffinePartial2D(from, to, inlierMask, Calib3d.RANSAC, 3.0, 2_000L, 0.995, 10L)
            val affine = transform
            if (affine.empty() || affine.rows() != 2 || affine.cols() != 3) return@native ArtworkMatch(false, matches.size)
            val coefficients = DoubleArray(6)
            affine.get(0, 0, coefficients)
            if (coefficients.any { !it.isFinite() }) return@native ArtworkMatch(false, matches.size)
            val scale = hypot(coefficients[0], coefficients[3])
            val rotation = abs(atan2(coefficients[3], coefficients[0]) * 180 / Math.PI)
            val mask = ByteArray(matches.size)
            inlierMask.get(0, 0, mask)
            val selected = matches.indices.filter { mask[it].toInt() != 0 }.toMutableList()
            val pointModels = selected.associateWith { coefficients }.toMutableMap()
            val rejected = hashSetOf<Int>()
            // Store headers can reposition parts of the same original composition independently.
            // Permit at most three nearby, similarly scaled/rotated partial-affine models;
            // admission still requires distributed combined evidence, never one localized logo.
            if (selected.size >= MIN_INLIERS) repeat(2) { round ->
                val remaining = matches.indices.filter { it !in pointModels && it !in rejected }
                if (remaining.size < 12) return@repeat
                val localMask = Mat()
                var localTransform: Mat? = null
                try {
                    from.fromList(remaining.map(leftPoints::get))
                    to.fromList(remaining.map(rightPoints::get))
                    Core.setRNGSeed(0x474e4153 + round)
                    localTransform = Calib3d.estimateAffinePartial2D(from, to, localMask, Calib3d.RANSAC, 3.0, 2_000L, 0.995, 10L)
                    if (localTransform.empty()) return@repeat
                    val local = DoubleArray(6)
                    localTransform.get(0, 0, local)
                    val flags = ByteArray(remaining.size)
                    localMask.get(0, 0, flags)
                    val support = remaining.indices.filter { flags[it].toInt() != 0 }.map(remaining::get)
                    rejected += support
                    val localScale = hypot(local[0], local[3])
                    val localAngle = atan2(local[3], local[0]) * 180 / Math.PI
                    val baseAngle = atan2(coefficients[3], coefficients[0]) * 180 / Math.PI
                    if (support.size >= 12 && local.all(Double::isFinite) && localScale / scale in 0.75..1.33 &&
                        abs(localAngle - baseAngle) <= 10 && abs(local[2] - coefficients[2]) <= right.width * 0.35 &&
                        abs(local[5] - coefficients[5]) <= right.height * 0.35
                    ) {
                        support.forEach { index -> pointModels[index] = local }
                        selected += support
                    }
                } finally { localMask.release(); localTransform?.release() }
            }
            val ratio = selected.size.toDouble() / matches.size
            val leftSpatial = spatial(selected.map { Point(leftPoints[it].x / left.width, leftPoints[it].y / left.height) })
            val rightSpatial = spatial(selected.map { Point(rightPoints[it].x / right.width, rightPoints[it].y / right.height) })
            val coverage = min(leftSpatial.coverage, rightSpatial.coverage)
            val cells = min(leftSpatial.cells, rightSpatial.cells)
            val error = selected.map { index ->
                val a = leftPoints[index]
                val b = rightPoints[index]
                val model = pointModels.getValue(index)
                hypot(model[0] * a.x + model[1] * a.y + model[2] - b.x,
                    model[3] * a.x + model[4] * a.y + model[5] - b.y)
            }.average().takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
            val accepted = selected.size >= MIN_INLIERS && ratio >= MIN_INLIER_RATIO &&
                coverage >= MIN_COVERAGE && cells >= MIN_CELLS &&
                min(leftSpatial.xSpan, rightSpatial.xSpan) >= 0.45 && min(leftSpatial.ySpan, rightSpatial.ySpan) >= 0.40 &&
                scale in 0.15..6.0 && rotation <= 25 && error <= 2.5
            ArtworkMatch(accepted, matches.size, selected.size, ratio, coverage, cells, error)
        } finally {
            (forward + reverse).forEach(Mat::release)
            listOf(leftDescriptors, rightDescriptors, from, to, inlierMask).forEach(Mat::release)
            transform?.release()
        }
    } ?: ArtworkMatch(false, completed = false)

    private data class Spatial(val coverage: Double, val cells: Int, val xSpan: Double, val ySpan: Double)

    private fun spatial(points: List<Point>): Spatial {
        if (points.size < 3) return Spatial(0.0, 0, 0.0, 0.0)
        val sorted = points.distinctBy { it.x to it.y }.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun half(values: List<Point>): List<Point> {
            val result = arrayListOf<Point>()
            for (point in values) {
                while (result.size >= 2 && cross(result[result.lastIndex - 1], result.last(), point) <= 0) result.removeAt(result.lastIndex)
                result += point
            }
            return result.dropLast(1)
        }
        val hull = half(sorted) + half(sorted.reversed())
        val area = abs(hull.indices.sumOf { index ->
            val a = hull[index]
            val b = hull[(index + 1) % hull.size]
            a.x * b.y - b.x * a.y
        }) / 2
        val cells = points.map { (it.x * 4).toInt().coerceIn(0, 3) to (it.y * 4).toInt().coerceIn(0, 3) }.distinct().size
        return Spatial(area, cells, points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    }

    private fun <T> native(block: () -> T): T? = synchronized(NATIVE_LOCK) {
        if (!loaded) {
            try {
                // The official Android AAR ships libopencv_java4.so, not Core's desktop filename.
                System.loadLibrary("opencv_java4")
                if (Core.getVersionString() != "4.14.0") return@synchronized null
                Core.setNumThreads(1)
                loaded = true
            } catch (_: UnsatisfiedLinkError) { return@synchronized null }
        }
        try { block() } catch (_: CvException) { null }
            catch (_: IllegalArgumentException) { null }
    }

    companion object {
        const val MIN_INLIERS = 24
        const val LOWE_RATIO = 0.75
        const val MAX_HAMMING = 64
        const val MIN_INLIER_RATIO = 0.55
        const val MIN_COVERAGE = 0.18
        const val MIN_CELLS = 7
        private val NATIVE_LOCK = Any()
        private var loaded = false
    }
}
