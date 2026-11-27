package com.colony.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val API = "https://colony-player.pages.dev/api"
private const val PREFS = "colony_player_device"
private const val BLACK = 0xFF080808.toInt()
private const val PANEL = 0xFF151515.toInt()
private const val GOLD = 0xFFE0B65B.toInt()
private const val WHITE = 0xFFF5F1E8.toInt()
private const val MUTED = 0xFFAAA39A.toInt()

data class MediaEntry(val title: String, val url: String, val group: String, val logo: String, val type: String)

class MainActivity : Activity() {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private lateinit var deviceId: String
    private lateinit var deviceKey: String
    private var token = ""
    private var media: List<MediaEntry> = emptyList()
    private var player: ExoPlayer? = null
    private lateinit var root: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: newDeviceId().also { prefs.edit().putString("device_id", it).apply() }
        deviceKey = prefs.getString("device_key", null) ?: newDeviceKey().also { prefs.edit().putString("device_key", it).apply() }
        showActivation()
        registerInBackground()
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(API + path).header("Accept", "application/json")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (!response.isSuccessful && !json.has("error")) json.put("error", "HTTP_${response.code}")
            return json
        }
    }

    private fun registerInBackground() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    request("POST", "/device/register", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey))
                }
            } catch (_: Exception) { }
        }
    }

    private fun reload() {
        if (busy) return
        busy = true
        showLoading("جارٍ التحقق من الجهاز...")
        lifecycleScope.launch {
            try {
                val registered = withContext(Dispatchers.IO) {
                    request("POST", "/device/register", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey))
                }
                if (!registered.optBoolean("ok")) error(messageFor(registered.optString("error")))
                val login = withContext(Dispatchers.IO) {
                    request("POST", "/device/login", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey))
                }
                if (!login.optBoolean("ok")) error(messageFor(login.optString("error")))
                token = login.getString("token")
                if (!login.optJSONObject("device")?.optBoolean("playbackAllowed", false)!!) {
                    showActivation("الاشتراك غير نشط. فعّل جهازك من الموقع.")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { request("GET", "/playlists") }
                if (!result.optBoolean("ok")) error(messageFor(result.optString("error")))
                val playlists = result.optJSONArray("playlists")
                if (playlists == null || playlists.length() == 0) {
                    showActivation("أضف قائمة التشغيل من الموقع ثم اضغط RELOAD.")
                    return@launch
                }
                val first = playlists.getJSONObject(0)
                val id = first.optString("id")
                if (id.isBlank()) error("معرّف قائمة التشغيل غير صالح.")
                val source = withContext(Dispatchers.IO) { request("GET", "/playlists/${Uri.encode(id)}/source") }
                if (!source.optBoolean("ok")) error(messageFor(source.optString("error")))
                val url = source.getJSONObject("playlist").getString("url")
                val text = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).header("User-Agent", "COLONY PLAYER/1.0").header("Accept", "*/*").build()
                    http.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("فشل تحميل القائمة: HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }
                }
                val parsed = M3uParser.parse(text)
                if (parsed.isEmpty()) error("الرابط لم يُرجع قائمة M3U صالحة أو لا يحتوي على قنوات.")
                media = parsed
                showHome()
            } catch (e: Exception) {
                showActivation(e.message ?: "تعذر تحميل قائمة التشغيل.")
            } finally {
                busy = false
            }
        }
    }

    private fun messageFor(code: String): String = when (code) {
        "DEVICE_BLOCKED" -> "هذا الجهاز موقوف من الإدارة."
        "SUBSCRIPTION_EXPIRED" -> "انتهت التجربة أو الاشتراك. فعّل جهازك من الموقع."
        "DEVICE_NOT_AUTHORIZED" -> "بيانات الجهاز غير صحيحة. تحقق من Device Address و Device Key."
        "DEVICE_ALREADY_REGISTERED" -> "هذا الجهاز مسجل بمفتاح مختلف. لا تغيّر بيانات الجهاز."
        "TOO_MANY_ATTEMPTS" -> "محاولات كثيرة. انتظر قليلاً ثم أعد المحاولة."
        "UNAUTHORIZED" -> "تعذر تسجيل الدخول. أعد المحاولة."
        else -> code.ifBlank { "حدث خطأ غير معروف." }
    }

    private fun baseScreen(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BLACK)
            setPadding(42, 24, 42, 24)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        setContentView(root)
        return root
    }

    private fun showActivation(message: String = "To continue using the app") {
        baseScreen()
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 0.7f))
        text(root, "COLONY PLAYER", 34, GOLD, true, Gravity.CENTER)
        text(root, "To continue using the app", 24, WHITE, true, Gravity.CENTER)
        text(root, "Adding Playlist", 19, GOLD, true, Gravity.CENTER)
        text(root, "Visit colony-player.pages.dev", 17, MUTED, false, Gravity.CENTER).apply {
            isFocusable = true
            setOnClickListener { openWebsite() }
        }
        text(root, "Log in using your Device Address and Key", 18, WHITE, true, Gravity.CENTER)
        val credentials = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        credential(credentials, "MAC Address", deviceId)
        credential(credentials, "Device Key", deviceKey)
        root.addView(credentials, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18; bottomMargin = 18 })
        text(root, "Activate your device and enjoy!", 18, GOLD, true, Gravity.CENTER)
        if (message != "To continue using the app") text(root, message, 15, MUTED, false, Gravity.CENTER)
        action(root, "RELOAD", true) { reload() }
        val bottom = View(this)
        root.addView(bottom, LinearLayout.LayoutParams(1, 0, 0.7f))
    }

    private fun credential(parent: LinearLayout, label: String, value: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 14, 18, 14)
            background = rounded(PANEL, 12, 0xFF4B402B.toInt())
        }
        text(column, label, 14, MUTED, true, Gravity.CENTER)
        text(column, value, 21, WHITE, true, Gravity.CENTER)
        action(column, "COPY", false) { copy(label, value) }
        parent.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(10, 0, 10, 0) })
    }

    private fun showLoading(label: String) {
        baseScreen()
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        text(root, "COLONY PLAYER", 32, GOLD, true, Gravity.CENTER)
        text(root, label, 20, WHITE, false, Gravity.CENTER)
        val progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(54, 54).apply { gravity = Gravity.CENTER; topMargin = 24 })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }

    private fun showHome() {
        baseScreen()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        text(header, "COLONY PLAYER", 30, GOLD, true, Gravity.CENTER)
        val filler = View(this); header.addView(filler, LinearLayout.LayoutParams(0, 1, 1f))
        action(header, "DEVICE INFO", false) { deviceInfo() }
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 26 })
        text(root, "YOUR ENTERTAINMENT. YOUR PLAYER.", 14, MUTED, true, Gravity.CENTER)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; weightSum = 3f }
        homeCard(cards, "LIVE TV", "قنوات البث المباشر", "live")
        homeCard(cards, "MOVIES", "مكتبة الأفلام", "movie")
        homeCard(cards, "SERIES", "المسلسلات", "series")
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24; bottomMargin = 22 })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; weightSum = 5f }
        actionWeight(footer, "FAVORITES") { showList("favorites") }
        actionWeight(footer, "HISTORY") { showHistory() }
        actionWeight(footer, "UPDATE") { reload() }
        actionWeight(footer, "LOGOUT") { confirmLogout() }
        actionWeight(footer, "DEVICE INFO") { deviceInfo() }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
    }

    private fun homeCard(parent: LinearLayout, title: String, subtitle: String, type: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
            background = rounded(0xFF171717.toInt(), 16, GOLD, 2)
            isFocusable = true
            isClickable = true
            setOnClickListener { showList(type) }
            setOnFocusChangeListener { v, focus -> v.background = rounded(if (focus) 0xFF332817.toInt() else 0xFF171717.toInt(), 16, GOLD, if (focus) 4 else 2) }
        }
        text(card, title, 26, GOLD, true, Gravity.CENTER)
        text(card, subtitle, 16, WHITE, false, Gravity.CENTER)
        parent.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(12, 0, 12, 0) })
    }

    private fun showList(type: String) {
        val selected = when (type) {
            "live" -> media.filter { it.type == "live" }
            "movie" -> media.filter { it.type == "movie" }
            "series" -> media.filter { it.type == "series" }
            "favorites" -> media.filter { isFavorite(it.url) }
            else -> media
        }
        baseScreen()
        val heading = when (type) { "live" -> "LIVE TV"; "movie" -> "MOVIES"; "series" -> "SERIES"; "favorites" -> "FAVORITES"; else -> "LIBRARY" }
        text(root, heading, 28, GOLD, true, Gravity.CENTER)
        text(root, "${selected.size} items", 14, MUTED, false, Gravity.CENTER)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selected.groupBy { it.group }.forEach { (group, items) ->
            text(list, group, 18, GOLD, true)
            items.forEach { entry ->
                val row = action(list, entry.title, false) { play(entry) }
                row.gravity = Gravity.CENTER_VERTICAL
                row.textSize = 17f
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        action(root, "BACK TO HOME", true) { showHome() }
    }

    private fun showHistory() {
        val history = getSharedPreferences(PREFS, MODE_PRIVATE).getString("history", "").orEmpty().split("\n").filter { it.isNotBlank() }
        val items = history.mapNotNull { url -> media.firstOrNull { it.url == url } }
        baseScreen()
        text(root, "HISTORY", 28, GOLD, true, Gravity.CENTER)
        items.forEach { item -> action(root, item.title, false) { play(item) } }
        action(root, "BACK TO HOME", true) { showHome() }
    }

    private fun play(entry: MediaEntry) {
        val old = getSharedPreferences(PREFS, MODE_PRIVATE).getString("history", "").orEmpty().split("\n").filter { it.isNotBlank() }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("history", (listOf(entry.url) + old.filter { it != entry.url }).take(100).joinToString("\n")).apply()
        baseScreen()
        text(root, entry.title, 22, GOLD, true, Gravity.CENTER)
        val view = PlayerView(this).apply { useController = true }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        action(root, "BACK TO LIBRARY", true) { player?.release(); player = null; showHome() }
        player?.release()
        player = ExoPlayer.Builder(this).build().also { it.setMediaItem(MediaItem.fromUri(entry.url)); view.player = it; it.prepare(); it.playWhenReady = true }
    }

    private fun isFavorite(url: String): Boolean = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("favorites", emptySet())?.contains(url) == true

    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("LOGOUT").setMessage("Return to device activation?").setNegativeButton("CANCEL", null)
            .setPositiveButton("LOGOUT") { _, _ -> token = ""; media = emptyList(); showActivation() }.show()
    }

    private fun deviceInfo() {
        AlertDialog.Builder(this).setTitle("DEVICE INFO").setMessage("MAC Address: $deviceId\nDevice Key: $deviceKey")
            .setPositiveButton("COPY ADDRESS") { _, _ -> copy("MAC Address", deviceId) }
            .setNeutralButton("COPY KEY") { _, _ -> copy("Device Key", deviceKey) }.show()
    }

    private fun openWebsite() { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://colony-player.pages.dev"))) }

    private fun copy(label: String, value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun text(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean, gravity: Int = Gravity.START): TextView {
        val view = TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
        return view
    }

    private fun action(parent: LinearLayout, label: String, primary: Boolean, click: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (primary) BLACK else WHITE)
            background = rounded(if (primary) GOLD else PANEL, 12, if (primary) GOLD else 0xFF4B402B.toInt())
            setPadding(18, 13, 18, 13); isFocusable = true; isClickable = true
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (primary || focused) GOLD else PANEL, 12, GOLD, if (focused) 3 else 1); setTextColor(if (primary || focused) BLACK else WHITE) }
            setOnClickListener { click() }
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 })
        return view
    }

    private fun actionWeight(parent: LinearLayout, label: String, click: () -> Unit) {
        val view = TextView(this).apply {
            text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(WHITE); background = rounded(PANEL, 10, 0xFF4B402B.toInt())
            setPadding(8, 15, 8, 15); isFocusable = true; isClickable = true; setOnClickListener { click() }
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) 0xFF332817.toInt() else PANEL, 10, GOLD, 2) }
        }
        parent.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(5, 0, 5, 0) })
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, width: Int = 1) =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat(); if (stroke != 0) setStroke(width, stroke) }

    private fun newDeviceId(): String = (1..6).joinToString(":") { "%02X".format(SecureRandom().nextInt(256)) }
    private fun newDeviceKey(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
