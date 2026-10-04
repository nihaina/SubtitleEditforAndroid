package com.subtitleedit.util

import android.os.Environment
import java.io.File

/** Resolves the shared application directory used by downloads and model management. */
object ModelDirectoryManager {
    const val DEFAULT_DIRECTORY_NAME = "TsumugiSub"
    const val MODELS_DIRECTORY_NAME = "models"

    @Volatile
    private var configuredDirectory: File? = null

    @Suppress("DEPRECATION")
    fun defaultDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        DEFAULT_DIRECTORY_NAME
    )

    fun softwareDirectory(): File = configuredDirectory ?: defaultDirectory()

    fun modelsDirectory(): File = File(softwareDirectory(), MODELS_DIRECTORY_NAME)

    fun setSoftwareDirectory(directory: File?) {
        configuredDirectory = directory?.let { File(it.path).absoluteFile }
    }

    fun setModelsDirectory(directory: File?) = setSoftwareDirectory(directory)
}
