package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorUiStateTest {
    @Test
    fun compatibilityPropertiesUpdateTheSingleUiState() {
        val viewModel = EditorViewModel()

        viewModel.initialized = true
        viewModel.documentLoaded = true
        viewModel.isSourceViewMode = true
        viewModel.selectedIndices = setOf(1, 3)
        viewModel.playbackPositionMs = 2500L
        viewModel.playbackSpeed = 1.5f
        viewModel.selectedAudioStreamIndex = 2
        viewModel.isAudioOnlyFromVideo = true

        assertTrue(viewModel.uiState.value.initialized)
        assertTrue(viewModel.uiState.value.documentLoaded)
        assertTrue(viewModel.uiState.value.isSourceViewMode)
        assertEquals(setOf(1, 3), viewModel.uiState.value.selectedIndices)
        assertEquals(2500L, viewModel.uiState.value.playbackPositionMs)
        assertEquals(1.5f, viewModel.uiState.value.playbackSpeed)
        assertEquals(2, viewModel.uiState.value.selectedAudioStreamIndex)
        assertTrue(viewModel.uiState.value.isAudioOnlyFromVideo)
    }

    @Test
    fun defaultUiStatePreservesExistingDefaults() {
        val viewModel = EditorViewModel()

        assertEquals(EditorUiState(), viewModel.uiState.value)
        assertFalse(viewModel.isSourceViewMode)
        assertEquals(1.0f, viewModel.playbackSpeed)
    }
}
