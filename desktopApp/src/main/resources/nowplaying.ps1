# Ghostly now-playing helper: prints the current system media session (SMTC) as one JSON line per poll.
# Runs as its own process so a misbehaving media session can only end this helper, never the app.
# Output: {"t":title,"a":artist,"pos":seconds,"pms":milliseconds,"age":milliseconds,"dur":seconds,"play":bool},
# {} when nothing plays, {"err":1} when a read failed.
# "pos"/"pms" is the position as the player last published it and "age" how long ago that was (-1 when the
# player gives no usable time). Players publish rarely — Spotify about once in 4.5 s — so the position alone
# stands still between two publications while the track goes on.
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
                $age = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
                # No publication time (year 1601) or a clock that disagrees: the app then treats the
                # position as fresh, as before.
                if ($age -lt 0 -or $age -gt 86400000) { $age = -1 }
                $line = @{
                    t    = [string]$p.Title
                    a    = [string]$p.Artist
                    pos  = [math]::Floor($tl.Position.TotalSeconds)
                    pms  = [math]::Floor($tl.Position.TotalMilliseconds)
                    age  = [math]::Floor($age)
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
