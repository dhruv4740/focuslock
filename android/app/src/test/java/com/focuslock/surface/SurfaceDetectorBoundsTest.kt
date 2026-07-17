package com.focuslock.surface

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceDetectorBoundsTest {

    @Test
    fun `fullscreen viewer fills the window`() {
        assertTrue(isViewerSizedBounds(nodeW = 1080, nodeH = 2400, windowW = 1080, windowH = 2400))
    }

    @Test
    fun `feed-embedded 4x5 post is not viewer sized`() {
        // 1080x1350 post inside a 1080x2400 window is ~56% of window height.
        assertFalse(isViewerSizedBounds(nodeW = 1080, nodeH = 1350, windowW = 1080, windowH = 2400))
    }

    @Test
    fun `zero or negative window dimensions never match`() {
        assertFalse(isViewerSizedBounds(nodeW = 1080, nodeH = 2400, windowW = 0, windowH = 2400))
        assertFalse(isViewerSizedBounds(nodeW = 1080, nodeH = 2400, windowW = 1080, windowH = 0))
        assertFalse(isViewerSizedBounds(nodeW = 1080, nodeH = 2400, windowW = -1, windowH = 2400))
    }

    @Test
    fun `viewer filling a shrunken split-screen window still matches`() {
        assertTrue(isViewerSizedBounds(nodeW = 1080, nodeH = 1180, windowW = 1080, windowH = 1180))
    }
}
