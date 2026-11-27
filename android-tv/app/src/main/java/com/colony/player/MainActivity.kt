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
        deviceId = prefs.getString("device_id", null)?.takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) } ?: randomDeviceId().also { prefs.edit().putString("device_id", it).apply() }
        deviceKey = prefs.getString("device_key", null)?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: randomDeviceKey().also { prefs.edit().putString("device_key", it).apply() }
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        // Always start on the exact approved activation artwork.
        // The user presses RELOAD after adding a playlist on the website.
        showActivationScreen()
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

    private var selectedSection = "الكل"
    private var selectedPlaylistName = "مكتبتي"
    private val sectionNames = listOf("الكل", "مباشر", "أفلام", "مسلسلات", "المفضلة")
    private val cardPalette = listOf(0xFF17243A.toInt(), 0xFF282033.toInt(), 0xFF18332F.toInt(), 0xFF33251C.toInt(), 0xFF1D2B3B.toInt())

    private fun showPlaylists(list: JSONArray) {
        val names = mutableListOf<Pair<String, String>>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            names.add(item.optString("id") to item.optString("name", "قائمة تشغيل"))
        }
        if (names.isEmpty()) {
            playlistId = ""
            streams = emptyList()
            showActivationScreen()
            Toast.makeText(this, "لم نعثر على قائمة تشغيل بعد. أضف قائمتك من الموقع ثم اضغط RELOAD.", Toast.LENGTH_LONG).show()
            return
        }
        if (playlistId.isBlank()) {
            playlistId = names.first().first
            selectedPlaylistName = names.first().second
        }
        openPlaylist()
    }

    private fun openPlaylist() {
        lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { api("GET", "/playlists/$playlistId/source") }
                if (!source.optBoolean("ok")) error(apiError(source))
                val playlistUrl = source.getJSONObject("playlist").getString("url")
                val playlistText = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(playlistUrl)
                        .header("User-Agent", "COLONY PLAYER/1.0")
                        .header("Accept", "*/*").build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("تعذر تحميل القائمة: HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }
                }
                streams = parseM3u(playlistText)
                if (streams.isEmpty()) {
                    showConnectionError("القائمة فارغة أو ليست بصيغة M3U مدعومة حالياً.")
                } else {
                    selectedSection = "الكل"
                    showLibrary()
                }
            } catch (e: Exception) {
                showConnectionError(e.message ?: "تعذر فتح قائمة التشغيل")
            }
        }
    }

    private fun parseM3u(text: String): List<StreamItem> {
        val result = mutableListOf<StreamItem>()
        var pendingName = ""
        var pendingGroup = "عام"
        var pendingLogo = ""
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("#EXTINF", true)) {
                val group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                val logo = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                pendingGroup = group?.takeIf { it.isNotBlank() } ?: "عام"
                pendingLogo = logo.orEmpty()
                pendingName = line.substringAfterLast(",", "").trim().ifBlank {
                    Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
                }
            } else if (!line.startsWith("#") && (line.startsWith("http://", true) || line.startsWith("https://", true))) {
                val classification = (pendingGroup + " " + pendingName).lowercase()
                val kind = when {
                    listOf("movie", "vod", "film", "أفلام", "فيلم").any { it in classification } -> "movie"
                    listOf("series", "show", "مسلسل", "مسلسلات").any { it in classification } -> "series"
                    else -> "live"
                }
                result.add(StreamItem(pendingName.ifBlank { "قناة ${result.size + 1}" }, line, pendingGroup, pendingLogo, kind))
                pendingName = ""
                pendingGroup = "عام"
                pendingLogo = ""
            }
        }
        return result
    }

    private fun showEmptyLibrary() {
        buildShell("الرئيسية", "أهلاً بك في COLONY PLAYER")
        val panel = panel(0xFF17191F.toInt())
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(30, 28, 30, 28)
        addText(panel, "ابدأ مكتبتك الخاصة", 27, GOLD, true)
        addText(panel, "أضف قوائم M3U أو Xtream من موقع COLONY PLAYER، وستظهر هنا تلقائياً.", 17, MUTED)
        addAction(panel, "تحديث القوائم", true) { loadPlaylists() }
        addAction(panel, "عرض بيانات الجهاز", false) { deviceDialog() }
        content.addView(panel, matchWrap())
        addFooter()
    }

    private lateinit var content: LinearLayout
    private lateinit var sideNav: LinearLayout
    private lateinit var topTitle: TextView

    private fun showLibrary() {
        // The approved artwork is the complete home screen. Keep it pixel-for-pixel;
        // invisible focusable hit areas only connect the pictured controls.
        val screen = FrameLayout(this)
        val artwork = ImageView(this).apply {
            setImageResource(R.drawable.colony_home_screen)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "COLONY PLAYER home screen"
        }
        screen.addView(artwork, FrameLayout.LayoutParams(-1, -1))

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        fun hit(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
            val outline = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((3 * metrics.density).toInt().coerceAtLeast(2), GOLD)
                cornerRadius = 18 * metrics.density
            }
            val target = View(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnFocusChangeListener { v, focused -> v.background = if (focused) outline else null }
                setOnClickListener { action() }
                contentDescription = "COLONY PLAYER control"
            }
            val lp = FrameLayout.LayoutParams((screenW * w).toInt(), (screenH * h).toInt(), Gravity.TOP or Gravity.LEFT)
            lp.leftMargin = (screenW * x).toInt()
            lp.topMargin = (screenH * y).toInt()
            screen.addView(target, lp)
        }

        // Three large category panels in the exact positions of the supplied image.
        hit(0.025f, 0.285f, 0.312f, 0.435f) { showSection("مباشر") }
        hit(0.351f, 0.285f, 0.303f, 0.435f) { showSection("أفلام") }
        hit(0.668f, 0.285f, 0.309f, 0.435f) { showSection("مسلسلات") }

        // Bottom controls: FAVORITES / HISTORY / UPDATE / LOGOUT / DEVICE INFO.
        hit(0.025f, 0.820f, 0.187f, 0.118f) { showSection("المفضلة") }
        hit(0.212f, 0.820f, 0.198f, 0.118f) { showHistory() }
        hit(0.410f, 0.820f, 0.187f, 0.118f) { loadPlaylists() }
        hit(0.597f, 0.820f, 0.190f, 0.118f) {
            AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج")
                .setMessage("هل تريد الرجوع إلى شاشة إضافة قائمة التشغيل؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تأكيد") { _, _ ->
                    playlistId = ""
                    streams = emptyList()
                    showEmptyLibrary()
                }.show()
        }
        hit(0.787f, 0.820f, 0.190f, 0.118f) { deviceDialog() }

        // Header gear and information symbols already present in the approved artwork.
        hit(0.858f, 0.075f, 0.045f, 0.105f) { showHomeSettings() }
        hit(0.940f, 0.075f, 0.045f, 0.105f) { deviceDialog() }

        setContentView(screen)
        screen.post { screen.getChildAt(1)?.requestFocus() }
    }

    private fun showHomeSettings() {
        AlertDialog.Builder(this)
            .setTitle("COLONY PLAYER")
            .setItems(arrayOf("تحديث القوائم", "عرض بيانات الجهاز")) { _, which ->
                if (which == 0) loadPlaylists() else deviceDialog()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showHistory() {
        val historyUrls = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("history", "")!!.split("\\n").filter { it.isNotBlank() }
        val recent = historyUrls.mapNotNull { url -> streams.firstOrNull { it.url == url } }
        buildShell("HISTORY", "سجل المشاهدة")
        if (recent.isEmpty()) {
            addText(content, "لا يوجد سجل مشاهدة بعد.", 19, MUTED)
        } else {
            recent.forEachIndexed { index, item ->
                content.addView(streamCard(item, index), matchWrap())
            }
        }
        addAction(content, "العودة للرئيسية", true) { showLibrary() }
    }

    private fun filteredStreams(): List<StreamItem> = streams.filter {
        when (selectedSection) {
            "مباشر" -> it.kind == "live"
            "أفلام" -> it.kind == "movie"
            "مسلسلات" -> it.kind == "series"
            "المفضلة" -> favorites.contains(it.url)
            else -> true
        }
    }

    private fun showSection(section: String) {
        selectedSection = section
        val visible = filteredStreams()
        val title = when (section) {
            "مباشر" -> "LIVE TV"
            "أفلام" -> "MOVIES"
            "مسلسلات" -> "SERIES"
            "المفضلة" -> "FAVORITES"
            else -> "ALL CONTENT"
        }
        buildShell(title, "${visible.size} عنصر في قائمتك")
        if (visible.isEmpty()) {
            addText(content, "لا توجد عناصر في هذا القسم حالياً.", 19, MUTED)
            addAction(content, "تحديث القوائم", true) { loadPlaylists() }
            return
        }
        val grouped = visible.groupBy { it.group.ifBlank { "عام" } }
        var index = 0
        grouped.forEach { (groupName, items) ->
            addText(content, groupName, 18, GOLD, true)
            val grid = android.widget.GridLayout(this).apply {
                columnCount = 4
                useDefaultMargins = false
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            }
            items.forEach { item ->
                grid.addView(streamCard(item, index++), android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = 150
                    columnSpec = android.widget.GridLayout.spec((index - 1) % 4, 1f)
                    setMargins(6, 6, 6, 6)
                })
            }
            content.addView(grid, matchWrap(0, 4, 0, 14))
        }
    }

    private fun streamCard(item: StreamItem, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(16, 12, 16, 14)
            background = rounded(cardPalette[index % cardPalette.size], 18, 0xFF39404A.toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener { play(item) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = rounded(if (hasFocus) 0xFF3A3020.toInt() else cardPalette[index % cardPalette.size], 18,
                    if (hasFocus) GOLD else 0xFF39404A.toInt(), if (hasFocus) 3 else 1)
            }
        }
        val icon = when(item.kind) { "movie" -> "▶"; "series" -> "▤"; else -> "◉" }
        addText(card, icon, 25, GOLD, true)
        addText(card, item.name, 17, WHITE, true, 2)
        addText(card, item.group, 12, MUTED, false, 1)
        return card
    }

    private fun addStat(parent: LinearLayout, label: String, value: String, icon: String) {
        val box = panel(0xFF17191F.toInt())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(17, 13, 17, 13)
        addText(box, icon + "  " + label, 13, MUTED, true)
        addText(box, value, 24, GOLD, true)
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 9, 4, 9) })
    }

    private fun buildShell(active: String, subtitle: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080808.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(28, 18, 28, 14)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topTitle = TextView(this).apply {
            text = active
            textSize = 25f
            setTextColor(GOLD)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        titles.addView(topTitle)
        addText(titles, subtitle, 13, MUTED)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(makeAction("رجوع", false) { showLibrary() })
        root.addView(top, matchWrap())
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 5, 0, 24)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addNav(icon: String, label: String, active: Boolean, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13, 11, 13, 11)
            background = rounded(if (active) 0xFF2D261A.toInt() else 0xFF101319.toInt(), 12,
                if (active) GOLD else 0xFF252A32.toInt(), if (active) 2 else 1)
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        addText(item, icon, 18, if (active) GOLD else MUTED, true)
        addText(item, label, 15, if (active) WHITE else MUTED, active)
        sideNav.addView(item, LinearLayout.LayoutParams(-1, 49).apply { bottomMargin = 5 })
    }

    private fun panel(color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 18, 0xFF282D35.toInt())
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 1): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(strokeWidth, stroke)
        }

    private fun matchWrap(l: Int = 0, t: Int = 8, r: Int = 0, b: Int = 8) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(l, t, r, b) }

    private fun addText(parent: LinearLayout, value: String, size: Int, color: Int,
                        bold: Boolean = false, maxLines: Int = Int.MAX_VALUE): TextView {
        val v = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            this.maxLines = maxLines
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2, 4, 2, 4)
        }
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun makeAction(label: String, primary: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFF17130A.toInt() else WHITE)
            background = rounded(if (primary) GOLD else 0xFF222831.toInt(), 12,
                if (primary) GOLD else 0xFF3A414C.toInt())
            setPadding(18, 11, 18, 11)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (primary || focused) GOLD else 0xFF222831.toInt(), 12,
                    if (focused) WHITE else if (primary) GOLD else 0xFF3A414C.toInt(), 2)
                setTextColor(if (primary || focused) 0xFF17130A.toInt() else WHITE)
            }
            setOnClickListener { action() }
        }

    private fun addAction(parent: LinearLayout, label: String, primary: Boolean, action: () -> Unit) {
        parent.addView(makeAction(label, primary, action), matchWrap(0, 9, 0, 0))
    }

    private fun addFooter() {
        addText(content, "COLONY PLAYER  •  YOUR ENTERTAINMENT. YOUR PLAYER.", 11, MUTED)
    }

    private fun search() {
        val input = EditText(this).apply { hint = "اسم القناة أو الفيلم"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("بحث في مكتبتك").setView(input)
            .setPositiveButton("بحث") { _, _ ->
                val q = input.text.toString().trim()
                showSearch(streams.filter { it.name.contains(q, true) || it.group.contains(q, true) })
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showSearch(found: List<StreamItem>) {
        buildShell("نتائج البحث", "نتائج مطابقة لبحثك")
        addText(content, "تم العثور على ${found.size} عنصر", 18, GOLD, true)
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        found.take(90).forEachIndexed { index, item ->
            grid.addView(streamCard(item, index), android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = 145
                columnSpec = android.widget.GridLayout.spec(index % 3, 1f)
                setMargins(5, 5, 5, 5)
            })
        }
        content.addView(grid, matchWrap())
        addAction(content, "رجوع للمكتبة", false) { showLibrary() }
    }

    private fun play(stream: StreamItem) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val history = (listOf(stream.url) + prefs.getString("history", "").orEmpty().split("\\n").filter { it.isNotBlank() }.filter { it != stream.url }).take(100)
        prefs.edit().putString("history", history.joinToString("\\n")).apply()
        buildShell(stream.name, "مشغل الوسائط")
        val playerPanel = panel(0xFF050608.toInt())
        playerPanel.setPadding(5, 5, 5, 5)
        val view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
        }
        playerPanel.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(playerPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = 12 })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(makeAction(if (favorites.contains(stream.url)) "★ إزالة من المفضلة" else "☆ أضف للمفضلة", false) {
            if (!favorites.add(stream.url)) favorites.remove(stream.url)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet("favorites", favorites.toSet()).apply()
            play(stream)
        })
        controls.addView(makeAction("العودة للمكتبة", true) {
            player?.release(); player = null; showLibrary()
        })
        content.addView(controls, matchWrap())
        player?.release()
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(stream.url))
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun showSubscription(entitlement: JSONObject?) {
        showActivationScreen(
            if (entitlement == null) "DEVICE NOT ACTIVATED" else "SUBSCRIPTION INACTIVE"
        )
    }

    private fun showActivationScreen(status: String = "DEVICE NOT ACTIVATED") {
        val frame = FrameLayout(this)
        val artwork = ImageView(this).apply {
            setImageResource(R.drawable.colony_activation_screen)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "COLONY PLAYER device activation"
        }
        frame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        fun control(x: Float, y: Float, width: Float, height: Float, action: () -> Unit) {
            val view = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isFocusable = true
                isClickable = true
                setOnClickListener { action() }
                setOnFocusChangeListener { v, focused ->
                    if (focused) v.background = rounded(android.graphics.Color.TRANSPARENT, 8, GOLD, 2)
                    else v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            frame.addView(view, FrameLayout.LayoutParams((w * width).toInt(), (h * height).toInt(), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = (w * x).toInt()
                topMargin = (h * y).toInt()
            })
        }

        // Invisible remote/click zones over the controls in the approved image.
        control(0.24f, 0.56f, 0.28f, 0.10f) { copyToClipboard("MAC Address", deviceId) }
        control(0.55f, 0.56f, 0.24f, 0.10f) { copyToClipboard("Device Key", deviceKey) }
        control(0.30f, 0.88f, 0.40f, 0.10f) {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "جارٍ التحقق من الجهاز وتحميل القائمة…", Toast.LENGTH_SHORT).show()
                    val reg = withContext(Dispatchers.IO) {
                        api("POST", "/device/register", JSONObject().put("deviceId", deviceId).put("deviceKey", deviceKey))
                    }
                    if (!reg.optBoolean("ok")) error(apiError(reg))
                    authenticate()
                } catch (e: Exception) {
                    showActivationScreen()
                    Toast.makeText(this@MainActivity, e.message ?: "تعذر الاتصال بالخادم", Toast.LENGTH_LONG).show()
                }
            }
        }
        control(0.24f, 0.38f, 0.54f, 0.09f) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://colony-player.pages.dev")))
        }

        // Actual device credentials overlay the blank fields in the approved artwork.
        fun value(text: String, x: Float, y: Float, width: Float) {
            val v = TextView(this).apply {
                this.text = text
                textSize = 20f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
            }
            frame.addView(v, FrameLayout.LayoutParams((w * width).toInt(), (h * 0.055f).toInt(), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = (w * x).toInt()
                topMargin = (h * y).toInt()
            })
        }
        value(deviceId, 0.27f, 0.575f, 0.25f)
        value(deviceKey, 0.57f, 0.575f, 0.20f)

        setContentView(frame)
        frame.post { frame.getChildAt(1)?.requestFocus() }
    }

    private fun showConnectionError(message: String) {
        buildShell("تعذر الاتصال", "تحقق من اتصال الإنترنت")
        val panel = panel(0xFF211A17.toInt())
        panel.setPadding(24, 20, 24, 20)
        addText(panel, message, 20, GOLD, true)
        addText(panel, "تأكد من اتصال التلفاز بالإنترنت ثم أعد المحاولة.", 16, MUTED)
        content.addView(panel, matchWrap())
        addAction(content, "إعادة المحاولة", true) { recreate() }
        addAction(content, "بيانات الجهاز", false) { deviceDialog() }
    }

    private fun showMessage(title: String, message: String) {
        buildShell(title, "YOUR ENTERTAINMENT. YOUR PLAYER.")
        addText(content, message, 20, WHITE, true)
        addText(content, "DEVICE ID", 12, MUTED, true)
        addText(content, deviceId, 22, GOLD, true)
        addText(content, "DEVICE KEY", 12, MUTED, true)
        addText(content, deviceKey, 22, WHITE, true)
        addAction(content, "نسخ Device ID", false) { copyToClipboard("Device ID", deviceId) }
        addAction(content, "نسخ Device Key", false) { copyToClipboard("Device Key", deviceKey) }
    }

    private fun deviceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        addText(box, "DEVICE ID", 12, MUTED, true)
        addText(box, deviceId, 23, GOLD, true)
        addAction(box, "نسخ Device ID", false) { copyToClipboard("Device ID", deviceId) }
        addText(box, "DEVICE KEY", 12, MUTED, true)
        addText(box, deviceKey, 23, WHITE, true)
        addAction(box, "نسخ Device Key", false) { copyToClipboard("Device Key", deviceKey) }
        addText(box, "أدخل هاتين المعلومتين في موقع COLONY PLAYER. لا تشارك Device Key مع أي شخص.", 14, MUTED)
        AlertDialog.Builder(this).setTitle("ربط جهازك")
            .setView(box).setPositiveButton("حسناً", null).show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label تم نسخه", Toast.LENGTH_SHORT).show()
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

    private fun randomDeviceId(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..6).joinToString(":") { "%02X".format(random.nextInt(256)) }
    }

    private fun randomDeviceKey(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
}
