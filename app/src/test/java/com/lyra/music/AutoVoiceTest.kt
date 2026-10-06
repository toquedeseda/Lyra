package com.lyra.music

import com.lyra.music.playback.AutoLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cómo entiende Lyra lo que se le pide por voz en el coche ("Ok Google, pon … en Lyra"). */
class AutoVoiceTest {

    private fun understood(text: String) = AutoLibrary.stripFillers(AutoLibrary.voiceKey(text))

    @Test
    fun apostrophesAndAccentsDoNotMatter() {
        assertEquals("today s top hits", AutoLibrary.voiceKey("Today’s Top Hits"))
        assertEquals("today s top hits", AutoLibrary.voiceKey("today's top hits"))
        assertEquals("cancion de amor", AutoLibrary.voiceKey("  Canción  de AMOR! "))
    }

    @Test
    fun fillersAreRemoved() {
        assertEquals("today s top hits", understood("Pon mi playlist Today's Top Hits en Lyra"))
        assertEquals("me gusta", understood("pon mis me gusta"))
        assertEquals("para correr", understood("reproduce la carpeta Para correr"))
        assertEquals("descargas", understood("quiero escuchar mis descargas"))
    }

    @Test
    fun namesThatStartLikeAFillerKeepTheirWords() {
        // "El" solo se quita si va suelto delante; "Elefante" sigue igual.
        assertEquals("elefante", understood("Elefante"))
        assertEquals("bad bunny", understood("Bad Bunny"))
    }
}
