package com.flux.codebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileInjectionTest {

    @Test
    fun viewportIsDerivedFromTheSelectedScale() {
        val js = MobileInjection.documentStartScript(0.17f)
        assertTrue(js.contains("var S = 0.17;"))
        assertTrue(js.contains("Math.round(760 / S)"))
        assertTrue(js.contains("minimum-scale=0.05"))
        assertTrue(js.contains("maximum-scale=5"))
    }

    @Test
    fun scaleIsClampedToTheOfferedRange() {
        assertTrue(MobileInjection.documentStartScript(0.01f).contains("var S = 0.17;"))
        assertTrue(MobileInjection.documentStartScript(9f).contains("var S = 1.00;"))
    }

    @Test
    fun applyScriptUsesTheSameStorageKey() {
        val js = MobileInjection.applyScaleScript(0.6f)
        assertTrue(js.contains("'cbm-ui-scale'"))
        assertTrue(js.contains("'0.60'"))
    }

    @Test
    fun cssDisablesTextAutosizingAndWidensTheChatColumn() {
        val script = MobileInjection.documentStartScript(1f)
        assertTrue(script.contains("text-size-adjust:100%"))
        assertTrue(script.contains("--conversation-content-max-width:100%"))
    }

    @Test
    fun presetsCoverTheRequestedRange() {
        assertEquals(1f, UiScale.STEPS.first(), 0.001f)
        assertEquals(0.17f, UiScale.STEPS.last(), 0.001f)
        assertTrue("minimum must be at least 5x smaller", 1f / UiScale.STEPS.last() >= 5f)
        assertEquals(0.6f, UiScale.nearest(0.61f), 0.001f)
        assertEquals(6, UiScale.indexOf(0.17f))
        assertEquals(2, UiScale.indexOf(0.6f))
    }
}
