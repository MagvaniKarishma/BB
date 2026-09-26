package com.brokerbuddy.voice

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hindi / Marathi → English on the phone (Google ML Kit, free). The language model (~30 MB)
 * downloads the first time it's needed; after that translation works offline. Nothing is
 * sent anywhere by ML Kit for translating.
 */
object EnglishTranslator {
    enum class Source { HINDI, MARATHI }

    private val devanagari = Regex("[\\u0900-\\u097F]")

    /** Whether the text needs translating (it's in Devanagari script). Hinglish in Latin letters doesn't. */
    fun needsTranslation(text: String): Boolean = devanagari.containsMatchIn(text)

    suspend fun toEnglish(text: String, source: Source): String {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(if (source == Source.MARATHI) TranslateLanguage.MARATHI else TranslateLanguage.HINDI)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)
        try {
            withTimeout(120_000) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            return withTimeout(20_000) { translator.translate(text).await() }
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
