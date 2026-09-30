# Ghostly now-playing helper: prints the current system media session (SMTC) as one JSON line per poll.
# Runs as its own process so a misbehaving media session can only end this helper, never the app.
# Output: {"t":title,"a":artist,"pos":seconds,"dur":seconds,"play":bool}, {} when nothing plays,
# {"err":1} when a read failed.
param([int]$PollMs = 1200)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    [void]$task.Wait(5000)
    if (-not $task.IsCompleted) { throw 'timeout' }
    $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while ($true) {
    $line = '{}'
    try {
        foreach ($s in $manager.GetSessions()) {
            $p = Await ($s.TryGetMediaPropertiesAsync()) $propsType
            if ($p -and ($p.Title -or $p.Artist)) {
                $tl = $s.GetTimelineProperties()
                $pb = $s.GetPlaybackInfo()
                $line = @{
                    t    = [string]$p.Title
                    a    = [string]$p.Artist
                    pos  = [math]::Floor($tl.Position.TotalSeconds)
                    dur  = [math]::Floor($tl.EndTime.TotalSeconds)
                    play = ([string]$pb.PlaybackStatus -eq 'Playing')
                } | ConvertTo-Json -Compress
                break
            }
        }
    } catch {
        $line = '{"err":1}'
    }
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds $PollMs
}
