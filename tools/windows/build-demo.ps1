param([switch]$ReuseBuild, [string]$JarTool = "jar")
$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
Push-Location $root
try {
    if (-not $ReuseBuild) {
        & .\gradlew.bat :desktopApp:createDistributable :desktopApp:shotsClasses --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Demo build failed" }
    }
    $app = Join-Path $root 'desktopApp\build\compose\binaries\main\app\Ghostly'
    $target = Join-Path $root 'build\demo\Ghostly'
    $classes = Join-Path $root 'desktopApp\build\classes\kotlin\shots'
    if (-not (Test-Path "$app\Ghostly.exe") -or -not (Test-Path $classes)) { throw 'Build the app image and shotsClasses first' }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Copy-Item "$app\*" $target -Recurse -Force
    & $JarTool --create --file "$target\app\demo-shots.jar" -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'Demo classes could not be packaged' }
    $cfgPath = Join-Path $target 'app\Ghostly.cfg'
    $cfg = [System.IO.File]::ReadAllText($cfgPath)
    $entry = 'app.mainclass=app.ghostly.desktop.MainKt'
    if (-not $cfg.Contains($entry) -or $cfg.Contains('[ArgOptions]')) { throw 'Unexpected app launcher configuration' }
    $cfg = $cfg.Replace($entry, 'app.mainclass=app.ghostly.shots.ShotsKt' + "`r`n" + 'app.classpath=$APPDIR\demo-shots.jar')
    $output = Join-Path $root 'build\demo\output'
    $cfg += "`r`n[ArgOptions]`r`narguments=--performance`r`narguments=$output`r`narguments=0`r`n"
    [System.IO.File]::WriteAllText($cfgPath, $cfg, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $target 'portable'), 'Ghostly demo')
    @'
Ghostly demo

Run Ghostly.exe. This uses a fake VPN, sample subscriptions, six test local proxies and silent demo music.
It does not change the running VPN, system proxy, firewall, installed app or real profiles.
The update button simulates a download and never starts a real installer.
Settings -> Local proxy: use checkboxes or Ctrl/Shift, then change the country/server for the selection.
Closing the window ends the demo and discards its temporary settings.
'@ | Set-Content -LiteralPath (Join-Path $target 'README.txt') -Encoding UTF8
    Write-Output (Join-Path $target 'Ghostly.exe')
} finally { Pop-Location }
