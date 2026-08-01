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

        // --- Fix for "opening any .xlsx crashes the whole app" (Excel/CSV screen AND Data
        // Cleaning's file upload both went through this, since both call
        // ExcelCsvHelper.readXlsx) ---
        //
        // Root cause: fastexcel-reader's ReadableWorkbook constructor calls
        // javax.xml.stream.XMLInputFactory.newInstance() to get a StAX parser. That method
        // looks for an implementation in this order: (1) this exact system property, (2) a
        // JRE config file that doesn't exist on Android, (3) a META-INF/services ServiceLoader
        // scan of the classpath. aalto-xml (fastexcel-reader's own StAX engine, now an explicit
        // dependency - see app/build.gradle) IS on the classpath, but Android's FactoryFinder
        // can still fail step 3, throwing javax.xml.stream.FactoryConfigurationError. Because
        // that class extends java.lang.Error (not Exception), it slipped straight past every
        // "catch (e: Exception)" in the app and crashed the whole process instead of showing an
        // error message.
        //
        // Setting the system property ourselves, first, means step 1 always succeeds and steps
        // 2/3 are never reached - this is the standard, documented fix for any StAX-based
        // library (fastexcel, Apache POI, etc.) running on Android.
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl")
        System.setProperty("javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl")
        System.setProperty("javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl")

        PDFBoxResourceLoader.init(applicationContext)
    }
}
