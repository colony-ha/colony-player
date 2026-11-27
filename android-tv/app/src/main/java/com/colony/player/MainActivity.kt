package com.colony.player

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val API = "https://colony-player.pages.dev/api"
private const val PREFS = "colony_player_device"
private const val BG = 0xFF090909.toInt()
private const val GOLD = 0xFFE0B65B.toInt()
private const val WHITE = 0xFFF5F5F5.toInt()
private const val MUTED = 0xFFAAA39A.toInt()

data class StreamItem(val name: String, val url: String, val group: String, val logo: String = "", val kind: String = "live")

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private lateinit var root: LinearLayout
    private var player: ExoPlayer? = null
    private var token = ""
    private lateinit var deviceId: String
    private lateinit var deviceKey: String
    private var streams: List<StreamItem> = emptyList()
    private var playlistId = ""
    private var category = "الكل"
    private val favorites = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: ("CP-" + randomHex(8).uppercase()).also { prefs.edit().putString("device_id", it).apply() }
        deviceKey = prefs.getString("device_key", null) ?: randomToken().also { prefs.edit().putString("device_key", it).apply() }
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        showMessage("جاري تهيئة COLONY PLAYER", "يرجى الانتظار…")
        lifecycleScope.launch {
            try {
                val reg = withContext(Dispatchers.IO) { api("POST", "/device/register", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey)) }
                if (!reg.optBoolean("ok")) error(apiError(reg))
                authenticate()
            } catch (e: Exception) { showConnectionError(e.message ?: "تعذر الاتصال") }
        }
    }

    private suspend fun authenticate() {
        val login = withContext(Dispatchers.IO) { api("POST", "/device/login", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey)) }
        if (!login.optBoolean("ok")) error(apiError(login))
        token = login.getString("token")
        val device = login.getJSONObject("device")
        if (!device.optBoolean("playbackAllowed")) showSubscription(device.optJSONObject("entitlement"))
        else loadPlaylists()
    }

    private fun api(method: String, path: String, data: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(API + path).header("Accept", "application/json")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((data ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (!response.isSuccessful && !result.has("error")) result.put("error", "HTTP ${response.code}")
            return result
        }
    }

    private fun apiError(j: JSONObject): String = when (j.optString("error")) {
        "SUBSCRIPTION_EXPIRED" -> "انتهت التجربة. فعّل الاشتراك من موقع COLONY PLAYER."
        "DEVICE_BLOCKED" -> "هذا الجهاز موقوف من الإدارة."
        "DEVICE_NOT_AUTHORIZED" -> "تعذر التحقق من الجهاز."
        "TOO_MANY_ATTEMPTS" -> "محاولات كثيرة، أعد المحاولة لاحقاً."
        else -> j.optString("error", "حدث خطأ")
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api("GET", "/playlists") }
                if (!result.optBoolean("ok")) error(apiError(result))
                showPlaylists(result.optJSONArray("playlists") ?: JSONArray())
            } catch (e: Exception) {
                if (e.message?.contains("SUBSCRIPTION_EXPIRED") == true || e.message?.contains("DEVICE_BLOCKED") == true) showSubscription(null)
                else showConnectionError(e.message ?: "تعذر تحميل القوائم")
            }
        }
    }

    private fun showPlaylists(list: JSONArray) {
        base("COLONY PLAYER")
        text("قوائم التشغيل", 26, GOLD, true)
        text("اختر قائمة لعرض القنوات والأفلام والمسلسلات", 17, MUTED)
        if (list.length() == 0) {
            text("لا توجد قوائم. أضف قائمة M3U أو Xtream من بوابة الموقع.", 19, WHITE, true)
        } else for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            button(item.optString("name", "قائمة تشغيل") + "  •  " + item.optString("playlist_type", "m3u").uppercase()) {
                playlistId = item.getString("id")
                openPlaylist()
            }
        }
        button("بيانات ربط الجهاز") { deviceDialog() }
        button("تحديث القوائم") { loadPlaylists() }
        text("YOUR ENTERTAINMENT. YOUR PLAYER.", 13, MUTED)
    }

    private fun openPlaylist() {
        showMessage("جاري تحميل القائمة", "قد يستغرق ذلك لحظات حسب حجم القائمة…")
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { api("GET", "/playlists/$playlistId/source") }
                if (!response.optBoolean("ok")) error(apiError(response))
                val p = response.getJSONObject("playlist")
                val raw = withContext(Dispatchers.IO) { downloadPlaylist(p.optString("type"), p.getString("url")) }
                streams = withContext(Dispatchers.Default) { parseM3u(raw) }
                if (streams.isEmpty()) error("لم يتم العثور على عناصر صالحة في القائمة.")
                showLibrary()
            } catch (e: Exception) { showConnectionError(e.message ?: "تعذر تحميل القائمة") }
        }
    }

    private fun downloadPlaylist(type: String, source: String): String {
        val url = if (type == "xtream") xtreamUrl(source) else source
        val request = Request.Builder().url(url).header("User-Agent", "COLONY PLAYER AndroidTV").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("فشل تحميل المصدر: HTTP ${response.code}")
            return response.body?.string()?.take(25_000_000) ?: ""
        }
    }

    private fun xtreamUrl(source: String): String {
        val uri = android.net.Uri.parse(source)
        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return source
        val base = source.substringBefore("?").trimEnd('/')
        return "$base/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"
    }

    private fun parseM3u(content: String): List<StreamItem> {
        val result = mutableListOf<StreamItem>()
        var name = ""
        var group = "عام"
        var logo = ""
        var kind = "live"
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", true)) {
                val comma = line.lastIndexOf(',')
                name = if (comma >= 0) line.substring(comma + 1).trim() else "بدون اسم"
                group = Regex("""group-title=["']([^"']*)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.ifBlank { "عام" } ?: "عام"
                logo = Regex("""tvg-logo=["']([^"']*)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: ""
                kind = when {
                    group.contains("movie", true) || group.contains("vod", true) || group.contains("أفلام") -> "movie"
                    group.contains("series", true) || group.contains("مسلسل") -> "series"
                    else -> "live"
                }
            } else if (line.isNotBlank() && !line.startsWith("#") && name.isNotBlank()) {
                if (line.startsWith("http://") || line.startsWith("https://")) result.add(StreamItem(name, line, group, logo, kind))
                name = ""; group = "عام"; logo = ""; kind = "live"
            }
        }
        return result
    }

    private fun showLibrary() {
        base("مكتبة COLONY PLAYER")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("الكل", "مباشر", "أفلام", "مسلسلات", "المفضلة").forEach { label ->
            bar.addView(makeButton(label) { category = label; showLibrary() })
        }
        root.addView(bar)
        val visible = streams.filter {
            when (category) {
                "مباشر" -> it.kind == "live"
                "أفلام" -> it.kind == "movie"
                "مسلسلات" -> it.kind == "series"
                "المفضلة" -> favorites.contains(it.url)
                else -> true
            }
        }
        text("${visible.size} عنصر", 14, MUTED)
        val scroll = ScrollView(this)
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        visible.take(1500).forEach { stream ->
            val star = if (favorites.contains(stream.url)) " ★" else ""
            items.addView(makeButton(stream.name + star + "  •  " + stream.group) { play(stream) })
        }
        scroll.addView(items)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makeButton("بحث") { search() })
        actions.addView(makeButton("قوائم التشغيل") { loadPlaylists() })
        actions.addView(makeButton("تحديث") { openPlaylist() })
        root.addView(actions)
    }

    private fun search() {
        val input = EditText(this).apply { hint = "اسم القناة أو الفيلم"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("بحث").setView(input)
            .setPositiveButton("بحث") { _, _ ->
                val q = input.text.toString().trim()
                showSearch(streams.filter { it.name.contains(q, true) || it.group.contains(q, true) })
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showSearch(found: List<StreamItem>) {
        base("نتائج البحث")
        text("${found.size} نتيجة", 15, MUTED)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        found.take(500).forEach { item -> list.addView(makeButton(item.name) { play(item) }) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        button("رجوع") { showLibrary() }
    }

    private fun play(stream: StreamItem) {
        base(stream.name)
        val view = PlayerView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(makeButton("إيقاف مؤقت") { player?.pause() })
        controls.addView(makeButton("تشغيل") { player?.play() })
        controls.addView(makeButton(if (favorites.contains(stream.url)) "إزالة من المفضلة" else "أضف للمفضلة") {
            if (!favorites.add(stream.url)) favorites.remove(stream.url)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet("favorites", favorites.toSet()).apply()
            play(stream)
        })
        controls.addView(makeButton("رجوع") { player?.release(); player = null; showLibrary() })
        root.addView(controls)
        player?.release()
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(stream.url))
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun showSubscription(entitlement: JSONObject?) {
        base("الاشتراك")
        text("انتهت صلاحية المشاهدة أو تم إيقاف الجهاز", 24, GOLD, true)
        text("فعّل اشتراكك السنوي من موقع COLONY PLAYER ثم أعد التحقق.", 17, WHITE)
        button("إعادة التحقق") {
            lifecycleScope.launch {
                try { authenticate() }
                catch (e: Exception) { showConnectionError(e.message ?: "فشل التحقق") }
            }
        }
        button("بيانات ربط الجهاز") { deviceDialog() }
    }

    private fun showConnectionError(message: String) {
        base("COLONY PLAYER")
        text(message, 20, GOLD, true)
        text("تحقق من الإنترنت ثم أعد المحاولة.", 16, MUTED)
        button("إعادة المحاولة") { recreate() }
        button("بيانات ربط الجهاز") { deviceDialog() }
    }

    private fun showMessage(title: String, message: String) {
        base(title)
        text(message, 18, WHITE)
        text("Device ID: $deviceId", 14, MUTED)
    }

    private fun deviceDialog() {
        AlertDialog.Builder(this).setTitle("ربط جهازك")
            .setMessage("افتح موقع COLONY PLAYER من هاتفك، وأدخل بيانات هذا الجهاز في بوابة الربط.\\n\\nDevice ID:\\n$deviceId\\n\\nDevice Key:\\n$deviceKey\\n\\nلا تشارك المفتاح مع أي شخص.")
            .setPositiveButton("حسناً", null).show()
    }

    private fun base(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(36, 20, 36, 20)
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        text(title, 29, GOLD, true)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 8)
        }
        root.addView(v)
        return v
    }

    private fun button(label: String, action: () -> Unit) { root.addView(makeButton(label, action)) }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        isFocusable = true
        isFocusableInTouchMode = true
        setTextColor(GOLD)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF24201A.toInt()))
        setPadding(16, 8, 16, 8)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(5, 5, 5, 5) }
    }

    override fun onResume() {
        super.onResume()
        if (token.isNotBlank()) lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api("GET", "/device/me") }
                val allowed = result.optJSONObject("device")?.optBoolean("playbackAllowed", false) ?: false
                if (!result.optBoolean("ok") || !allowed) { player?.pause(); showSubscription(null) }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count); SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
