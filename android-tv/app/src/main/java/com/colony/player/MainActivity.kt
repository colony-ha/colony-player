package com.colony.player

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.colony.player.device.DeviceIdentity
import com.colony.player.model.MediaEntry
import com.colony.player.model.MediaSection
import com.colony.player.network.ApiException
import com.colony.player.network.ColonyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * COLONY PLAYER Android TV client.
 * This entry point is intentionally independent from the previous app implementation.
 * It coordinates screens; device, API, playlist parsing and media models live in
 * separate source files.
 */
class MainActivity : ComponentActivity() {
    private val api = ColonyApi()
    private lateinit var identity: DeviceIdentity
    private lateinit var root: LinearLayout
    private var library: List<MediaEntry> = emptyList()
    private var currentPlayer: ExoPlayer? = null
    private var isLoading = false
    private var activeTitle = "LIVE TV"
    private var activeEntries: List<MediaEntry> = emptyList()
    private var activeSection: MediaSection? = null
    private var activeGroup: String? = null
    private var screenLevel = "ACTIVATION"
    private var catalogPage = 0

    private val preferences by lazy {
        getSharedPreferences("colony_player_library_v2", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        identity = DeviceIdentity.load(this)
        showActivation()
    }

    private fun synchronizeDevice() {
        if (isLoading) return
        isLoading = true
        showLoading("Checking your device...")
        lifecycleScope.launch {
            try {
                val allowed = withContext(Dispatchers.IO) {
                    api.register(identity.address, identity.key)
                    api.signIn(identity.address, identity.key)
                }
                if (!allowed) {
                    showActivation("Your trial or subscription is not active, or this device is blocked.")
                    return@launch
                }

                showLoading("Getting your playlist...")
                val source = withContext(Dispatchers.IO) { api.firstM3uPlaylist() }

                showLoading("Downloading and preparing channels...")
                val entries = withContext(Dispatchers.IO) { api.loadM3u(source.url) }
                library = entries
                showHome()
            } catch (error: Exception) {
                android.util.Log.e("ColonyPlayer", "Device synchronization failed", error)
                val message = when (error) {
                    is ApiException -> explainApiError(error.code)
                    is IllegalArgumentException -> when (error.message) {
                        "NOT_M3U" -> "This source is not an M3U playlist. Only M3U and M3U Plus are supported."
                        "M3U_EMPTY" -> "The M3U playlist contains no playable entries."
                        else -> "The playlist format is invalid."
                    }
                    else -> error.message ?: "Unable to load the playlist."
                }
                showActivation(message)
            } finally {
                isLoading = false
            }
        }
    }

    private fun explainApiError(code: String): String = when (code) {
        "DEVICE_BLOCKED" -> "This device has been blocked by the administrator."
        "SUBSCRIPTION_EXPIRED" -> "Your trial or subscription has expired. Activate this device on the website."
        "DEVICE_NOT_AUTHORIZED", "DEVICE_ALREADY_REGISTERED" -> "Device details were not accepted. Check the address and key."
        "TOO_MANY_ATTEMPTS" -> "Too many attempts. Wait a few minutes, then try again."
        "NO_PLAYLIST" -> "Add an M3U playlist on the website, then press RELOAD."
        "M3U_ONLY" -> "This player accepts M3U and M3U Plus playlists only. Xtream is not supported."
        "INVALID_PLAYLIST_URL" -> "The playlist URL is invalid."
        "NETWORK_ERROR" -> "Network connection failed. Check the TV internet connection."
        "EMPTY_PLAYLIST" -> "The playlist server returned an empty file."
        else -> "Server response: $code"
    }

    private fun newScreen(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BLACK)
            setPadding(42, 22, 42, 22)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        setContentView(root)
        return root
    }

