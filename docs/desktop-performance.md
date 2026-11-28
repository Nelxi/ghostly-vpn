# Desktop rendering checks

The scene, particles, reflections, blur and page-transition curves are retained. Desktop ambient motion
follows display frames instead of a free-running 16 ms timer; hidden/minimized windows still stop it.
Android keeps its existing ambient rate policy.

The small aurora surface is now a Compose-owned graphics layer. Independent gradient strips are batched
into a textured triangle mesh per curtain, including their existing heights, colors and opacity. The
same surface is reused for the reflection. This removes software-bitmap painting and bitmap replacement
on every camera-size change. These changes are shared with Android.

Windows defaults to Skiko OpenGL and a 64 MiB GPU resource cache. Explicit `skiko.renderApi` and
`skiko.gpu.resourceCacheLimit` overrides are respected, and Skiko keeps its renderer fallback mechanism.
This changes neither the VPN cores nor Swing's rendering backend.

Tray and second-instance activation now share the same restore path, including an already-visible window
behind other windows. IPC activation is dispatched onto the AWT event thread. Quit disconnects outside
that thread so it can continue processing window/tray events.

## Reproduce

```powershell
.\gradlew.bat :desktopApp:performancePreview -PperfJfr=C:/Temp/ghostly.jfr -PperfOutput=C:/Temp/ghostly-perf
.\gradlew.bat :desktopApp:performancePreview -PperfSeconds=0
```

The first command opens a real GPU window, warms up for five seconds and samples frame-clock intervals
for the rest of a 25-second run. It cycles Home, Settings and Servers every two seconds at a fixed
night sky, using fake profiles and VPN state in a temporary directory. It writes `frames.txt` and a
window screenshot. The second command stays open for manual navigation until closed. Neither command
uses the running VPN or its stored profiles. `-PperfApi=DIRECT3D` overrides the renderer for comparison.

## Observations on 2026-11-28

Owner's Windows 11 desktop: i5-12400F, RTX 5060. The existing Ghostly instance remained running.
Measurements are frame-clock intervals, not a hardware presentation trace or an Android measurement.

| Build/run | Frames | Mean ms | P95 ms | P99 ms | Intervals >50 ms | Process CPU seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Original, Direct3D, JFR | 1075 | 18.60 | 65.44 | 122.27 | 93 | 26.14 |
| Patched, OpenGL, JFR | 1284 | 15.56 | 43.80 | 64.21 | 42 | 22.13 |
| Patched, OpenGL, repeat with JFR | 1292 | 15.47 | 39.37 | 66.51 | 33 | 21.45 |
| Patched, OpenGL, without JFR | 1391 | 14.36 | 38.94 | 50.25 | 16 | 21.09 |

There are still long frames. These runs establish a reduction in stalls and CPU time on this machine,
not perfect smoothness on every computer. The uninstrumented run is listed separately because recorder
overhead affects the comparison. The GPU cache budget is not a cap on total process memory.

The owner's existing crash logs show native allocation failures under system-wide commit exhaustion;
one reports only 5 MiB of remaining page-file-backed commit. A separate log shows an AWT native access
violation while processing a display change. The changes here do not establish that either crash class
is eliminated, and there has been no long-duration memory-pressure test.

Validation: 195 JVM tests passed, including repeated hidden/minimized window restoration; shared Android
code compiled; Windows app image built; desktop/mobile scene screenshots inspected. A physical Android
performance run and other GPUs/platforms remain unverified.
