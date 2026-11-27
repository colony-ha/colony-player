package com.colony.player.device

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

data class DeviceIdentity(val address: String, val key: String) {
    companion object {
        private const val PREFS = "colony_player_identity_v2"

        fun load(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val address = prefs.getString("address", null) ?: createAddress()
            val key = prefs.getString("key", null) ?: createKey()
            prefs.edit().putString("address", address).putString("key", key).apply()
            return DeviceIdentity(address, key)
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
