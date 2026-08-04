package com.prateek.datatoolkit.features.conversion

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.databinding.ActivityFileConversionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileConversionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileConversionBinding
    private lateinit var cache: CacheManager

    private var sourceFileName: String = ""
    private var sourceExtension: String = ""
    private var sourceTempFile: File? = null
    private var availableTargets: List<FileConversionHelper.ConversionFormat> = emptyList()

    private var convertedFile: File? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onFilePicked(it) }
    }

    // Wildcard mime since the produced file's format varies with what the user picked as a
    // target (pdf/docx/txt/jpg/png/webp/bmp/wav/m4a) - the filename we pass carries the
    // real extension, which is what the system "Save As" picker actually uses.
    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { copyConvertedFileTo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileConversionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnPickFile.setOnClickListener { pickFile.launch("*/*") }
        binding.btnConvert.setOnClickListener { runConversion() }
        binding.btnSaveAs.setOnClickListener { onSaveAsClicked() }
    }

    private fun onFilePicked(uri: Uri) {
        val name = displayNameOf(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val category = FileConversionHelper.detectCategory(ext, contentResolver.getType(uri))

        sourceFileName = name
        sourceExtension = ext
        sourceTempFile = null
        convertedFile = null
        binding.btnSaveAs.isEnabled = false
        binding.btnConvert.isEnabled = false
        binding.tvStatus.text = ""

        if (category == FileConversionHelper.FileCategory.UNKNOWN) {
            binding.tvSourceInfo.text = "$name — unrecognized file type, can't convert this"
            binding.spinnerTargetFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
            availableTargets = emptyList()
            return
        }

        availableTargets = FileConversionHelper.targetFormats(category, ext)
        if (availableTargets.isEmpty()) {
            binding.tvSourceInfo.text = "$name — already in its only supported format"
            return
        }
        binding.spinnerTargetFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, availableTargets.map { it.label }
        )
        binding.tvSourceInfo.text = "Loading $name…"
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val temp = withContext(Dispatchers.IO) {
                    copyUriToTempFile(uri, if (ext.isNotEmpty()) ".$ext" else ".tmp")
                }
                sourceTempFile = temp
                binding.tvSourceInfo.text = "$name  •  ${categoryLabel(category)}"
                binding.btnConvert.isEnabled = true
            } catch (e: Exception) {
                binding.tvSourceInfo.text = "Failed to read $name: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun runConversion() {
        val input = sourceTempFile
        val target = availableTargets.getOrNull(binding.spinnerTargetFormat.selectedItemPosition)
        if (input == null || !input.exists() || target == null) {
            Toast.makeText(this, "Pick a file first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnConvert.isEnabled = false
        binding.btnSaveAs.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Converting to ${target.label}…"

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val outFile = File(cacheDir, "converted_${System.currentTimeMillis()}.${target.extension}")
                withContext(Dispatchers.IO) {
                    FileConversionHelper.convert(input, sourceExtension, target, outFile)
                }
                convertedFile = outFile
                binding.btnSaveAs.isEnabled = true
                binding.tvStatus.text = "Done — ${outFile.name} (${formatSize(outFile.length())}) ready to save"
                cache.record(
                    feature = "FILE_CONVERSION",
                    inputText = "$sourceFileName->${target.extension}:${System.currentTimeMillis()}",
                    inputLabel = "$sourceFileName → .${target.extension}",
                    outputPreview = outFile.name,
                    outputPath = null,
                    qualityScore = 100,
                    status = "SUCCESS",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Throwable) {
                binding.tvStatus.text = "Conversion failed: ${e.message}"
                cache.record(
                    feature = "FILE_CONVERSION",
                    inputText = "$sourceFileName->${target.extension}:${System.currentTimeMillis()}",
                    inputLabel = "$sourceFileName → .${target.extension}",
                    outputPreview = e.message ?: "error",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED",
                    durationMs = System.currentTimeMillis() - start
                )
            } finally {
                binding.btnConvert.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onSaveAsClicked() {
        val file = convertedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Nothing to save yet — convert a file first", Toast.LENGTH_SHORT).show()
            return
        }
        saveAs.launch(file.name)
    }

    private fun copyConvertedFileTo(uri: Uri) {
        val file = convertedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                }
                Toast.makeText(this@FileConversionActivity, "Saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@FileConversionActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri, suffix: String): File {
        val file = File.createTempFile("conv_", suffix, cacheDir)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open the picked file")
        return file
    }

    private fun displayNameOf(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
        } catch (e: Exception) {
            // Fall back to the lastPathSegment already captured above.
        }
        return name
    }

    private fun categoryLabel(category: FileConversionHelper.FileCategory): String = when (category) {
        FileConversionHelper.FileCategory.DOCUMENT -> "Document"
        FileConversionHelper.FileCategory.IMAGE -> "Image"
        FileConversionHelper.FileCategory.AUDIO -> "Audio"
        FileConversionHelper.FileCategory.VIDEO -> "Video"
        FileConversionHelper.FileCategory.UNKNOWN -> "Unknown"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
