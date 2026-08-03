package com.prateek.datatoolkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.prateek.datatoolkit.databinding.ActivityMainBinding
import com.prateek.datatoolkit.features.batch.BatchProcessingActivity
import com.prateek.datatoolkit.features.conversion.FileConversionActivity
import com.prateek.datatoolkit.features.datacleaning.DataCleaningActivity
import com.prateek.datatoolkit.features.email.EmailExtractionActivity
import com.prateek.datatoolkit.features.excel.ExcelCsvActivity
import com.prateek.datatoolkit.features.invoice.InvoiceOcrActivity
import com.prateek.datatoolkit.features.ocr.OcrActivity
import com.prateek.datatoolkit.features.pdf.PdfActivity
import com.prateek.datatoolkit.features.scraping.WebScrapingActivity
import com.prateek.datatoolkit.features.workflow.WorkflowActivity
import com.prateek.datatoolkit.ui.DashboardActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWorkflow.setOnClickListener { open(WorkflowActivity::class.java) }
        binding.btnCleaning.setOnClickListener { open(DataCleaningActivity::class.java) }
        binding.btnOcr.setOnClickListener { open(OcrActivity::class.java) }
        binding.btnPdf.setOnClickListener { open(PdfActivity::class.java) }
        binding.btnExcel.setOnClickListener { open(ExcelCsvActivity::class.java) }
        binding.btnConversion.setOnClickListener { open(FileConversionActivity::class.java) }
        binding.btnScraping.setOnClickListener { open(WebScrapingActivity::class.java) }
        binding.btnEmail.setOnClickListener { open(EmailExtractionActivity::class.java) }
        binding.btnBatch.setOnClickListener { open(BatchProcessingActivity::class.java) }
        binding.btnDashboard.setOnClickListener { open(DashboardActivity::class.java) }
        binding.btnInvoice.setOnClickListener { open(InvoiceOcrActivity::class.java) }
    }

    /** Starts [cls] with a subtle slide+fade instead of the platform's default abrupt cut. */
    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}
