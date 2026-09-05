package dev.cabezudo.treasurehunt.aruco.pose

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

object IppeSquareObjectPoints {
    fun create(sideMillimeters: Double): List<PhysicalPosePoint> {
        require(sideMillimeters.isFinite() && sideMillimeters > 0.0)
        val half = sideMillimeters / 2.0
        // Orden obligatorio de SOLVEPNP_IPPE_SQUARE y correspondiente a ArUco:
        // superior izquierda, superior derecha, inferior derecha, inferior izquierda.
        return listOf(
            PhysicalPosePoint(-half, half, 0.0),
            PhysicalPosePoint(half, half, 0.0),
            PhysicalPosePoint(half, -half, 0.0),
            PhysicalPosePoint(-half, -half, 0.0),
        )
    }

    fun axes(sideMillimeters: Double): List<PhysicalPosePoint> {
        val length = sideMillimeters * 0.5
        return listOf(
            PhysicalPosePoint(0.0, 0.0, 0.0),
            PhysicalPosePoint(length, 0.0, 0.0),
            PhysicalPosePoint(0.0, length, 0.0),
            PhysicalPosePoint(0.0, 0.0, length),
        )
    }
}

data class PhysicalPosePoint(val x: Double, val y: Double, val z: Double)

class PhysicalMarkerSizeValidator(
    private val maximumRelativeSideDifference: Double = 0.02,
) {
    fun rejection(size: PhysicalMarkerSize?): ArucoPoseStatus? {
        if (size == null || !size.confirmed) return ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED
        if (!size.widthMillimeters.isFinite() || !size.heightMillimeters.isFinite() ||
            size.widthMillimeters <= 0.0 || size.heightMillimeters <= 0.0
        ) return ArucoPoseStatus.REJECTED
        val relativeDifference = abs(size.widthMillimeters - size.heightMillimeters) /
            size.meanSideMillimeters
        return if (relativeDifference > maximumRelativeSideDifference) {
            ArucoPoseStatus.PHYSICAL_MARKER_DEFORMED
        } else null
    }
}

object PoseGeometryValidator {
    fun hasValidIppeCornerOrder(points: List<RecognitionPoint>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        if (signedArea(points) <= 1e-6) return false
        val signs = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % 4]
            val c = points[(index + 2) % 4]
            cross(a, b, c)
        }
        return signs.all { it > 1e-9 }
    }

    fun hasNonDegenerateProjection(points: List<RecognitionPoint>): Boolean =
        hasValidIppeCornerOrder(points) && points.indices.all { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            hypot(b.x - a.x, b.y - a.y) > 1e-6
        }

    private fun signedArea(points: List<RecognitionPoint>): Double = points.indices.sumOf { index ->
        val a = points[index]
        val b = points[(index + 1) % points.size]
        a.x * b.y - b.x * a.y
    } / 2.0

    private fun cross(a: RecognitionPoint, b: RecognitionPoint, c: RecognitionPoint): Double =
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
}

object PoseResultValidator {
    fun rejection(
        rotationVector: List<Double>,
        translation: List<Double>,
        rotationMatrix: List<Double>,
        reprojectionRms: Double,
        projectedCorners: List<RecognitionPoint>,
    ): ArucoPoseRejectionReason? {
        if (rotationVector.size != 3 || translation.size != 3 || rotationMatrix.size != 9) {
            return ArucoPoseRejectionReason.INVALID_VECTOR_DIMENSIONS
        }
        if ((rotationVector + translation + rotationMatrix).any { !it.isFinite() }) {
            return ArucoPoseRejectionReason.NON_FINITE_RESULT
        }
        if (translation[2] <= 0.0) return ArucoPoseRejectionReason.NON_POSITIVE_Z
        if (!validRotationMatrix(rotationMatrix)) {
            return ArucoPoseRejectionReason.INVALID_ROTATION_MATRIX
        }
        if (!reprojectionRms.isFinite()) return ArucoPoseRejectionReason.NON_FINITE_REPROJECTION
        if (!PoseGeometryValidator.hasNonDegenerateProjection(projectedCorners)) {
            return ArucoPoseRejectionReason.DEGENERATE_PROJECTION
        }
        return null
    }

    fun eulerDegreesXyz(rotation: List<Double>): List<Double> {
        require(rotation.size == 9)
        val sy = sqrt(rotation[0] * rotation[0] + rotation[3] * rotation[3])
        val singular = sy < 1e-9
        val x: Double
        val y: Double
        val z: Double
        if (!singular) {
            x = atan2(rotation[7], rotation[8])
            y = atan2(-rotation[6], sy)
            z = atan2(rotation[3], rotation[0])
        } else {
            x = atan2(-rotation[5], rotation[4])
            y = atan2(-rotation[6], sy)
            z = 0.0
        }
        val degrees = 180.0 / Math.PI
        return listOf(x * degrees, y * degrees, z * degrees)
    }

    private fun validRotationMatrix(r: List<Double>): Boolean {
        val rows = listOf(r.slice(0..2), r.slice(3..5), r.slice(6..8))
        for (i in 0..2) {
            val norm = rows[i].sumOf { it * it }
            if (abs(norm - 1.0) > 1e-5) return false
            for (j in i + 1..2) {
                val dot = (0..2).sumOf { rows[i][it] * rows[j][it] }
                if (abs(dot) > 1e-5) return false
            }
        }
        val determinant =
            r[0] * (r[4] * r[8] - r[5] * r[7]) -
                r[1] * (r[3] * r[8] - r[5] * r[6]) +
                r[2] * (r[3] * r[7] - r[4] * r[6])
        return abs(determinant - 1.0) <= 1e-5
    }
}
