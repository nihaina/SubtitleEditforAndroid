package com.subtitleedit.ui

/** A fresh token also permits locating the same song twice in succession. */
internal data class MediaCategoryRevealRequest(
    val category: MediaCategory,
    val trackPath: String,
    val token: Long = System.nanoTime()
)
