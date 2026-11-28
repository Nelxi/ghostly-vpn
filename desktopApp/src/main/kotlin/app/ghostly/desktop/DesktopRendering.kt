package app.ghostly.desktop

/** Apply before creating a Compose window. Explicit user/diagnostic overrides always win. */
fun configureDesktopRendering() {
    if (hostOs != HostOs.WINDOWS) return
    // The Direct3D backend stalls on scene/page effects on the tested NVIDIA desktop. Skiko retains its
    // own fallback renderers if OpenGL cannot initialize. This does not change Swing's graphics pipeline.
    System.getProperties().putIfAbsent("skiko.renderApi", "OPENGL")
    // Bound reusable textures/blur intermediates, independently of the JVM heap limit.
    System.getProperties().putIfAbsent("skiko.gpu.resourceCacheLimit", "64M")
}
