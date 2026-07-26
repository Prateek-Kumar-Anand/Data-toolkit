package com.prateek.datatoolkit

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application entry point. Initializes PdfBox-Android's resource loader once,
 * globally, so every PDF feature (merge/split/extract/create) can use it.
 */
class ToolkitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
