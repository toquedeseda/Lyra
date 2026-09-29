# Lyra

App de música personal para Android, estilo Spotify en negro, gris y blanco. Busca, reproduce y descarga música de **YouTube Music** y **SoundCloud**, y se actualiza sola desde las releases de este repositorio.

> Proyecto personal y sin ánimo de lucro. Usar YouTube de esta forma va contra sus condiciones de uso: es para uso propio.

## Funciones

- **Inicio**: accesos rápidos, tus mixes (radio infinita a partir de lo que más escuchas), "Porque escuchaste…", artistas parecidos y las secciones de YouTube Music (con chips de estado de ánimo).
- **Buscar**: sugerencias al escribir, pestañas (Todo, Canciones, Vídeos, Álbumes, Artistas, Playlists, SoundCloud), explorar por géneros y estados de ánimo, y pegar enlaces de YouTube o SoundCloud.
- **Álbumes, artistas y playlists** con descarga completa en un toque. Las playlists de YouTube o SoundCloud se pueden guardar como copia propia y volver a sincronizar.
- **Descargas ocultas** dentro de la app (Opus/M4A sin recomprimir, con carátula), que suenan sin conexión.
- **Reproducción**: crossfade (0-12 s), volumen igualado, ecualizador de 10 bandas con presets, saltar silencios, radio infinita al acabar la cola y cola reordenable.
- **Letras sincronizadas** de [LRCLIB](https://lrclib.net), a pantalla completa y tocables para saltar a esa parte.
- **Fuera de la app**: pantalla de bloqueo y notificación (con "Me gusta"), Android Auto, y una **isla flotante** junto a la cámara que se despliega con los controles.
- **Copia de seguridad** en un `.zip` (biblioteca, playlists, historial y ajustes; opcionalmente con las descargas).
- **Actualizaciones**: al abrir la app mira la última release de GitHub, enseña las novedades y se instala con un botón.

## Primeros pasos en el móvil (Vivo)

1. Descarga el APK de la última [release](https://github.com/toquedeseda/Lyra/releases/latest) e instálalo.
2. Permite las **notificaciones** (controles en la pantalla de bloqueo).
3. En el aviso del Inicio pulsa **Arreglarlo** para quitar la optimización de batería. En los Vivo, además: *Ajustes → Batería → Consumo en segundo plano → Lyra → Permitir*.
4. La primera actualización pedirá permitir **"Instalar apps desconocidas"** a Lyra (solo una vez).
5. **Isla** (*Ajustes → Isla flotante*): actívala y da permiso de *Accesibilidad → Lyra isla* para que vaya junto a la cámara. Si sale en gris: *info de la app → ⋮ → Permitir ajustes restringidos*. Sin accesibilidad funciona con "Mostrar sobre otras apps", justo debajo de la barra de estado.
6. **Android Auto**: al ser una app instalada a mano, activa en Android Auto *Ajustes para desarrolladores → Fuentes desconocidas*.

## Compilar

Android Studio (JDK 21, SDK 37) o desde consola:

```powershell
.\gradlew.bat :app:assembleDebug      # APK de pruebas (com.lyra.music.debug, convive con la release)
.\gradlew.bat :app:testDebugUnitTest  # tests (parser, audio, letras, versiones)
$env:LYRA_LIVE_TESTS="1"; .\gradlew.bat :app:testDebugUnitTest   # también contra YouTube y SoundCloud reales
```

## Publicar una versión

```powershell
.\release.ps1 -Version 1.0.1 -Notes "- Lo que cambia"
```

Sube la versión, pasa los tests, compila y firma el APK, hace commit y tag y crea la release en GitHub. El móvil la detecta al abrir Lyra.

**Firma**: las releases se firman con `signing/lyra-release.jks` usando los datos de `keystore.properties`. Ninguno de los dos está en el repositorio. **Haz copia de seguridad de los dos**: si se pierden, las actualizaciones no se podrán instalar encima y habrá que desinstalar (perdiendo las descargas).

## Estructura

```
app/src/main/java/com/lyra/music/
├── data/
│   ├── source/innertube/   Cliente de YouTube Music (búsqueda, álbumes, artistas, radio, inicio)
│   ├── source/soundcloud/  NewPipeExtractor: URLs de audio de YouTube y todo SoundCloud
│   ├── source/lyrics/      LRCLIB
│   ├── db/                 Room: biblioteca, playlists, historial, descargas, letras
│   ├── download/           Cola de descargas (WorkManager, por trozos)
│   ├── repo/               Repositorios (música, biblioteca, inicio, letras)
│   └── backup/             Copias de seguridad
├── playback/               Servicio Media3, procesador de audio (EQ + volumen), crossfade, radio, Android Auto
├── island/                 Isla flotante
├── update/                 Actualizaciones desde GitHub
└── ui/                     Interfaz en Jetpack Compose
```

## Licencia

GPL-3.0, porque usa [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0). Las letras vienen de [LRCLIB](https://lrclib.net) y no se incluyen en el código.
