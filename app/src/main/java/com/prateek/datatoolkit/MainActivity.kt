package com.prateek.datatoolkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.prateek.datatoolkit.databinding.ActivityMainBinding
import com.prateek.datatoolkit.features.batch.BatchProcessingActivity
import com.prateek.datatoolkit.features.datacleaning.DataCleaningActivity
import com.prateek.datatoolkit.features.email.EmailExtractionActivity
import com.prateek.datatoolkit.features.excel.ExcelCsvActivity
import com.prateek.datatoolkit.features.ocr.OcrActivity
import com.prateek.datatoolkit.features.pdf.PdfActivity
import com.prateek.datatoolkit.features.scraping.WebScrapingActivity
import com.prateek.datatoolkit.ui.DashboardActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDashboard.setOnClickListener { startActivity(Intent(this, DashboardActivity::class.java)) }
        binding.btnCleaning.setOnClickListener { startActivity(Intent(this, DataCleaningActivity::class.java)) }
        binding.btnOcr.setOnClickListener { startActivity(Intent(this, OcrActivity::class.java)) }
        binding.btnPdf.setOnClickListener { startActivity(Intent(this, PdfActivity::class.java)) }
        binding.btnExcel.setOnClickListener { startActivity(Intent(this, ExcelCsvActivity::class.java)) }
        binding.btnScraping.setOnClickListener { startActivity(Intent(this, WebScrapingActivity::class.java)) }
        binding.btnEmail.setOnClickListener { startActivity(Intent(this, EmailExtractionActivity::class.java)) }
        binding.btnBatch.setOnClickListener { startActivity(Intent(this, BatchProcessingActivity::class.java)) }
    }
}
