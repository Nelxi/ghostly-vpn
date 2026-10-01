package app.ghostly.core.vpn

/**
 * Login/password of the core's hidden loopback port (`app-in`) that the app's own requests
 * (subscription updates, pings) go through.
 *
 * Without auth any app on the phone could find that port by scanning 127.0.0.1 and ask through it
 * which IP the tunnel exits from — even an app that is excluded from the VPN (banks, Gosuslugi, MAX
 * do exactly this and report the address). New random values on every start of the process; the
 * core's config is always built in the same process that then uses the port.
 */
object LoopbackAuth {
    val user: String = "g" + token(10)
    val pass: String = token(24)

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun token(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val bytes = (0..length / 16).flatMap { kotlin.uuid.Uuid.random().toByteArray().asList() }
        return (0 until length).joinToString("") { alphabet[(bytes[it].toInt() and 0xFF) % alphabet.length].toString() }
    }
}
