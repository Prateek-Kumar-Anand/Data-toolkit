package com.prateek.datatoolkit.features.conversion

import com.prateek.datatoolkit.R
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.io.FileGuards
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.core.storage.StoragePermissionHelper
import com.prateek.datatoolkit.databinding.ActivityFileConversionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.prateek.datatoolkit.core.io.displayNameOf
import com.prateek.datatoolkit.core.ui.formatSize
import com.prateek.datatoolkit.core.ui.saveFileWithToast

class FileConversionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileConversionBinding
    private lateinit var cache: CacheManager

    private var sourceFileName: String = ""
    private var sourceExtension: String = ""
    private var sourceTempFile: File? = null
    private var availableTargets: List<FileConversionHelper.ConversionFormat> = emptyList()

    private var convertedFile: File? = null
    private var convertedTargetPos = -1
    private var loadGeneration = 0   // bumped per pick so a slow earlier load can't overwrite a newer one
    private var busy = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onFilePicked(it) }
    }

    private val storagePermission = StoragePermissionHelper(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileConversionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnPickFile.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.btnConvert.setOnClickListener { runConversion() }
        binding.btnSaveAs.setOnClickListener { onSaveAsClicked() }
        // Saving is only offered for the format that was actually converted - changing the
        // dropdown afterwards must not leave a stale "Save" for a different format.
        binding.spinnerTargetFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.btnSaveAs.isEnabled = !busy && convertedFile != null && position == convertedTargetPos
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            sourceTempFile?.delete()
            convertedFile?.delete()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnPickFile.isEnabled = !value
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun onFilePicked(uri: Uri) {
        val name = displayNameOf(uri)
        val generation = ++loadGeneration
        // Drop the previous file's private copies before loading a new one.
        sourceTempFile?.delete()
        convertedFile?.delete()
        convertedFile = null
        convertedTargetPos = -1
        val nameExt = name.substringAfterLast('.', "")
        val detection = FileConversionHelper.detect(nameExt, contentResolver.getType(uri))
        val category = detection.category
        // Use the resolved extension (may have been recovered from the MIME type when the
        // filename itself had none) for everything downstream - target list and conversion
        // both branch on this, and a resolved-but-not-name-derived extension is exactly the
        // sourceExtension convert() needs to read the file correctly.
        val ext = detection.resolvedExtension

        sourceFileName = name
        sourceExtension = ext
        sourceTempFile = null
        convertedFile = null
        binding.btnSaveAs.isEnabled = false
        binding.btnConvert.isEnabled = false
        binding.tvStatus.text = ""

        if (category == FileConversionHelper.FileCategory.UNKNOWN) {
            binding.tvSourceInfo.text = detection.recognizedButUnsupported?.let { friendly ->
                "$name — $friendly isn't supported for conversion here"
            } ?: "$name — unrecognized file type, can't convert this"
            binding.spinnerTargetFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
            availableTargets = emptyList()
            return
        }

        availableTargets = FileConversionHelper.targetFormats(category, ext)
        if (availableTargets.isEmpty()) {
            binding.tvSourceInfo.text = getString(R.string.conversion_already_in_its_only, name)
            return
        }
        binding.spinnerTargetFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, availableTargets.map { it.label }
        )
        binding.tvSourceInfo.text = getString(R.string.conversion_loading, name)
        setBusy(true)

        lifecycleScope.launch {
            try {
                val temp = withContext(Dispatchers.IO) {
                    copyUriToTempFile(uri, if (ext.isNotEmpty() && ext.all { it.isLetterOrDigit() }) ".$ext" else ".tmp")
                }
                if (generation != loadGeneration) { temp.delete(); return@launch }
                sourceTempFile = temp
                binding.tvSourceInfo.text = getString(R.string.conversion_text, name, categoryLabel(category))
                binding.btnConvert.isEnabled = true
            } catch (e: Throwable) {
                if (generation == loadGeneration) {
                    binding.tvSourceInfo.text = getString(R.string.conversion_failed_to_read, name, friendlyError(e))
                }
            } finally {
                if (generation == loadGeneration) setBusy(false)
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
        val targetPos = binding.spinnerTargetFormat.selectedItemPosition
        // Any previous result is stale from here on - make sure it can't be saved by mistake.
        convertedFile?.delete()
        convertedFile = null
        convertedTargetPos = -1
        binding.btnConvert.isEnabled = false
        binding.btnSaveAs.isEnabled = false
        setBusy(true)
        binding.tvStatus.text = getString(R.string.conversion_converting_to, target.label)

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val outFile = File(cacheDir, "converted_${System.currentTimeMillis()}.${target.extension}")
            try {
                val warning = withContext(Dispatchers.IO) {
                    FileConversionHelper.convert(input, sourceExtension, target, outFile)
                }
                convertedFile = outFile
                convertedTargetPos = targetPos
                binding.btnSaveAs.isEnabled = true
                binding.tvStatus.text = getString(R.string.conversion_done_ready, outFile.name, formatSize(outFile.length())) +
                    (warning?.let { getString(R.string.conversion_warning_suffix, it) } ?: "")
                safeRecord(
                    feature = "FILE_CONVERSION",
                    inputText = "$sourceFileName->${target.extension}:${System.currentTimeMillis()}",
                    inputLabel = "$sourceFileName → .${target.extension}",
                    outputPreview = outFile.name,
                    outputPath = null,
                    qualityScore = 100,
                    status = "SUCCESS",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                outFile.delete()
                throw e
            } catch (e: Throwable) {
                outFile.delete() // never leave a half-written output behind
                binding.tvStatus.text = getString(R.string.conversion_conversion_failed, friendlyError(e))
                safeRecord(
                    feature = "FILE_CONVERSION",
                    inputText = "$sourceFileName->${target.extension}:${System.currentTimeMillis()}",
                    inputLabel = "$sourceFileName → .${target.extension}",
                    outputPreview = friendlyError(e),
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED",
                    durationMs = System.currentTimeMillis() - start
                )
            } finally {
                binding.btnConvert.isEnabled = true
                setBusy(false)
            }
        }
    }

    /** Turns low-level exceptions (null messages, OutOfMemoryError, codec errors) into something readable. */
    private fun friendlyError(e: Throwable): String = when {
        e is OutOfMemoryError -> "Not enough memory for this file - try a smaller one"
        e is java.io.FileNotFoundException -> "The file could no longer be read"
        e is IllegalStateException && e.message.isNullOrBlank() -> "The conversion failed unexpectedly"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private suspend fun safeRecord(
        feature: String, inputText: String, inputLabel: String, outputPreview: String,
        outputPath: String?, qualityScore: Int, status: String, durationMs: Long = 0
    ) {
        try {
            cache.record(
                feature = feature, inputText = inputText, inputLabel = inputLabel,
                outputPreview = outputPreview, outputPath = outputPath,
                qualityScore = qualityScore, status = status, durationMs = durationMs
            )
        } catch (_: Exception) {
        }
    }

    private fun onSaveAsClicked() {
        val file = convertedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Nothing to save yet — convert a file first", Toast.LENGTH_SHORT).show()
            return
        }
        storagePermission.runWithPermission { copyConvertedFileTo(file) }
    }

    private fun copyConvertedFileTo(file: File) {
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val base = sourceFileName.substringBeforeLast('.', sourceFileName).ifBlank { "converted" }
        saveFileWithToast(OutputStorage.Module.CONVERSION, file, "$base.$ext", mime)
    }

    private fun copyUriToTempFile(uri: Uri, suffix: String): File =
        FileGuards.copyToTemp(this, uri, "conv_", suffix, maxBytes = 500L * 1024 * 1024)



    private fun categoryLabel(category: FileConversionHelper.FileCategory): String = when (category) {
        FileConversionHelper.FileCategory.DOCUMENT -> "Document"
        FileConversionHelper.FileCategory.IMAGE -> "Image"
        FileConversionHelper.FileCategory.AUDIO -> "Audio"
        FileConversionHelper.FileCategory.VIDEO -> "Video"
        FileConversionHelper.FileCategory.UNKNOWN -> "Unknown"
    }


}
