import { json, currentDevice, entitlement, decryptText } from "../../../_shared/auth.js";

function isPublicHostname(host) {
  const value = host.toLowerCase().replace(/^\[|\]$/g, "");
  if (value === "localhost" || value.endsWith(".localhost") || value.endsWith(".local")) return false;
  if (/^(\d{1,3}\.){3}\d{1,3}$/.test(value)) {
    const p = value.split(".").map(Number);
    if (p.some(n => n > 255)) return false;
    return !(p[0] === 10 || p[0] === 127 || p[0] === 0 ||
      (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
      (p[0] === 192 && p[1] === 168) ||
      (p[0] === 169 && p[1] === 254) || p[0] >= 224);
  }
  return !value.includes(":") && !value.endsWith(".internal");
}

export async function onRequestGet({ request, env, params }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  if (device.status === "blocked") return json({ ok: false, error: "DEVICE_BLOCKED" }, 403);
  if (!entitlement(device).active) return json({ ok: false, error: "SUBSCRIPTION_EXPIRED" }, 403);

  const playlist = await env.DB.prepare(
    "SELECT id, encrypted_url, iv FROM playlists WHERE id = ? AND device_id = ? LIMIT 1"
  ).bind(String(params.id), device.id).first();
  if (!playlist) return json({ ok: false, error: "NOT_FOUND" }, 404);

  try {
    const url = await decryptText(playlist.encrypted_url, playlist.iv, env.PLAYLIST_ENCRYPTION_KEY);
    const parsed = new URL(url);
    if (!["http:", "https:"].includes(parsed.protocol) || !isPublicHostname(parsed.hostname)) {
      return json({ ok: false, error: "INVALID_PLAYLIST_HOST" }, 400);
    }
    const upstream = await fetch(parsed.toString(), {
      method: "GET", redirect: "manual", signal: AbortSignal.timeout(25000),
      headers: { "Accept": "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*", "User-Agent": "COLONY-PLAYER/1.0" }
    });
    if (upstream.status >= 300 && upstream.status < 400) return json({ ok: false, error: "PLAYLIST_REDIRECT_NOT_SUPPORTED" }, 502);
    if (!upstream.ok) return json({ ok: false, error: "PLAYLIST_HTTP_" + upstream.status }, 502);
    const content = await upstream.text();
    if (!content || content.length > 15 * 1024 * 1024) return json({ ok: false, error: "PLAYLIST_EMPTY_OR_TOO_LARGE" }, 413);
    if (!content.replace(/^\uFEFF/, "").trimStart().toUpperCase().startsWith("#EXTM3U")) {
      return json({ ok: false, error: "NOT_M3U_CONTENT" }, 422);
    }
    return new Response(content, { status: 200, headers: {
      "content-type": "audio/x-mpegurl; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff"
    }});
  } catch (error) {
    return json({ ok: false, error: error?.name === "TimeoutError" ? "PLAYLIST_TIMEOUT" : "PLAYLIST_FETCH_FAILED" }, 502);
  }
}
