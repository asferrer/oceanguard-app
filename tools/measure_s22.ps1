# Medidas de la app en el Galaxy S22 Ultra por adb (script ASCII, PowerShell 5.1).
# Uso:   .\measure_s22.ps1 -Apk ..\dist\oceanguard-v1.0.0-debug.apk [-Package com.oceanguard.ai.debug] [-Step all]
# Pasos: install | push | bench | ram | all
#   install  instala el APK (adb install -r -d)
#   push     copia a /sdcard/Pictures/OceanGuardVAL las imagenes de VAL de tools\pc_reference\images
#   bench    captura el log del benchmark del detector (RTDETRBenchmark) durante 6 minutos
#   ram      vuelca dumpsys meminfo y la temperatura de la bateria
# El benchmark se lanza a mano en el telefono: Ajustes > tocar 21 veces la version (modo desarrollador) >
# seccion de desarrollador > "RT-DETRv2 Detector Benchmark" > boton. Telefono frio, bateria > 50 %, sin ahorro de energia.
param(
    [string]$Apk = "",
    [string]$Package = "com.oceanguard.ai.debug",
    [string]$Step = "all",
    [string]$OutDir = ".\s22_out"
)
$ErrorActionPreference = "Stop"
$adb = "adb"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:MSYS_NO_PATHCONV = "1"

function Test-Device {
    $devs = & $adb devices | Select-String -Pattern "\tdevice$"
    if (-not $devs) { throw "No hay telefono con depuracion USB autorizada (adb devices)." }
    & $adb shell getprop ro.product.model | Out-File -Encoding ascii "$OutDir\device.txt"
    & $adb shell getprop ro.build.version.release | Out-File -Append -Encoding ascii "$OutDir\device.txt"
}

function Step-Install {
    if (-not (Test-Path $Apk)) { throw "Falta -Apk (ruta al APK)." }
    & $adb install -r -d $Apk
}

function Step-Push {
    $imgs = Join-Path $PSScriptRoot "pc_reference\images"
    if (-not (Test-Path $imgs)) { throw "No existe $imgs" }
    $dest = "/sdcard/Pictures/OceanGuardVAL"
    & $adb shell mkdir -p $dest
    Get-ChildItem $imgs -Filter *.jpg | ForEach-Object {
        & $adb push $_.FullName "$dest/$($_.Name)"
        & $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$dest/$($_.Name)" | Out-Null
    }
    Write-Host "Imagenes en la galeria (carpeta OceanGuardVAL). Analiza cada una en la app con el umbral por defecto y compara"
    Write-Host "las cajas con tools\pc_reference\pc_boxes.json (mismas imagenes, mismo umbral, posprocesado de la app)."
}

function Step-Bench {
    & $adb logcat -c
    Write-Host "Lanza ahora el benchmark en el telefono. Capturando 6 minutos en $OutDir\bench_logcat.txt ..."
    $p = Start-Process -FilePath $adb -ArgumentList "logcat","-s","RTDETRBenchmark:I","RTDETRInference:I","DetectionOrchestrator:I" `
        -RedirectStandardOutput "$OutDir\bench_logcat.txt" -NoNewWindow -PassThru
    Start-Sleep -Seconds 360
    Stop-Process -Id $p.Id -Force
    Select-String -Path "$OutDir\bench_logcat.txt" -Pattern "p50=" | ForEach-Object { $_.Line }
}

function Step-Ram {
    # Con Gemma 4 cargado: abre un informe en la app (o activa el afinado con Gemma) y ejecuta esto mientras tanto.
    & $adb shell dumpsys meminfo $Package | Out-File -Encoding ascii "$OutDir\meminfo_$Package.txt"
    & $adb shell cat /proc/meminfo | Select-Object -First 5 | Out-File -Encoding ascii "$OutDir\proc_meminfo.txt"
    & $adb shell dumpsys battery | Select-String "temperature|level" | Out-File -Encoding ascii "$OutDir\battery.txt"
    Select-String -Path "$OutDir\meminfo_$Package.txt" -Pattern "TOTAL PSS|TOTAL RSS|Native Heap|Java Heap" | ForEach-Object { $_.Line }
}

Test-Device
switch ($Step) {
    "install" { Step-Install }
    "push"    { Step-Push }
    "bench"   { Step-Bench }
    "ram"     { Step-Ram }
    "all"     { Step-Install; Step-Push; Step-Bench; Step-Ram }
    default   { throw "Paso desconocido: $Step" }
}
