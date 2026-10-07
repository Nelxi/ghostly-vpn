# Builds build/release/Ghostly-Windows.exe (Inno Setup installer) and
# build/release/Ghostly-Windows-Portable.zip (unzip & run, data stays in the folder).
param([string]$Iscc = "$env:ISCC")
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
Push-Location $root
try {
    & .\gradlew.bat :desktopApp:createDistributable --console=plain
    if (-not $Iscc) { $Iscc = (Get-Command iscc -ErrorAction SilentlyContinue).Source }
    if (-not $Iscc) { $Iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" }
    $version = (Select-String -Path desktopApp\build.gradle.kts -Pattern 'val ghostlyVersion = "(.+)"').Matches[0].Groups[1].Value
    & $Iscc "/DAppVersion=$version" tools\windows\ghostly.iss

    # Portable: same app image + a "portable" marker (switches data to .\data, no registry/autostart/updates)
    $app = "desktopApp\build\compose\binaries\main\app\Ghostly"
    $stage = "build\portable\Ghostly"
    if (Test-Path "build\portable") { Remove-Item "build\portable" -Recurse -Force }
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item "$app\*" $stage -Recurse
    Set-Content -Path "$stage\portable" -Value "Ghostly portable $version" -Encoding ascii
    Set-Content -Path "$stage\README.txt" -Encoding utf8 -Value @"
Ghostly VPN $version — портативная версия

Запуск: Ghostly.exe. Установка не нужна, права администратора не нужны
(кроме режима TUN). Подписки и настройки хранятся рядом, в папке data —
папку можно носить на флешке. Новую версию скачивайте с сайта
ghostlynex.fun или https://github.com/Nelxi/ghostly-vpn/releases
"@
    $zip = "build\release\Ghostly-Windows-Portable.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    # tar (bsdtar, built into Windows 10+) writes proper "/" paths; Compress-Archive in PS 5 writes "\\".
    & "$env:SystemRoot\System32\tar.exe" -a -c -f $zip -C build\portable Ghostly
    if ($LASTEXITCODE -ne 0) { throw "zip failed" }
} finally { Pop-Location }

