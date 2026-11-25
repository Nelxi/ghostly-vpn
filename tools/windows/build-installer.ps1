# Builds build/release/Ghostly-Windows.exe (Inno Setup installer) and
# build/release/Ghostly-Windows-Portable.zip (unzip & run, data stays in the folder).
# Use -ReuseAppImage to package an already tested :desktopApp:createDistributable image.
param([string]$Iscc = "$env:ISCC", [switch]$ReuseAppImage)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
Push-Location $root
try {
    $app = "desktopApp\build\compose\binaries\main\app\Ghostly"
    $version = (Select-String -Path desktopApp\build.gradle.kts -Pattern 'val ghostlyVersion = "(.+)"').Matches[0].Groups[1].Value
    if ($ReuseAppImage) {
        $imageVersion = (Select-String -LiteralPath "$app\app\Ghostly.cfg" -Pattern '^java-options=-Djpackage\.app-version=(.+)$').Matches[0].Groups[1].Value
        if ($imageVersion -ne $version) { throw "App image version $imageVersion does not match $version" }
    } else {
        & .\gradlew.bat :desktopApp:createDistributable --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Desktop app image build failed" }
    }
    if (-not $Iscc) { $Iscc = (Get-Command iscc -ErrorAction SilentlyContinue).Source }
    if (-not $Iscc) { $Iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" }
    & $Iscc "/DAppVersion=$version" tools\windows\ghostly.iss
    if ($LASTEXITCODE -ne 0) { throw "Windows installer build failed" }

    # Portable: same app image + a "portable" marker (switches data to .\data, no registry/autostart/updates)
    $stage = "build\portable\Ghostly"
    if (Test-Path "build\portable") { Remove-Item "build\portable" -Recurse -Force }
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item "$app\*" $stage -Recurse
    Set-Content -Path "$stage\portable" -Value "Ghostly portable $version" -Encoding ascii
    Set-Content -Path "$stage\README.txt" -Encoding utf8 -Value @"
Ghostly VPN $version — портативная версия

Запуск: Ghostly.exe. Установка не нужна, права администратора не нужны
(кроме режима TUN). Подписки и настройки хранятся рядом, в папке data —
папку можно носить на флешке. Сохранённые подписки зашифрованы ключом
текущего пользователя Windows: на другом компьютере добавьте подписку заново.
Новую версию скачивайте с сайта
ghostlynex.fun или https://github.com/Nelxi/ghostly-vpn/releases
"@
    $zip = "build\release\Ghostly-Windows-Portable.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    # tar (bsdtar, built into Windows 10+) writes proper "/" paths; Compress-Archive in PS 5 writes "\\".
    & "$env:SystemRoot\System32\tar.exe" -a -c -f $zip -C build\portable Ghostly
    if ($LASTEXITCODE -ne 0) { throw "zip failed" }
} finally { Pop-Location }

