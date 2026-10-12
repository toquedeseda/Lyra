package com.lyra.music.data.feedback

import com.lyra.music.data.share.PlaylistSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Lo que alguien cuenta desde «Ayúdanos» (en el móvil o en el PC). Los nombres, los del servidor. */
@Serializable
data class Feedback(
    val tipo: String,
    val mensaje: String,
    val contacto: String? = null,
    val app: FeedbackApp,
    val dispositivo: FeedbackDevice,
    /** El informe de errores de la app (versión, móvil y últimos fallos), si la persona quiere. */
    val informe: String? = null,
) {
    enum class Kind(val code: String, val label: String) {
        PROBLEM("error", "Algo falla"),
        IDEA("idea", "Una idea"),
        OTHER("otro", "Otra cosa"),
    }
}

@Serializable
data class FeedbackApp(val plataforma: String, val version: String, val variante: String? = null)

@Serializable
data class FeedbackDevice(val fabricante: String? = null, val modelo: String? = null, val sistema: String? = null)

/** Manda lo de «Ayúdanos» al servidor de la web de Lyra (lo guarda y avisa; ver lyra-app/opiniones.mjs). */
class FeedbackSender(private val http: OkHttpClient) {

    private val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    @Serializable
    private data class Answer(val ok: Boolean = false, val error: String? = null)

    /** Lanza IOException con el motivo (en palabras de persona) si no se pudo mandar. */
    suspend fun send(feedback: Feedback) = withContext(Dispatchers.IO) {
        val ready = feedback.copy(
            mensaje = feedback.mensaje.trim().take(MAX_MESSAGE),
            contacto = feedback.contacto?.trim()?.take(MAX_CONTACT)?.ifEmpty { null },
            informe = feedback.informe?.let(::cleanReport),
        )
        val body = json.encodeToString(Feedback.serializer(), ready).toRequestBody(JSON_TYPE)
        val request = Request.Builder().url("${PlaylistSharing.BASE}/api/opiniones").post(body).header("X-Lyra", "1").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("No hay conexión con el servidor. Prueba otra vez en un momento.", e)
        }
        response.use {
            if (it.isSuccessful) return@withContext
            // El servidor explica qué pasa (p. ej. «Has mandado varias seguidas…»).
            val reason = runCatching { json.decodeFromString(Answer.serializer(), it.body.string()).error }.getOrNull()
            throw IOException(reason ?: "El servidor respondió ${it.code}. Prueba otra vez en un rato.")
        }
    }

    companion object {
        const val MAX_MESSAGE = 4_000
        const val MAX_CONTACT = 200
        private const val MAX_REPORT = 38_000
        private val JSON_TYPE = "application/json".toMediaType()

        /**
         * El informe ya no lleva cuentas ni claves, pero por si acaso se tapan las cadenas largas que
         * parecen una (tokens, códigos), y se recorta a lo que acepta el servidor.
         */
        fun cleanReport(report: String): String =
            report.replace(Regex("[A-Za-z0-9_\\-]{32,}"), "[…]").take(MAX_REPORT)
    }
}
