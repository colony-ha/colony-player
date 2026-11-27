package com.colony.player.device

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Stable identity for this Android TV installation/device.
 *
 * ANDROID_ID is scoped to the Android user + app signing key on modern Android,
 * and unlike app preferences it normally survives uninstall/reinstall.
 * The MAC-shaped value is an app device identifier, NOT the hardware Wi-Fi MAC.
 */
data class DeviceIdentity(val address: String, val key: String) {
    companion object {
        private const val PREFS = "colony_player_identity_v3"
        private const val ADDRESS_SALT = "COLONY_PLAYER_DEVICE_ADDRESS_V3"
        private const val KEY_SALT = "COLONY_PLAYER_DEVICE_KEY_V3"

        fun load(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("9774d56d682e549c", ignoreCase = true)
            }

            val identity = if (androidId != null) {
                val stableAddress = deriveAddress(androidId)
                val stableKey = deriveKey(androidId)
                DeviceIdentity(stableAddress, stableKey)
            } else {
                // Rare fallback for devices that do not expose ANDROID_ID.
                val address = prefs.getString("address", null) ?: createAddress()
                val key = prefs.getString("key", null) ?: createKey()
                DeviceIdentity(address, key)
            }

            prefs.edit()
                .putString("address", identity.address)
                .putString("key", identity.key)
                .apply()
            return identity
        }

        private fun digest(namespace: String, androidId: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest("$namespace:$androidId".toByteArray(Charsets.UTF_8))

        private fun deriveAddress(androidId: String): String {
            val bytes = digest(ADDRESS_SALT, androidId)
            // Mark as locally administered and unicast, like a generated MAC address.
            val first = (bytes[0].toInt() and 0xFC) or 0x02
            val octets = byteArrayOf(first.toByte()) + bytes.copyOfRange(1, 6)
            return octets.joinToString(":") {
                String.format(Locale.US, "%02X", it.toInt() and 0xFF)
            }
        }

        private fun deriveKey(androidId: String): String {
            val bytes = digest(KEY_SALT, androidId)
            val value = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
            return String.format(Locale.US, "%06d", value % 1_000_000L)
        }

        private fun createAddress(): String {
            val random = SecureRandom()
            return (0 until 6).joinToString(":") {
                String.format(Locale.US, "%02X", random.nextInt(256))
            }
        }

        private fun createKey(): String =
            String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
    }
}
