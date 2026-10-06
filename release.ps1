<#
.SYNOPSIS
  Publica una versión nueva de Lyra en GitHub. El móvil la detecta sola al abrir la app.

.EXAMPLE
  .\release.ps1 -Version 1.0.1 -Notes "- Arreglado el crossfade`n- Letras más rápidas"

.EXAMPLE
  .\release.ps1 -Version 1.1.0 -NotesFile notas.md
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [string]$NotesFile = "",
    # Línea opcional al final del mensaje del commit (p. ej. "Co-Authored-By: …").
    [string]$CommitTrailer = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

# Ejecuta un programa externo y solo falla por su código de salida. En Windows
# PowerShell 5.1 lo que escriben por stderr (Gradle, git) no debe cortar el script.
function Invoke-Tool([string]$Exe, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "Falló: $Exe $($Arguments -join ' ') (código $code)" }
}

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
}

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "La versión debe tener el formato 1.2.3"
}
if (-not (Test-Path "$root\keystore.properties")) {
    throw "Falta keystore.properties (la clave de firma). Sin ella el móvil no aceptará la actualización."
}

# --- 1. Subir versionCode y versionName ---------------------------------------
$gradleFile = "$root\app\build.gradle.kts"
$gradle = Get-Content $gradleFile -Raw -Encoding utf8
$codeMatch = [regex]::Match($gradle, 'val lyraVersionCode = (\d+)')
$nameMatch = [regex]::Match($gradle, 'val lyraVersionName = "([^"]+)"')
if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw "No encuentro la versión en app/build.gradle.kts" }
$current = $nameMatch.Groups[1].Value
if ([version]$Version -le [version]$current) {
    throw "La versión $Version no es mayor que la actual ($current)"
}
$newCode = [int]$codeMatch.Groups[1].Value + 1
$originalGradle = $gradle
$gradle = $gradle -replace 'val lyraVersionCode = \d+', "val lyraVersionCode = $newCode"
$gradle = $gradle -replace 'val lyraVersionName = "[^"]+"', "val lyraVersionName = `"$Version`""
[IO.File]::WriteAllText($gradleFile, $gradle, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Versión $current -> $Version (código $newCode)" -ForegroundColor Cyan

# --- 2. Tests y compilación ----------------------------------------------------
try {
    # Las dos versiones: la completa (isla junto a la cámara) y la de amigos (sin ese permiso).
    Invoke-Tool "$root\gradlew.bat" @(":app:testCompletaDebugUnitTest", ":app:assembleCompletaRelease", ":app:assembleAmigosRelease", "--console=plain")
} catch {
    # Si algo falla, la versión vuelve a como estaba.
    [IO.File]::WriteAllText($gradleFile, $originalGradle, (New-Object System.Text.UTF8Encoding $false))
    throw "La compilación o los tests han fallado; versión restaurada a $current. $_"
}

New-Item -ItemType Directory -Force "$root\dist" | Out-Null
$apk = "$root\dist\Lyra-v$Version.apk"
$apkFriends = "$root\dist\Lyra-v${Version}_amigos.apk"
Copy-Item "$root\app\build\outputs\apk\completa\release\app-completa-release.apk" $apk -Force
Copy-Item "$root\app\build\outputs\apk\amigos\release\app-amigos-release.apk" $apkFriends -Force
Write-Host "APK: $apk y $apkFriends" -ForegroundColor Cyan

# --- 3. Notas -------------------------------------------------------------------
$notesPath = "$root\dist\notas-v$Version.md"
if ($NotesFile) {
    Copy-Item $NotesFile $notesPath -Force
} elseif ($Notes) {
    [IO.File]::WriteAllText($notesPath, $Notes, (New-Object System.Text.UTF8Encoding $false))
} else {
    [IO.File]::WriteAllText($notesPath, "Mejoras y correcciones.", (New-Object System.Text.UTF8Encoding $false))
}
# Guía para quien la instala por primera vez (Play Protect avisa al no venir de Google Play).
# La app no la enseña en «Novedades»: corta las notas en la marca <!-- instalar -->.
if (Test-Path "$root\INSTALAR.md") {
    $guide = [IO.File]::ReadAllText("$root\INSTALAR.md", [Text.Encoding]::UTF8).Trim()
    $notes = [IO.File]::ReadAllText($notesPath, [Text.Encoding]::UTF8).TrimEnd()
    [IO.File]::WriteAllText($notesPath, "$notes`n`n<!-- instalar -->`n`n$guide`n", (New-Object System.Text.UTF8Encoding $false))
}

# --- 4. Git y GitHub -----------------------------------------------------------
Invoke-Tool "git" @("add", "-A")
if ($CommitTrailer) {
    Invoke-Tool "git" @("commit", "-q", "-m", "Lyra $Version", "-m", $CommitTrailer)
} else {
    Invoke-Tool "git" @("commit", "-q", "-m", "Lyra $Version")
}
Invoke-Tool "git" @("tag", "v$Version")
Invoke-Tool "git" @("push", "-q", "origin", "HEAD", "--tags")
# Primero la completa y después la de amigos: las versiones antiguas de la app (hasta la 1.6)
# se actualizan con el primer .apk de la release, y ese tiene que ser el de la completa.
Invoke-Tool "gh" @("release", "create", "v$Version", $apk, "--title", "Lyra $Version", "--notes-file", $notesPath)
Invoke-Tool "gh" @("release", "upload", "v$Version", $apkFriends)

Write-Host "Publicada Lyra $Version. El móvil la verá al abrir la app (o en Ajustes -> Buscar actualizaciones)." -ForegroundColor Green
