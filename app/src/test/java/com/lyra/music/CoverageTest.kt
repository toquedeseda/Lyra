package com.lyra.music

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.lyra.music.playback.WaitForNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Sin cobertura se espera a la red (sin límite) en vez de dar la canción por perdida. */
class CoverageTest {

    private var online = true
    private var clock = 1_000_000L
    private val policy = WaitForNetworkPolicy(isOnline = { online }, now = { clock })

    // En las pruebas no hay Uri de Android (y DataSpec no deja crearse sin una): la carga va sin
    // ellas, que para decidir si reintentar solo cuentan el fallo y los intentos.
    @Suppress("UNCHECKED_CAST")
    private fun <T> missing(): T = null as T

    private fun info(error: IOException, count: Int) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1, missing(), missing(), emptyMap(), 0, 0, 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        count,
    )

    private fun forbidden() = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), missing(), ByteArray(0))

    @Test
    fun sinRedSeReintentaSiempre() {
        val error = IOException("sin red", UnknownHostException("rr1.googlevideo.com"))
        assertEquals(1_000L, policy.getRetryDelayMsFor(info(error, 1)))
        assertEquals(2_000L, policy.getRetryDelayMsFor(info(error, 2)))
        assertEquals(3_000L, policy.getRetryDelayMsFor(info(error, 3)))
        // Por muchos intentos que lleve, no se da por perdida (antes, al cuarto daba error).
        assertEquals(3_000L, policy.getRetryDelayMsFor(info(error, 500)))
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun loQueNoEsLaRedSigueComoSiempre() {
        // La dirección caducó (403): unos intentos y al aviso de error, que pide otra.
        assertTrue(policy.getRetryDelayMsFor(info(forbidden(), 1)) != C.TIME_UNSET)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(forbidden(), 4)))
        // Un audio que no se puede leer no se arregla esperando, ni con cobertura ni sin ella.
        online = false
        val broken = ParserException.createForMalformedContainer("roto", null)
        assertFalse(policy.isNetworkProblem(broken))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(broken, 1)))
    }

    @Test
    fun reconoceLosFallosDeRed() {
        assertTrue(policy.isNetworkProblem(IOException(SocketTimeoutException("timeout"))))
        assertTrue(policy.isNetworkProblem(UnknownHostException("music.youtube.com")))
        assertFalse(policy.isNetworkProblem(IOException("Esta canción no tiene audio disponible")))
        assertFalse(policy.isNetworkProblem(forbidden()))
        // Sin ninguna red, cualquier fallo al cargar es por eso.
        online = false
        assertTrue(policy.isNetworkProblem(IOException("Esta canción no tiene audio disponible")))
    }

    @Test
    fun sabeSiSeEstaEsperandoCobertura() {
        assertFalse(policy.failing)
        policy.getRetryDelayMsFor(info(IOException(UnknownHostException("x")), 1))
        assertTrue(policy.failing)
        clock += 7_000
        assertTrue(policy.failing)
        // Si ya no falla (carga bien), deja de contar como espera.
        clock += 2_000
        assertFalse(policy.failing)
    }
}
