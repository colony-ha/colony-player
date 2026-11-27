package com.colony.player

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.colony.player.device.DeviceIdentity
import com.colony.player.model.MediaEntry
import com.colony.player.model.MediaSection
import com.colony.player.network.ApiException
import com.colony.player.network.ColonyApi
import com.colony.player.playlist.M3uPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

                val entries = withContext(Dispatchers.IO) {
                    val source = api.firstM3uPlaylist()
                    val document = api.downloadM3u(source.url)
                    M3uPlaylistParser.parse(document)
                }
                library = entries
                showHome()
            } catch (error: Exception) {
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
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        addBrand(root)
        addText(root, "To continue using the app", 25, IVORY, true, Gravity.CENTER)
        addText(root, "Adding Playlist", 19, GOLD, true, Gravity.CENTER)
        val website = addText(root, "Visit colony-player.pages.dev", 16, MUTED, false, Gravity.CENTER)
        website.isFocusable = true
        website.setOnClickListener { openWebsite() }
        addText(root, "Log in using your Device Address and Key", 18, IVORY, true, Gravity.CENTER)

        val credentials = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addCredential(credentials, "MAC Address", identity.address)
        addCredential(credentials, "Device Key", identity.key)
        root.addView(credentials, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 18
            bottomMargin = 14
        })

        addText(root, "Activate your device and enjoy!", 18, GOLD, true, Gravity.CENTER)
        if (message != "To continue using the app") {
            addText(root, message, 14, MUTED, false, Gravity.CENTER)
        }
        addAction(root, "RELOAD", true) { synchronizeDevice() }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
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
        addText(card, title, 25, GOLD, true, Gravity.CENTER)
        addText(card, label, 13, IVORY, false, Gravity.CENTER)
        parent.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(12, 0, 12, 0)
        })
    }

    private fun showLibrary(section: MediaSection) {
        val filtered = library.filter { it.section == section }
        val title = when (section) {
            MediaSection.LIVE -> "LIVE TV"
            MediaSection.MOVIE -> "MOVIES"
            MediaSection.SERIES -> "SERIES"
        }
        showMediaList(title, filtered)
    }

    private fun showFavorites() {
        val favorites = preferences.getStringSet("favorites", emptySet()) ?: emptySet()
        showMediaList("FAVORITES", library.filter { favorites.contains(it.id) })
    }

    private fun showHistory() {
        val history = preferences.getString("history", "").orEmpty()
            .split("|").filter { it.isNotBlank() }
        showMediaList("HISTORY", history.mapNotNull { id -> library.firstOrNull { it.id == id } })
    }

    private fun showMediaList(title: String, entries: List<MediaEntry>) {
        newScreen()
        addText(root, title, 27, GOLD, true, Gravity.CENTER)
        addText(root, "${entries.size} ITEMS", 13, MUTED, false, Gravity.CENTER)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        entries.groupBy { it.groupName }.forEach { (group, groupedEntries) ->
            addText(list, group, 17, GOLD, true)
            groupedEntries.forEach { entry ->
                val row = addAction(list, entry.title, false) { play(entry) }
                row.gravity = Gravity.CENTER_VERTICAL
                row.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addAction(root, "BACK", true) { showHome() }
    }

    private fun play(entry: MediaEntry) {
        rememberHistory(entry)
        newScreen()
        addText(root, entry.title, 21, GOLD, true, Gravity.CENTER)
        val playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val isSaved = isFavorite(entry)
        addFooterAction(controls, if (isSaved) "REMOVE FAVORITE" else "FAVORITE") {
            toggleFavorite(entry)
            play(entry)
        }
        addFooterAction(controls, "BACK") {
            releasePlayer()
            showHome()
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        releasePlayer()
        currentPlayer = ExoPlayer.Builder(this).build().also { engine ->
            playerView.player = engine
            engine.setMediaItem(MediaItem.fromUri(entry.streamUrl))
            engine.prepare()
            engine.playWhenReady = true
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
            contentDescription = "COLONY PLAYER"
        }
        parent.addView(logo, LinearLayout.LayoutParams(250, 78).apply {
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
