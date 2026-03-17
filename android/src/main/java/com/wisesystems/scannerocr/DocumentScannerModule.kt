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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        
        // Garante visibilidade das barras (fix Android 15)
        ensureSystemBarsVisible(activity)

        initLauncher(activity)
        
        val builder = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL) // FULL melhora a qualidade pra OCR

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
                            
                            // 1. Carrega o bitmap e ajusta rotação pelo Exif
                            val bitmap = loadBitmapFromUri(activity, originalUri) 
                            var finalUriString = originalUri.toString() 

                            if (bitmap != null) {
                                // 2. Executa o OCR na imagem alinhada
                                val ocrData = decodeTextWithMLKit(bitmap)

                                // 3. Salva a imagem final com qualidade máxima em cache
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
                    val fullText = visionText.text
                    
                    result.putString("rawText", fullText) // Útil para debugar no React Native
                    
                    // Extração de Padrões
                    val cpf = Regex("""\d{3}\.?\d{3}\.?\d{3}-?\d{2}""").find(fullText)?.value
                    val rg = Regex("""\d{1,2}\.?\d{3}\.?\d{3}-?[0-9a-zA-Z]{1,2}""").find(fullText)?.value
                    
                    // Pega todas as datas. Em documentos, a primeira costuma ser o nascimento, a segunda a emissão.
                    val dates = Regex("""\d{2}/\d{2}/\d{4}""").findAll(fullText).map { it.value }.toList()
                    val birthDate = dates.firstOrNull()

                    // Extração de Sexo
                    var sexo = "Desconhecido"
                    if (fullText.contains("MASCULINO", ignoreCase = true) || Regex("""\bSEXO\b[\s\S]{0,10}\bM\b""").containsMatchIn(fullText)) {
                        sexo = "M"
                    } else if (fullText.contains("FEMININO", ignoreCase = true) || Regex("""\bSEXO\b[\s\S]{0,10}\bF\b""").containsMatchIn(fullText)) {
                        sexo = "F"
                    }

                    // Tenta adivinhar o nome (Normalmente em Maiúsculo, ignorando labels conhecidas)
                    val name = parseNameFromText(fullText)

                    result.putString("cpf", cpf)
                    result.putString("rg", rg)
                    result.putString("dataNascimento", birthDate)
                    result.putString("sexo", sexo)
                    result.putString("nome", name)

                    continuation.resume(result)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }

    private fun parseNameFromText(text: String): String? {
        val ignoreList = listOf("REPÚBLICA", "FEDERATIVA", "BRASIL", "MINISTÉRIO", "TRÂNSITO", "SECRETARIA", "ESTADO", "SEGURANÇA", "PÚBLICA", "CARTEIRA", "IDENTIDADE", "NACIONAL", "HABILITAÇÃO")
        val lines = text.split("\n")
        
        // Tenta achar a primeira linha que seja toda em maiúscula, tenha mais de 5 letras, e não seja uma das palavras ignoradas
        for (line in lines) {
            val cleanLine = line.trim()
            if (cleanLine.length > 5 && cleanLine == cleanLine.uppercase()) {
                val words = cleanLine.split(" ")
                if (words.size >= 2 && !ignoreList.any { cleanLine.contains(it) }) {
                    return cleanLine
                }
            }
        }
        return null
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
            
            // Qualidade em 100% para não perder resolução do documento
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