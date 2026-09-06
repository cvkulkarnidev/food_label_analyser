package com.paddle.ocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class RectifiedBitmapResult(
    val bitmap: Bitmap,
    val perspectiveCorrected: Boolean,
    val deskewed: Boolean,
    val confidence: Double,
)

/** Conservative document rectification. It returns the source untouched when geometry is weak. */
object DocumentRectifier {
    private const val MAX_WORKING_DIMENSION = 1_200.0
    private const val MIN_QUAD_AREA_RATIO = 0.16
    private const val MIN_QUAD_CONFIDENCE = 0.58

    fun rectify(context: Context, source: Bitmap): RectifiedBitmapResult {
        if (!OpenCVUtils.init(context.applicationContext)) {
            return RectifiedBitmapResult(source, false, false, 0.0)
        }

        val input = Mat()
        val working = Mat()
        val gray = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(source, input)
            val scale = min(1.0, MAX_WORKING_DIMENSION / max(input.cols(), input.rows()).toDouble())
            if (scale < 1.0) {
                Imgproc.resize(input, working, Size(input.cols() * scale, input.rows() * scale))
            } else {
                input.copyTo(working)
            }
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 55.0, 165.0)
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = working.cols().toDouble() * working.rows()
            val candidate = contours.asSequence()
                .filter { Imgproc.contourArea(it) / imageArea >= MIN_QUAD_AREA_RATIO }
                .mapNotNull { contour -> quadCandidate(contour, imageArea) }
                .maxByOrNull(QuadCandidate::confidence)

            if (candidate != null && candidate.confidence >= MIN_QUAD_CONFIDENCE) {
                val points = orderCorners(candidate.points).map { Point(it.x / scale, it.y / scale) }
                warp(source, input, points)?.let { corrected ->
                    return RectifiedBitmapResult(
                        bitmap = corrected,
                        perspectiveCorrected = true,
                        deskewed = true,
                        confidence = candidate.confidence,
                    )
                }
            }

            val angle = dominantHorizontalAngle(closed)
            if (angle != null && abs(angle) in 0.8..14.0) {
                val matrix = Matrix().apply { postRotate((-angle).toFloat()) }
                val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                return RectifiedBitmapResult(rotated, false, true, 0.48)
            }
            return RectifiedBitmapResult(source, false, false, candidate?.confidence ?: 0.0)
        } catch (_: Throwable) {
            return RectifiedBitmapResult(source, false, false, 0.0)
        } finally {
            contours.forEach { it.release() }
            kernel.release()
            hierarchy.release()
            closed.release()
            edges.release()
            gray.release()
            working.release()
            input.release()
        }
    }

    private fun quadCandidate(contour: MatOfPoint, imageArea: Double): QuadCandidate? {
        val curve = MatOfPoint2f(*contour.toArray())
        val approximation = MatOfPoint2f()
        return try {
            val perimeter = Imgproc.arcLength(curve, true)
            Imgproc.approxPolyDP(curve, approximation, perimeter * 0.022, true)
            val points = approximation.toArray()
            if (points.size != 4) return null
            val polygon = MatOfPoint(*points)
            try {
                if (!Imgproc.isContourConvex(polygon)) return null
                val areaRatio = (abs(Imgproc.contourArea(polygon)) / imageArea).coerceIn(0.0, 1.0)
                val angleQuality = rightAngleQuality(points)
                QuadCandidate(points, (0.72 * areaRatio + 0.28 * angleQuality).coerceIn(0.0, 1.0))
            } finally {
                polygon.release()
            }
        } finally {
            approximation.release()
            curve.release()
        }
    }

    private fun rightAngleQuality(points: Array<Point>): Double {
        val ordered = orderCorners(points)
        val qualities = ordered.indices.map { index ->
            val previous = ordered[(index + 3) % 4]
            val center = ordered[index]
            val next = ordered[(index + 1) % 4]
            val ax = previous.x - center.x
            val ay = previous.y - center.y
            val bx = next.x - center.x
            val by = next.y - center.y
            val denominator = sqrt((ax.pow(2) + ay.pow(2)) * (bx.pow(2) + by.pow(2))).coerceAtLeast(1e-6)
            val degrees = Math.toDegrees(acos(((ax * bx + ay * by) / denominator).coerceIn(-1.0, 1.0)))
            (1.0 - abs(degrees - 90.0) / 45.0).coerceIn(0.0, 1.0)
        }
        return qualities.average()
    }

    private fun orderCorners(points: Array<Point>): List<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.maxBy { it.x - it.y }
        val bottomLeft = points.minBy { it.x - it.y }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun warp(source: Bitmap, input: Mat, points: List<Point>): Bitmap? {
        val width = max(distance(points[0], points[1]), distance(points[3], points[2])).toInt()
        val height = max(distance(points[0], points[3]), distance(points[1], points[2])).toInt()
        if (width < 240 || height < 240 || width.toLong() * height > 24_000_000L) return null

        val sourcePoints = MatOfPoint2f(*points.toTypedArray())
        val destinationPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((width - 1).toDouble(), 0.0),
            Point((width - 1).toDouble(), (height - 1).toDouble()),
            Point(0.0, (height - 1).toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(sourcePoints, destinationPoints)
        val output = Mat(height, width, CvType.CV_8UC4)
        return try {
            Imgproc.warpPerspective(
                input,
                output,
                transform,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE,
            )
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally {
            output.release()
            transform.release()
            destinationPoints.release()
            sourcePoints.release()
        }
    }

    private fun dominantHorizontalAngle(edges: Mat): Double? {
        val lines = Mat()
        return try {
            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,
                Math.PI / 180.0,
                55,
                edges.cols() * 0.24,
                22.0,
            )
            val angles = buildList {
                for (row in 0 until lines.rows()) {
                    val line = lines.get(row, 0) ?: continue
                    val angle = Math.toDegrees(kotlin.math.atan2(line[3] - line[1], line[2] - line[0]))
                    if (abs(angle) <= 20.0) add(angle)
                }
            }.sorted()
            angles.takeIf { it.size >= 3 }?.let { it[it.size / 2] }
        } finally {
            lines.release()
        }
    }

    private fun distance(first: Point, second: Point): Double =
        sqrt((first.x - second.x).pow(2) + (first.y - second.y).pow(2))

    private data class QuadCandidate(
        val points: Array<Point>,
        val confidence: Double,
    )
}
