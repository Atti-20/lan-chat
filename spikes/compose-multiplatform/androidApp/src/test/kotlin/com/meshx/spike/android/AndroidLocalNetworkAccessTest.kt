package com.meshx.spike.android

import com.meshx.spike.LocalNetworkAccessPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalNetworkAccessTest {
    @Test
    fun olderAndroidDoesNotRequireRuntimePermission() {
        val state = resolveLocalNetworkAccess(
            required = false,
            granted = true,
            requesting = false,
            previouslyRequested = false,
            canShowRationale = false,
        )
        assertEquals(LocalNetworkAccessPhase.NOT_REQUIRED, state.phase)
        assertTrue(state.granted)
    }

    @Test
    fun firstAndroid17LaunchCanRequestPermission() {
        val state = resolveLocalNetworkAccess(
            required = true,
            granted = false,
            requesting = false,
            previouslyRequested = false,
            canShowRationale = false,
        )
        assertEquals(LocalNetworkAccessPhase.REQUIRED, state.phase)
        assertTrue(state.canRequest)
        assertFalse(state.granted)
    }

    @Test
    fun denialWithRationaleCanRequestAgain() {
        val state = resolveLocalNetworkAccess(
            required = true,
            granted = false,
            requesting = false,
            previouslyRequested = true,
            canShowRationale = true,
        )
        assertEquals(LocalNetworkAccessPhase.DENIED, state.phase)
        assertTrue(state.canRequest)
    }

    @Test
    fun permanentDenialRoutesToSettings() {
        val state = resolveLocalNetworkAccess(
            required = true,
            granted = false,
            requesting = false,
            previouslyRequested = true,
            canShowRationale = false,
        )
        assertEquals(LocalNetworkAccessPhase.DENIED, state.phase)
        assertFalse(state.canRequest)
    }

    @Test
    fun grantAlwaysEnablesDiscovery() {
        val state = resolveLocalNetworkAccess(
            required = true,
            granted = true,
            requesting = false,
            previouslyRequested = true,
            canShowRationale = false,
        )
        assertEquals(LocalNetworkAccessPhase.GRANTED, state.phase)
        assertTrue(state.granted)
    }
}
