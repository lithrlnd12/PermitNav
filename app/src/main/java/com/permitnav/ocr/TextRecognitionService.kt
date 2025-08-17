package com.permitnav.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException

class TextRecognitionService(private val context: Context) {
    
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun processImage(imageUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("TextRecognition", "Loading image from URI: $imageUri")
            val inputImage = InputImage.fromFilePath(context, imageUri)
            android.util.Log.d("TextRecognition", "Image loaded successfully, processing...")
            val result = processImage(inputImage)
            android.util.Log.d("TextRecognition", "Processing complete, text length: ${result.length}")
            result
        } catch (e: IOException) {
            android.util.Log.e("TextRecognition", "Failed to load image from URI: $imageUri", e)
            throw TextRecognitionException("Failed to load image from URI", e)
        }
    }
    
    suspend fun processImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        processImage(inputImage)
    }
    
    private suspend fun processImage(inputImage: InputImage): String = suspendCancellableCoroutine { continuation ->
        android.util.Log.d("TextRecognition", "Starting ML Kit text recognition")
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                android.util.Log.d("TextRecognition", "ML Kit success - extracted text: '${result.text}'")
                continuation.resume(result.text)
            }
            .addOnFailureListener { exception ->
                android.util.Log.e("TextRecognition", "ML Kit failed", exception)
                continuation.resumeWithException(TextRecognitionException("Text recognition failed", exception))
            }
    }
    
    fun close() {
        recognizer.close()
    }
}

class TextRecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)