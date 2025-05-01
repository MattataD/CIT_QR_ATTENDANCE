package com.example.facenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceNetModel(context: Context) {

    private val modelPath = "facenet.tflite"
    private val inputImageSize = 160
    private val embeddingSize = 512
    private var interpreter: Interpreter
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        // Load the model
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val fileInputStream = assetFileDescriptor.createInputStream()
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        interpreter = Interpreter(modelBuffer)
    }

    // This method gets the embedding of the input bitmap
    fun getEmbedding(bitmap: Bitmap, callback: (embedding: FloatArray) -> Unit) {
        // Perform inference on a background thread to prevent UI thread blocking
        executor.execute {
            val resized = resizeBitmap(bitmap, inputImageSize)
            val input = preprocessImage(resized)
            val embedding = Array(1) { FloatArray(embeddingSize) }
            interpreter.run(input, embedding)
            callback(embedding[0])
        }
    }

    // Resizes the bitmap to the required input size
    private fun resizeBitmap(bitmap: Bitmap, size: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, size, size, false)
    }

    // Preprocesses the image to match FaceNet model input requirements
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val imgData = ByteBuffer.allocateDirect(1 * inputImageSize * inputImageSize * 3 * 4)
        imgData.order(ByteOrder.nativeOrder())
        imgData.rewind()

        val pixels = IntArray(inputImageSize * inputImageSize)
        bitmap.getPixels(pixels, 0, inputImageSize, 0, 0, inputImageSize, inputImageSize)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF).toFloat()
            val g = (pixel shr 8 and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            imgData.putFloat((r - 127.5f) / 128f)
            imgData.putFloat((g - 127.5f) / 128f)
            imgData.putFloat((b - 127.5f) / 128f)
        }

        return imgData
    }

    // Calculates the Euclidean distance between two embeddings
    fun calculateDistance(emb1: FloatArray, emb2: FloatArray): Float {
        var sum = 0f
        for (i in emb1.indices) {
            val diff = emb1[i] - emb2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    // Clean up the interpreter to prevent memory leaks
    fun close() {
        interpreter.close()
        executor.shutdown()
    }
}
