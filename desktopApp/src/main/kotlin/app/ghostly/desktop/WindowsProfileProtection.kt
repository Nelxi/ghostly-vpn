package app.ghostly.desktop

import app.ghostly.core.store.ProfileProtector
import com.sun.jna.platform.win32.Crypt32Util

object WindowsProfileProtection : ProfileProtector {
    override val scheme = "windows-dpapi"
    override fun seal(plaintext: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plaintext)
    override fun open(ciphertext: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(ciphertext)
}
