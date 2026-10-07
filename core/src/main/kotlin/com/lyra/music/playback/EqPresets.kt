package com.lyra.music.playback

/** Presets del ecualizador (10 bandas: 31 Hz … 16 kHz). */
object EqPresets {
    data class Preset(val key: String, val label: String, val bands: List<Float>)

    val all = listOf(
        Preset("flat", "Plano", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("bass", "Más graves", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("bass_extreme", "Graves a tope", listOf(9f, 8f, 6f, 3f, 0f, -1f, -1f, 0f, 0f, 0f)),
        Preset("treble", "Más agudos", listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 4f, 5f, 6f)),
        Preset("pop", "Pop", listOf(-1f, 1f, 3f, 4f, 2f, 0f, -1f, -1f, 1f, 2f)),
        Preset("rock", "Rock", listOf(4f, 3f, 1f, -1f, -2f, -1f, 1f, 3f, 4f, 4f)),
        Preset("electronic", "Electrónica", listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
        Preset("hiphop", "Hip hop / Reggaeton", listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f)),
        Preset("acoustic", "Acústica", listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f)),
        Preset("vocal", "Voces", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)),
        Preset("classical", "Clásica", listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 1f, 3f)),
        Preset("jazz", "Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Preset("night", "Noche (suave)", listOf(-4f, -3f, -2f, 0f, 1f, 1f, 0f, -1f, -3f, -4f)),
    )

    fun byKey(key: String): Preset? = all.firstOrNull { it.key == key }
}
