package com.colony.player.network

import com.colony.player.model.PlaylistSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.colony.player.model.MediaEntry
import com.colony.player.playlist.M3uPlaylistParser
import java.util.concurrent.TimeUnit

class ColonyApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var sessionToken: String? = null

    fun register(address: String, key: String) {
        val response = post("/device/register", JSONObject()
            .put("deviceId", address)
            .put("deviceKey", key))
        if (!response.optBoolean("ok")) throw ApiException(response.optString("error", "REGISTRATION_FAILED"))
    }

    fun signIn(address: String, key: String): Boolean {
        val response = post("/device/login", JSONObject()
            .put("deviceId", address)
            .put("deviceKey", key))
        if (!response.optBoolean("ok")) throw ApiException(response.optString("error", "LOGIN_FAILED"))
        sessionToken = response.getString("token")
        return response.optJSONObject("device")?.optBoolean("playbackAllowed") == true
    }

    fun firstM3uPlaylist(): PlaylistSource {
        val response = get("/playlists")
        if (!response.optBoolean("ok")) throw ApiException(response.optString("error", "PLAYLISTS_FAILED"))
        val list = response.optJSONArray("playlists") ?: throw ApiException("NO_PLAYLIST")
        if (list.length() == 0) throw ApiException("NO_PLAYLIST")

        var chosen: JSONObject? = null
        for (index in 0 until list.length()) {
            val candidate = list.optJSONObject(index) ?: continue
            if (candidate.optString("playlist_type").equals("m3u", true)) {
                chosen = candidate
                break
            }
        }
        val item = chosen ?: throw ApiException("M3U_ONLY")
        val id = item.optString("id")
        if (id.isBlank()) throw ApiException("INVALID_PLAYLIST")
        val source = get("/playlists/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}/source")
        if (!source.optBoolean("ok")) throw ApiException(source.optString("error", "PLAYLIST_SOURCE_FAILED"))
        val data = source.optJSONObject("playlist") ?: throw ApiException("INVALID_PLAYLIST")
        val type = data.optString("type")
        if (!type.equals("m3u", true)) throw ApiException("M3U_ONLY")
        val url = data.optString("url")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            throw ApiException("INVALID_PLAYLIST_URL")
        }
        return PlaylistSource(id, data.optString("name", "COLONY PLAYLIST"), type, url)
    }

    /** Downloads and parses the playlist as a stream; never stores the whole M3U text in memory. */
    fun loadM3u(url: String): List<MediaEntry> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "COLONY-PLAYER-Android/2.2")
            .header("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("PLAYLIST_HTTP_${response.code}")
                val body = response.body ?: throw ApiException("EMPTY_PLAYLIST")
                if (body.contentLength() == 0L) throw ApiException("EMPTY_PLAYLIST")
                return body.byteStream().use { M3uPlaylistParser.parse(it) }
            }
        } catch (error: IOException) {
            throw ApiException("NETWORK_ERROR", error)
        }
    }

    private fun post(path: String, payload: JSONObject): JSONObject = execute(
        Request.Builder().url(BASE_URL + path)
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
    )

    private fun get(path: String): JSONObject {
        val builder = Request.Builder().url(BASE_URL + path).get()
        sessionToken?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build())
    }

    private fun execute(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = try { JSONObject(raw) } catch (_: Exception) {
                    JSONObject().put("error", if (response.isSuccessful) "INVALID_SERVER_RESPONSE" else "HTTP_${response.code}")
                }
                if (!response.isSuccessful && !json.has("error")) json.put("error", "HTTP_${response.code}")
                return json
            }
        } catch (error: IOException) {
            throw ApiException("NETWORK_ERROR", error)
        }
    }

    companion object {
        private const val BASE_URL = "https://colony-player.pages.dev/api"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ApiException(val code: String, cause: Throwable? = null) : Exception(code, cause)
