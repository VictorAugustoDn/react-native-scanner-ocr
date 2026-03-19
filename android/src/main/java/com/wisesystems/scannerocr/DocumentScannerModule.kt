package com.wisesystems.scannerocr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Estrutura de dados limpa para organizar o retorno do OCR
data class ExtractedData(
    val cpf: String?,
    val rg: String?,
    val birthDate: String?,
    val issueDate: String?,
    val name: String?,
    val gender: String?
)

@ReactModule(name = DocumentScannerModule.NAME)
class DocumentScannerModule(reactContext: ReactApplicationContext) :
    NativeDocumentScannerSpec(reactContext) {

    companion object {
        const val NAME = "DocumentOcr"
        private const val ANDROID_15_API = 35
    }

    override fun getName(): String = NAME

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingPromise: Promise? = null
    private var scanner: GmsDocumentScanner? = null
    private var hostActivityRef: WeakReference<ComponentActivity>? = null
    private var previousFitsSystemWindows: Boolean? = null

    override fun scanDocument(options: ReadableMap, promise: Promise) {
        val activity = currentActivity as? ComponentActivity
        if (activity == null) {
            promise.reject("no_activity", "Activity not available or not a ComponentActivity")
            return
        }

        if (pendingPromise != null) {
            promise.reject("scan_in_progress", "Scan already in progress")
            return
        }

        pendingPromise = promise
        hostActivityRef = WeakReference(activity)
        
        ensureSystemBarsVisible(activity)

        initLauncher(activity)
        
        val builder = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE) 

        if (options.hasKey("maxNumDocuments")) {
            builder.setPageLimit(options.getInt("maxNumDocuments"))
        }
        
        val scannerClient = GmsDocumentScanning.getClient(builder.build())
        
        scannerClient.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher?.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                promise.reject("document_scan_error", e.message)
                clearPending()
            }
    }

    private fun initLauncher(activity: ComponentActivity) {
        if (launcher != null) return
        launcher = activity.activityResultRegistry.register(
            "document-scanner",
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val promise = pendingPromise ?: return@register
            val response = WritableNativeMap()
            val docScanResults = WritableNativeArray()

            if (result.resultCode == Activity.RESULT_OK) {
                val docResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pages = docResult?.pages

                if (pages != null && pages.isNotEmpty()) {
                    activity.lifecycleScope.launch(Dispatchers.IO) { 
                        var index = 0
                        for (page in pages) {
                            val originalUri = page.imageUri ?: continue
                            
                            val bitmap = loadBitmapFromUri(activity, originalUri) 
                            var finalUriString = originalUri.toString() 

                            if (bitmap != null) {
                                val ocrData = decodeTextWithMLKit(bitmap)

                                val timestamp = System.currentTimeMillis()
                                val newPath = saveImageToCache(activity, bitmap, "scan_ocr_${timestamp}_$index")
                                
                                if (newPath != null) {
                                    finalUriString = newPath
                                }

                                val resultObject = WritableNativeMap()
                                resultObject.putString("uri", finalUriString) 
                                
                                if (ocrData != null) {
                                    resultObject.putMap("ocrData", ocrData)
                                    resultObject.putBoolean("success", true)
                                } else {
                                    resultObject.putBoolean("success", false)
                                }
                                
                                docScanResults.pushMap(resultObject)
                            }
                            index++
                        }

                        response.putArray("scannedImages", docScanResults)
                        response.putString("status", "success")
                        promise.resolve(response)
                        clearPending()
                    }
                } else {
                    response.putString("status", "success")
                    response.putArray("scannedImages", docScanResults)
                    promise.resolve(response)
                    clearPending()
                }
            } else {
                response.putString("status", "cancel")
                promise.resolve(response)
                clearPending()
            }
        }
    }

    // --- MÉTODOS DE OCR COM ML KIT ---

    private suspend fun decodeTextWithMLKit(bitmap: Bitmap): WritableMap? =
        suspendCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val result = WritableNativeMap()
                    
                    // Texto bruto mantido para debug no JS
                    result.putString("rawText", visionText.text)
                    
                    // Nossa extração baseada em blocos e linhas entra aqui
                    val extractedData = extractStructuredData(visionText)
                    
                    result.putString("cpf", extractedData.cpf)
                    result.putString("rg", extractedData.rg)
                    result.putString("dataNascimento", extractedData.birthDate)
                    result.putString("dataEmissao", extractedData.issueDate)
                    result.putString("nome", extractedData.name)
                    result.putString("sexo", extractedData.gender)

                    continuation.resume(result)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }

    private fun extractStructuredData(visionText: Text): ExtractedData {
        val blocks = visionText.textBlocks
        val allLines = mutableListOf<String>()
        
        var cpf: String? = null
        var rg: String? = null
        var name: String? = null
        var gender: String? = null
        val allDates = mutableListOf<String>()
        
        val cpfRegex = Regex("""\d{3}[\.\s]?\d{3}[\.\s]?\d{3}[-\s]?\d{2}""")
        val dateRegex = Regex("""\d{2}/\d{2}/\d{4}""")

        // 1. Coleta básica e varredura de linhas
        for (block in blocks) {
            val blockText = block.text
            
            for (line in block.lines) {
                allLines.add(line.text.trim())
            }
            
            if (cpf == null) {
                cpf = cpfRegex.find(blockText)?.value
            }
            
            allDates.addAll(dateRegex.findAll(blockText).map { it.value }.toList())
            
            if (gender == null) {
                if (blockText.contains("MASCULINO", ignoreCase = true)) gender = "M"
                else if (blockText.contains("FEMININO", ignoreCase = true)) gender = "F"
            }
        }

        // --- 2. EXTRAÇÃO DE RG (APRIMORADA COM CLEAN LINE) ---
        val cleanRgRegex = Regex("""\d{1,2}\.?\d{3}\.?\d{3}-?[0-9X]{1,2}""", RegexOption.IGNORE_CASE)
        val strictRgRegex = Regex("""\d{1,2}\.\d{3}\.\d{3}-[0-9X]{1,2}""", RegexOption.IGNORE_CASE)

        for (line in allLines) {
            val upperLine = line.uppercase()
            // Procura pelas labels comuns, incluindo "RG:" ou a palavra solta "RG"
            if (upperLine.contains("REGISTRO GERAL") || 
                upperLine.contains("IDENTIDADE") || 
                upperLine.contains("RG:") || 
                upperLine.matches(Regex(""".*\bRG\b.*"""))) {
                
                // Remove TODOS os espaços da linha para consertar leituras como "1 68"
                val cleanLine = upperLine.replace(" ", "")
                val match = cleanRgRegex.find(cleanLine)?.value
                if (match != null) {
                    rg = match
                    break
                }
            }
        }

        // Fallback do RG: Se o OCR não leu a label "RG", varre o texto todo procurando o formato exato com pontos e traço
        if (rg == null) {
            for (line in allLines) {
                val cleanLine = line.uppercase().replace(" ", "")
                val match = strictRgRegex.find(cleanLine)?.value
                // Se achou, garante que não está pegando um pedaço do CPF por engano
                if (match != null && (cpf == null || !cpf!!.replace(Regex("""[^\d]"""), "").contains(match.replace(Regex("""[^\d]"""), "")))) {
                    rg = match
                    break
                }
            }
        }

        // --- 3. BUSCA DE NOME APRIMORADA ---
        val ignoreList = listOf(
            "REPÚBLICA", "FEDERATIVA", "MINISTÉRIO", "NOME", "ASSINATURA", 
            "TITULAR", "IDENTIDADE", "REGISTRO GERAL", "FILIAÇÃO", "LOCAL", 
            "DATA", "VALIDADE", "DOC", "ORIGEM", "CPF", "NATURALIDADE", "LEI"
        )
        
        for (i in allLines.indices) {
            val line = allLines[i]
            
            if (line.contains("NOME", ignoreCase = true) && 
                !line.contains("PAI", ignoreCase = true) && 
                !line.contains("MÃE", ignoreCase = true)) {
                
                val possibleNameInSameLine = line.replace(Regex("NOME( DO TITULAR)?", RegexOption.IGNORE_CASE), "").replace(":", "").trim()
                
                if (possibleNameInSameLine.length > 5 && !ignoreList.any { possibleNameInSameLine.contains(it, ignoreCase = true) }) {
                    name = possibleNameInSameLine
                    break
                } 
                else if (i + 1 < allLines.size) {
                    val nextLine = allLines[i + 1]
                    if (nextLine.length > 5 && !ignoreList.any { nextLine.contains(it, ignoreCase = true) }) {
                        name = nextLine
                        break
                    } 
                    else if (i + 2 < allLines.size) {
                        val nextNextLine = allLines[i + 2]
                        if (nextNextLine.length > 5 && !ignoreList.any { nextNextLine.contains(it, ignoreCase = true) }) {
                            name = nextNextLine
                            break
                        }
                    }
                }
            }
        }

        // --- 4. CLASSIFICAÇÃO CRONOLÓGICA DE DATAS ---
        var birthDate: String? = null
        var issueDate: String? = null
        
        if (allDates.isNotEmpty()) {
            val sortedDates = allDates.distinct().sortedBy { 
                val parts = it.split("/")
                if (parts.size == 3) "${parts[2]}${parts[1]}${parts[0]}" else "99999999" 
            }
            birthDate = sortedDates.firstOrNull()
            
            if (sortedDates.size > 1) {
                issueDate = sortedDates.lastOrNull()
            }
        }
        
        return ExtractedData(cpf, rg, birthDate, issueDate, name, gender)
    }

    // --- MÉTODOS DE PROCESSAMENTO DE IMAGEM ---

    private fun loadBitmapFromUri(activity: Activity, uri: Uri): Bitmap? {
        return try {
            val bitmap = activity.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it) 
            } ?: return null

            val inputForExif = activity.contentResolver.openInputStream(uri)
            if (inputForExif != null) {
                val exif = ExifInterface(inputForExif)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                inputForExif.close()
                return rotateBitmapIfRequired(bitmap, orientation)
            }
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveImageToCache(context: Context, bitmap: Bitmap, filename: String): String? {
        return try {
            val cachePath = File(context.cacheDir, "scanned_docs")
            if (!cachePath.exists()) cachePath.mkdirs()

            val file = File(cachePath, "$filename.jpg")
            val stream = FileOutputStream(file)
            
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream) 
            stream.close()

            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // --- CONTROLE DE UI (Barras do Sistema) ---

    private fun clearPending() {
        pendingPromise = null
        restoreSystemBars()
    }

    private fun ensureSystemBarsVisible(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < ANDROID_15_API || previousFitsSystemWindows != null) return
        val decor = activity.window.decorView
        @Suppress("DEPRECATION")
        previousFitsSystemWindows = decor.fitsSystemWindows
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    private fun restoreSystemBars() {
        val previous = previousFitsSystemWindows ?: return
        hostActivityRef?.get()?.let { WindowCompat.setDecorFitsSystemWindows(it.window, previous) }
        previousFitsSystemWindows = null
        hostActivityRef = null
    }
}