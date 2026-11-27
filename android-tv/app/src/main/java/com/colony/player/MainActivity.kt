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
            showEmptyLibrary()
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
        buildShell("الرئيسية", "مرحباً بك في مكتبتك")
        val hero = panel(0xFF171A20.toInt())
        hero.orientation = LinearLayout.HORIZONTAL
        hero.gravity = Gravity.CENTER_VERTICAL
        hero.setPadding(30, 22, 30, 22)
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(heroCopy, "YOUR ENTERTAINMENT.", 13, GOLD, true)
        addText(heroCopy, "كل ما تحب… في مكان واحد", 27, WHITE, true)
        addText(heroCopy, "$selectedPlaylistName  ·  ${streams.size} عنصر", 15, MUTED)
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val heroButton = makeAction("استكشف المكتبة", true) { showSection("الكل") }
        hero.addView(heroButton)
        content.addView(hero, matchWrap())

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val liveCount = streams.count { it.kind == "live" }
        val movieCount = streams.count { it.kind == "movie" }
        val seriesCount = streams.count { it.kind == "series" }
        addStat(stats, "قنوات مباشرة", liveCount.toString(), "◉")
        addStat(stats, "أفلام", movieCount.toString(), "▣")
        addStat(stats, "مسلسلات", seriesCount.toString(), "▤")
        content.addView(stats, matchWrap())

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addText(heading, "تصفح المحتوى", 21, WHITE, true)
        content.addView(heading, matchWrap(0, 15, 0, 8))

        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sectionNames.forEach { label ->
            categories.addView(makeAction(label, selectedSection == label) { showSection(label) },
                LinearLayout.LayoutParams(-2, 48).apply { marginEnd = 9 })
        }
        content.addView(categories, matchWrap(0, 0, 0, 12))

        val visible = filteredStreams()
        addText(content, when(selectedSection) {
            "مباشر" -> "القنوات المباشرة"
            "أفلام" -> "الأفلام"
            "مسلسلات" -> "المسلسلات"
            "المفضلة" -> "المفضلة"
            else -> "أضيفت إلى مكتبتك"
        } + "  ·  ${visible.size}", 19, GOLD, true)
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        visible.take(90).forEachIndexed { index, item ->
            grid.addView(streamCard(item, index), android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = 148
                columnSpec = android.widget.GridLayout.spec(index % 3, 1f)
                setMargins(5, 5, 5, 5)
            })
        }
        content.addView(grid, matchWrap())
        if (visible.isEmpty()) addText(content, "لا توجد عناصر في هذا القسم حالياً.", 16, MUTED)
        addFooter()
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
        showLibrary()
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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B0D11.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        sideNav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 22, 15, 18)
            setBackgroundColor(0xFF101319.toInt())
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 7, 8, 24)
        }
        addText(brand, "C  ◈", 31, GOLD, true)
        addText(brand, "COLONY", 20, WHITE, true)
        addText(brand, "PLAYER", 12, GOLD, true)
        sideNav.addView(brand)
        addNav("⌂", "الرئيسية", active == "الرئيسية") { showLibrary() }
        addNav("◉", "مباشر", selectedSection == "مباشر") { showSection("مباشر") }
        addNav("▣", "أفلام", selectedSection == "أفلام") { showSection("أفلام") }
        addNav("▤", "مسلسلات", selectedSection == "مسلسلات") { showSection("مسلسلات") }
        addNav("★", "المفضلة", selectedSection == "المفضلة") { showSection("المفضلة") }
        addNav("⌕", "بحث", false) { search() }
        val spacer = View(this)
        sideNav.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        addNav("▤", "قوائم التشغيل", false) { loadPlaylists() }
        addNav("⚙", "الجهاز", false) { deviceDialog() }
        root.addView(sideNav, LinearLayout.LayoutParams(205, -1))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 12)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topTitle = TextView(this).apply {
            text = active
            textSize = 22f
            setTextColor(WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        titles.addView(topTitle)
        addText(titles, subtitle, 12, MUTED)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(makeAction("بحث  ⌕", false) { search() })
        top.addView(makeAction("تحديث", false) { loadPlaylists() })
        main.addView(top)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 3, 0, 24)
        }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(0xFF050505.toInt())
            setPadding(22, 18, 22, 18)
        }

        val brandPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 20, 18, 20)
            background = rounded(0xFF0A0A0A.toInt(), 22, GOLD, 2)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.colony_player_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "COLONY PLAYER"
        }
        brandPanel.addView(logo, LinearLayout.LayoutParams(-1, 0, 1f))
        addText(brandPanel, "STREAM  ·  ENJOY  ·  MORE", 14, GOLD, true).gravity = Gravity.CENTER
        root.addView(brandPanel, LinearLayout.LayoutParams(0, -1, 0.34f).apply { marginEnd = 22 })

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val statusView = TextView(this).apply {
            text = "⚠  $status"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(GOLD)
            gravity = Gravity.CENTER
            background = rounded(0xFF15110A.toInt(), 28, GOLD, 2)
            setPadding(24, 12, 24, 12)
        }
        details.addView(statusView, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 12 })

        addText(details, "To continue using the app", 31, WHITE, true).gravity = Gravity.CENTER
        addText(details, "Please activate your device to start adding playlists.", 17, WHITE).gravity = Gravity.CENTER

        val separator = View(this).apply { setBackgroundColor(GOLD) }
        details.addView(separator, LinearLayout.LayoutParams(-1, 2).apply { topMargin = 14; bottomMargin = 14 })

        addText(details, "Adding Playlist", 23, GOLD, true)
        addText(details, "Visit our website:", 17, WHITE)
        val website = makeAction("◎   colony-player.pages.dev   ›", true) {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://colony-player.pages.dev")))
            } catch (_: Exception) {
                Toast.makeText(this, "Unable to open website on this device", Toast.LENGTH_SHORT).show()
            }
        }
        website.textSize = 20f
        details.addView(website, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 6; bottomMargin = 12 })

        addText(details, "Log in using your Device Address and Key", 20, GOLD, true)
        val credentials = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF191A20.toInt(), 16, 0xFF383B43.toInt())
            setPadding(18, 12, 18, 12)
        }
        val macColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(macColumn, "MAC ADDRESS", 13, MUTED, true)
        val macValue = TextView(this).apply {
            text = deviceId
            textSize = 21f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(WHITE)
            setPadding(2, 5, 2, 5)
        }
        macColumn.addView(macValue)
        macColumn.addView(makeAction("COPY MAC", false) { copyToClipboard("MAC Address", deviceId) })
        credentials.addView(macColumn, LinearLayout.LayoutParams(0, -2, 1.25f))

        val divider = View(this).apply { setBackgroundColor(0xFF66616A.toInt()) }
        credentials.addView(divider, LinearLayout.LayoutParams(1, 70).apply { marginStart = 14; marginEnd = 14 })

        val keyColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(keyColumn, "DEVICE KEY", 13, MUTED, true)
        val keyValue = TextView(this).apply {
            text = deviceKey
            textSize = 23f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(WHITE)
            setPadding(2, 5, 2, 5)
        }
        keyColumn.addView(keyValue)
        keyColumn.addView(makeAction("COPY KEY", false) { copyToClipboard("Device Key", deviceKey) })
        credentials.addView(keyColumn, LinearLayout.LayoutParams(0, -2, 0.8f))
        details.addView(credentials, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 12 })

        addText(details, "Activate your device and enjoy!", 21, WHITE, true).gravity = Gravity.CENTER
        val reload = makeAction("⟳   RELOAD", true) {
            lifecycleScope.launch {
                try {
                    authenticate()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "Activation check failed", Toast.LENGTH_LONG).show()
                }
            }
        }
        reload.textSize = 23f
        details.addView(reload, LinearLayout.LayoutParams(260, 62).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 12
        })
        root.addView(details, LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(root)
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