    private fun showActivation(message: String = "To continue using the app") {
        newScreen()
        val metrics = resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        val compact = shortSide < 420
        root.setPadding(if (compact) 18 else 42, if (compact) 8 else 22, if (compact) 18 else 42, if (compact) 10 else 22)

        // Keep RELOAD fixed and visible; allow the information above it to scroll
        // on phones with shorter displays instead of pushing the action off-screen.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, 0, 0, 4)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addBrand(content)
        addText(content, "To continue using the app", if (compact) 21 else 25, IVORY, true, Gravity.CENTER)
        addText(content, "Adding Playlist", if (compact) 17 else 19, GOLD, true, Gravity.CENTER)
        val website = addText(content, "Visit colony-player.pages.dev", if (compact) 14 else 16, MUTED, false, Gravity.CENTER)
        website.isFocusable = true
        website.setOnClickListener { openWebsite() }
        addText(content, "Log in using your Device Address and Key", if (compact) 15 else 18, IVORY, true, Gravity.CENTER)

        val credentials = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addCredential(credentials, "MAC Address", identity.address)
        addCredential(credentials, "Device Key", identity.key)
        content.addView(credentials, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = if (compact) 6 else 18
            bottomMargin = if (compact) 5 else 14
        })

        addText(content, "Activate your device and enjoy!", if (compact) 16 else 18, GOLD, true, Gravity.CENTER)
        if (message != "To continue using the app") {
            addText(content, message, if (compact) 12 else 14, MUTED, false, Gravity.CENTER)
        }
        addAction(root, "RELOAD", true) { synchronizeDevice() }
    }

    private fun addCredential(parent: LinearLayout, label: String, value: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            background = panelBackground()
        }
        addText(panel, label, 14, MUTED, true, Gravity.CENTER)
        addText(panel, value, 21, IVORY, true, Gravity.CENTER)
        addAction(panel, "COPY", false) { copyText(label, value) }
        parent.addView(panel, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(10, 0, 10, 0)
        })
    }

    private fun showLoading(message: String) {
        newScreen()
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        addBrand(root)
        addText(root, message, 19, IVORY, false, Gravity.CENTER)
        root.addView(ProgressBar(this), LinearLayout.LayoutParams(54, 54).apply {
            gravity = Gravity.CENTER
            topMargin = 24
        })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }

    private fun showHome() {
        newScreen()
        addBrand(root)
        addText(root, "YOUR ENTERTAINMENT. YOUR PLAYER.", 13, MUTED, true, Gravity.CENTER)

        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addHomeCard(cards, "LIVE TV", "LIVE", MediaSection.LIVE)
        addHomeCard(cards, "MOVIES", "MOVIES", MediaSection.MOVIE)
        addHomeCard(cards, "SERIES", "SERIES", MediaSection.SERIES)
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 24
            bottomMargin = 20
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addFooterAction(bottom, "FAVORITES") { showFavorites() }
        addFooterAction(bottom, "HISTORY") { showHistory() }
        addFooterAction(bottom, "UPDATE") { synchronizeDevice() }
        addFooterAction(bottom, "LOGOUT") { confirmLogout() }
        addFooterAction(bottom, "DEVICE INFO") { showDeviceInfo() }
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addHomeCard(
        parent: LinearLayout,
        title: String,
        label: String,
        section: MediaSection
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10, 18, 10, 18)
            background = outlineBackground(CARD, GOLD, 2)
            isFocusable = true
            isClickable = true
            setOnClickListener { showLibrary(section) }
            setOnFocusChangeListener { view, focused ->
                view.background = outlineBackground(
                    if (focused) FOCUSED_CARD else CARD,
                    GOLD,
                    if (focused) 4 else 2
                )
            }
        }
        val artwork = ImageView(this).apply {
            setImageResource(
                when (section) {
                    MediaSection.LIVE -> R.drawable.card_live
                    MediaSection.MOVIE -> R.drawable.card_movies
                    MediaSection.SERIES -> R.drawable.card_series
                }
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = title
        }
        card.addView(artwork, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = 12
        })
        addText(card, title, 25, GOLD, true, Gravity.CENTER)
        addText(card, label, 13, IVORY, false, Gravity.CENTER)
        parent.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(12, 0, 12, 0)
        })
    }

    private fun showLibrary(section: MediaSection) {
        activeSection = section
        activeGroup = null
        activeTitle = when (section) {
            MediaSection.LIVE -> "LIVE TV"
            MediaSection.MOVIE -> "MOVIES"
            MediaSection.SERIES -> "SERIES"
        }
        activeEntries = library.filter { it.section == section }
        catalogPage = 0
        if (section == MediaSection.LIVE) {
            showGroupList(activeTitle, activeEntries)
        } else {
            showMediaCatalog(activeTitle, activeEntries)
        }
    }

    /**
     * Movies and series are a VOD catalogue, not channel groups.
     * Display poster artwork and titles directly; selecting an item starts playback.
     */
    /**
     * VOD catalogue: no country/group/channel layer. Show a paged poster grid
     * so very large playlists do not create thousands of Android views at once.
     */
    private fun showMediaCatalog(title: String, entries: List<MediaEntry>) {
        screenLevel = "CATALOG"
        activeGroup = null
        val pageSize = 20
        val pageCount = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        catalogPage = catalogPage.coerceIn(0, pageCount - 1)
        val pageEntries = entries.drop(catalogPage * pageSize).take(pageSize)

        newScreen()
        root.setPadding(28, 12, 28, 10)
        addText(root, title, 29, GOLD, true, Gravity.CENTER)
        addText(root, "${entries.size} TITLES", 13, MUTED, false, Gravity.CENTER)

        if (entries.isEmpty()) {
            addText(root, "No titles found in this section.", 18, IVORY, false, Gravity.CENTER)
            addAction(root, "BACK", true) { showHome() }
            return
        }

        val scroll = ScrollView(this).apply { isFillViewport = false; isFocusable = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 5, 0, 5)
        }
        val featured = entries.first()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 9, 14, 9)
            background = outlineBackground(PANEL, BORDER, 1)
            isFocusable = true
            isClickable = true
            setOnClickListener { play(featured) }
            setOnFocusChangeListener { v, focused ->
                v.background = outlineBackground(if (focused) FOCUSED_CARD else PANEL, GOLD, if (focused) 3 else 1)
            }
        }
        val heroPoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = featured.title
            background = outlineBackground(CARD, BORDER, 1)
        }
        hero.addView(heroPoster, LinearLayout.LayoutParams(104, 142).apply { rightMargin = 18 })
        val heroInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(heroInfo, featured.title, 21, IVORY, true, Gravity.START)
        addText(heroInfo, if (title == "MOVIES") "MOVIE" else "SERIES", 12, GOLD, true, Gravity.START)
        addText(heroInfo, "SELECT TO PLAY", 12, MUTED, false, Gravity.START)
        hero.addView(heroInfo, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        loadArtwork(featured.logoUrl, heroPoster)

        addText(content, if (title == "MOVIES") "ALL MOVIES" else "ALL SERIES", 17, GOLD, true, Gravity.START)
        pageEntries.chunked(5).forEach { rowEntries ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowEntries.forEach { entry ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(5, 5, 5, 5)
                    background = outlineBackground(CARD, BORDER, 1)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { play(entry) }
                    setOnFocusChangeListener { v, focused ->
                        v.background = outlineBackground(if (focused) FOCUSED_CARD else CARD, GOLD, if (focused) 3 else 1)
                    }
                }
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = entry.title
                    background = outlineBackground(PANEL, BORDER, 1)
                }
                card.addView(poster, LinearLayout.LayoutParams(112, 148))
                val name = TextView(this).apply {
                    text = entry.title
                    textSize = 11f
                    setTextColor(IVORY)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(2, 5, 2, 2)
                }
                card.addView(name, LinearLayout.LayoutParams(112, 38))
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(5, 4, 5, 4)
                })
                loadArtwork(entry.logoUrl, poster)
            }
            repeat(5 - rowEntries.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    setMargins(5, 0, 5, 0)
                })
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val paging = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (catalogPage > 0) addFooterAction(paging, "PREVIOUS") {
            catalogPage--
            showMediaCatalog(title, entries)
        }
        addFooterAction(paging, "PAGE ${catalogPage + 1}/$pageCount") { }
        if (catalogPage < pageCount - 1) addFooterAction(paging, "NEXT") {
            catalogPage++
            showMediaCatalog(title, entries)
        }
        root.addView(paging, LinearLayout.LayoutParams(-1, -2))
        addAction(root, "BACK", true) { showHome() }
    }

    private fun loadArtwork(imageUrl: String?, target: ImageView) {
        if (imageUrl.isNullOrBlank()) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    URL(imageUrl).openConnection().apply {
                        connectTimeout = 8000
                        readTimeout = 10000
                    }.getInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }

                    val maxWidth = 320
                    val maxHeight = 420
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= maxWidth ||
                        bounds.outHeight / (sample * 2) >= maxHeight) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    URL(imageUrl).openConnection().apply {
                        connectTimeout = 8000
                        readTimeout = 10000
                    }.getInputStream().use { BitmapFactory.decodeStream(it, null, options) }
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null && !isFinishing && target.isAttachedToWindow) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun showFavorites() {
        activeSection = null
        activeGroup = null
        activeTitle = "FAVORITES"
        val favorites = preferences.getStringSet("favorites", emptySet()) ?: emptySet()
        activeEntries = library.filter { favorites.contains(it.id) }
        showMediaList(activeTitle, activeEntries)
    }

    private fun showHistory() {
        activeSection = null
        activeGroup = null
        activeTitle = "HISTORY"
        val history = preferences.getString("history", "").orEmpty()
            .split("|").filter { it.isNotBlank() }
        activeEntries = history.mapNotNull { id -> library.firstOrNull { it.id == id } }
        showMediaList(activeTitle, activeEntries)
    }

    private fun showGroupList(title: String, entries: List<MediaEntry>) {
        screenLevel = "GROUPS"
        newScreen()
        addText(root, title, 27, GOLD, true, Gravity.CENTER)
        val groups = entries.groupBy { it.groupName.ifBlank { "OTHER" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        addText(root, "${groups.size} GROUPS · ${entries.size} CHANNELS", 13, MUTED, false, Gravity.CENTER)
        val list = ListView(this).apply {
            divider = outlineBackground(BORDER, BORDER, 1)
            dividerHeight = 1
            isFocusable = true
            isFocusableInTouchMode = true
            setItemsCanFocus(false)
            adapter = object : ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_list_item_1, groups.keys.toList()
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = (convertView as? TextView) ?: TextView(this@MainActivity)
                    row.text = getItem(position)
                    row.textSize = 21f
                    row.typeface = Typeface.DEFAULT_BOLD
                    row.setTextColor(GOLD)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(24, 18, 24, 18)
                    row.background = outlineBackground(PANEL, BORDER, 1)
                    row.isFocusable = false
                    row.isClickable = false
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                val group = groups.keys.toList()[position]
                activeGroup = group
                showGroupChannels(title, group, groups[group].orEmpty())
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addAction(root, "BACK", true) { showHome() }
        list.requestFocus()
    }

    private fun showGroupChannels(title: String, group: String, entries: List<MediaEntry>) {
        screenLevel = "CHANNELS"
        activeGroup = group
        newScreen()
        addText(root, group, 27, GOLD, true, Gravity.CENTER)
        addText(root, "${entries.size} CHANNELS", 13, MUTED, false, Gravity.CENTER)
        val list = ListView(this).apply {
            divider = outlineBackground(BORDER, BORDER, 1)
            dividerHeight = 1
            adapter = object : ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_list_item_1, entries.map { it.title }
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = (convertView as? TextView) ?: TextView(this@MainActivity)
                    row.text = getItem(position)
                    row.textSize = 19f
                    row.setTextColor(IVORY)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(24, 15, 24, 15)
                    row.background = outlineBackground(PANEL, BORDER, 1)
                    row.isFocusable = false
                    row.isClickable = false
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ -> play(entries[position]) }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addAction(root, "BACK", true) { showGroupList(title, activeEntries) }
        list.requestFocus()
    }

    private fun showMediaList(title: String, entries: List<MediaEntry>) {
        screenLevel = "ITEMS"
        newScreen()
        addText(root, title, 27, GOLD, true, Gravity.CENTER)
        addText(root, "${entries.size} ITEMS", 13, MUTED, false, Gravity.CENTER)
        val list = ListView(this).apply {
            divider = outlineBackground(BORDER, BORDER, 1)
            dividerHeight = 1
            adapter = object : ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_list_item_1, entries.map { it.title }
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = (convertView as? TextView) ?: TextView(this@MainActivity)
                    row.text = getItem(position)
                    row.textSize = 19f
                    row.setTextColor(IVORY)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(24, 15, 24, 15)
                    row.background = outlineBackground(PANEL, BORDER, 1)
                    row.isFocusable = false
                    row.isClickable = false
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ -> play(entries[position]) }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addAction(root, "BACK", true) { showHome() }
        list.requestFocus()
    }

    private fun play(entry: MediaEntry) {
        rememberHistory(entry)
        screenLevel = "PLAYER"
        newScreen()
        root.setPadding(0, 0, 0, 0)
        val playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setOnLongClickListener {
                toggleFavorite(entry)
                Toast.makeText(
                    this@MainActivity,
                    if (isFavorite(entry)) "Added to favorites" else "Removed from favorites",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, -1))
        releasePlayer()
        currentPlayer = ExoPlayer.Builder(this).build().also { engine ->
            playerView.player = engine
            engine.setMediaItem(MediaItem.fromUri(entry.streamUrl))
            engine.prepare()
            engine.playWhenReady = true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screenLevel) {
            "PLAYER" -> {
                releasePlayer()
                val group = activeGroup
                if (group != null) {
                    showGroupChannels(activeTitle, group, activeEntries.filter { it.groupName == group })
                } else {
                    showMediaList(activeTitle, activeEntries)
                }
            }
            "CHANNELS" -> showGroupList(activeTitle, activeEntries)
            "GROUPS", "ITEMS", "CATALOG" -> showHome()
            else -> super.onBackPressed()
        }
    }

    private fun rememberHistory(entry: MediaEntry) {
        val previous = preferences.getString("history", "").orEmpty()
            .split("|").filter { it.isNotBlank() }
        val updated = (listOf(entry.id) + previous.filterNot { it == entry.id })
            .take(100).joinToString("|")
        preferences.edit().putString("history", updated).apply()
    }

    private fun isFavorite(entry: MediaEntry): Boolean =
        (preferences.getStringSet("favorites", emptySet()) ?: emptySet()).contains(entry.id)

    private fun toggleFavorite(entry: MediaEntry) {
        val updated = (preferences.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet()
        if (!updated.add(entry.id)) updated.remove(entry.id)
        preferences.edit().putStringSet("favorites", updated).apply()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("LOGOUT")
            .setMessage("Return to the device activation screen?")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("LOGOUT") { _, _ ->
                releasePlayer()
                library = emptyList()
                showActivation()
            }
            .show()
    }

    private fun showDeviceInfo() {
        AlertDialog.Builder(this)
            .setTitle("DEVICE INFO")
            .setMessage("MAC Address: ${identity.address}\nDevice Key: ${identity.key}")
            .setPositiveButton("COPY ADDRESS") { _, _ -> copyText("MAC Address", identity.address) }
            .setNeutralButton("COPY KEY") { _, _ -> copyText("Device Key", identity.key) }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun addBrand(parent: LinearLayout) {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.colony_player_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "COLONY PLAYER"
        }
        parent.addView(logo, LinearLayout.LayoutParams(280, 78).apply {
            gravity = Gravity.CENTER
            bottomMargin = 8
        })
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Int,
        color: Int,
        bold: Boolean,
        gravity: Int = Gravity.START
    ): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 7, 8, 7)
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
        return view
    }

    private fun addAction(
        parent: LinearLayout,
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        val view = TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) BLACK else IVORY)
            background = outlineBackground(if (primary) GOLD else PANEL, GOLD, 1)
            setPadding(18, 13, 18, 13)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { target, focused ->
                target.background = outlineBackground(
                    if (primary || focused) GOLD else PANEL,
                    GOLD,
                    if (focused) 3 else 1
                )
                setTextColor(if (primary || focused) BLACK else IVORY)
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 7
            bottomMargin = 7
        })
        return view
    }

    private fun addFooterAction(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val view = TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(IVORY)
            background = outlineBackground(PANEL, BORDER, 1)
            setPadding(5, 15, 5, 15)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { target, focused ->
                target.background = outlineBackground(if (focused) FOCUSED_CARD else PANEL, GOLD, 2)
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(4, 0, 4, 0)
        })
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun openWebsite() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://colony-player.pages.dev")))
    }

    private fun releasePlayer() {
        currentPlayer?.release()
        currentPlayer = null
    }

    private fun panelBackground() = outlineBackground(PANEL, BORDER, 1)

    private fun outlineBackground(color: Int, stroke: Int, width: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 14f
            setStroke(width, stroke)
        }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val BLACK = 0xFF080808.toInt()
        private const val PANEL = 0xFF171717.toInt()
        private const val CARD = 0xFF101010.toInt()
        private const val FOCUSED_CARD = 0xFF332817.toInt()
        private const val GOLD = 0xFFE0B65B.toInt()
        private const val IVORY = 0xFFF5F1E8.toInt()
        private const val MUTED = 0xFFAAA39A.toInt()
        private const val BORDER = 0xFF51452F.toInt()
    }
}
