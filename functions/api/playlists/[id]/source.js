import { json, currentDevice, entitlement, decryptText } from "../../../_shared/auth.js";

export async function onRequestGet({ request, env, params }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  if (device.status === "blocked") return json({ ok: false, error: "DEVICE_BLOCKED" }, 403);
  if (!entitlement(device).active) return json({ ok: false, error: "SUBSCRIPTION_EXPIRED" }, 403);
  const playlist = await env.DB.prepare(
    "SELECT id, name, playlist_type, encrypted_url, iv FROM playlists WHERE id = ? AND device_id = ? LIMIT 1"
  ).bind(String(params.id), device.id).first();
  if (!playlist) return json({ ok: false, error: "NOT_FOUND" }, 404);
  try {
    const url = await decryptText(playlist.encrypted_url, playlist.iv, env.PLAYLIST_ENCRYPTION_KEY);
    return json({ ok: true, playlist: { id: playlist.id, name: playlist.name, type: playlist.playlist_type, url } });
  } catch {
    return json({ ok: false, error: "PLAYLIST_DECRYPT_FAILED" }, 503);
  }
}
