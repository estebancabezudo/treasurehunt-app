package dev.cabezudo.treasurehunt.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadrilateralValidatorTest {
    private val validator = QuadrilateralValidator(ImageRecognitionConfig())

    @Test
    fun acceptsOrderedConvexQuadrilateral() {
        val result = validator.validate(
            listOf(point(80, 60), point(540, 90), point(510, 420), point(95, 400)),
            frameWidth = 640,
            frameHeight = 480,
        )

        assertTrue(result.valid)
        assertEquals(null, result.reason)
    }

    @Test
    fun rejectsSelfIntersectingQuadrilateral() {
        val result = validator.validate(
            listOf(point(80, 60), point(530, 410), point(520, 70), point(90, 400)),
            640,
            480,
        )

        assertFalse(result.valid)
        assertEquals(RecognitionRejectionReason.QUADRILATERAL_SELF_INTERSECTING, result.reason)
    }

    @Test
    fun rejectsAreaThatIsTooSmall() {
        val result = validator.validate(
            listOf(point(10, 10), point(25, 10), point(25, 20), point(10, 20)),
            640,
            480,
        )

        assertFalse(result.valid)
        assertEquals(RecognitionRejectionReason.QUADRILATERAL_TOO_SMALL, result.reason)
    }

    @Test
    fun rejectsAreaThatIsTooLarge() {
        val result = validator.validate(
            listOf(point(-100, -100), point(740, -100), point(740, 580), point(-100, 580)),
            640,
            480,
        )

        assertFalse(result.valid)
        assertEquals(RecognitionRejectionReason.QUADRILATERAL_TOO_LARGE, result.reason)
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        val result = validator.validate(
            listOf(
                RecognitionPoint(Double.NaN, 10.0),
                point(500, 10),
                point(500, 400),
                point(10, 400),
            ),
            640,
            480,
        )

        assertFalse(result.valid)
        assertEquals(RecognitionRejectionReason.CORNERS_NON_FINITE, result.reason)
    }

    @Test
    fun rejectsDegenerateHomography() {
        assertFalse(
            validator.isNonDegenerateHomography(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            ),
        )
        assertTrue(
            validator.isNonDegenerateHomography(
                doubleArrayOf(1.0, 0.0, 4.0, 0.0, 1.0, 8.0, 0.0, 0.0, 1.0),
            ),
        )
    }

    private fun point(x: Int, y: Int) = RecognitionPoint(x.toDouble(), y.toDouble())
}
