package de.pokescan

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class Guess(val name: String, val number: String, val total: String)

object Scanner {
    private val stop = setOf(
        "basis", "phase 1", "phase 2", "phase1", "phase2", "stufe 1", "stufe 2",
        "trainer", "item", "unterstützer", "stadium", "energie", "pokémon-werkzeug"
    )

    fun scan(ctx: Context, capture: ImageCapture, done: (Guess) -> Unit, fail: (String) -> Unit) {
        capture.takePicture(
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(InputImage.fromBitmap(bmp, rot))
                        .addOnSuccessListener { done(parse(it)) }
                        .addOnFailureListener { fail(it.message ?: "Texterkennung fehlgeschlagen") }
                }

                override fun onError(exception: ImageCaptureException) {
                    fail(exception.message ?: "Foto fehlgeschlagen")
                }
            }
        )
    }

    private fun parse(t: Text): Guess {
        val m = Regex("""(\d{1,3})\s*/\s*(\d{1,3})""").findAll(t.text).lastOrNull()
        val lines = t.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }
        var name = ""
        for (l in lines) {
            val s = l.text.trim()
                .replace(Regex("""\s*(KP|HP)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+\d+$"""), "")
                .trim()
            if (s.length < 3 || !s.any { it.isLetter() }) continue
            if (s.lowercase() in stop || s.startsWith("Entwickelt", ignoreCase = true)) continue
            name = s
            break
        }
        return Guess(name, m?.groupValues?.get(1) ?: "", m?.groupValues?.get(2) ?: "")
    }
}
