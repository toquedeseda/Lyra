package com.lyra.music.core

/** «1 canción», «3 canciones». */
fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
