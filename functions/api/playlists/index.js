import { json, currentDevice, entitlement, encryptText } from "../../_shared/auth.js";

function canUsePlaylists(device) {
  return device.status === "active" && entitlement(device).active;
}

export async function onRequestGet({ request, env }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  if (!canUsePlaylists(device)) return json({ ok: false, error: device.status === "blocked" ? "DEVICE_BLOCKED" : "SUBSCRIPTION_EXPIRED" }, 403);
  const result = await env.DB.prepare(
    "SELECT id, name, playlist_type, created_at, updated_at FROM playlists WHERE device_id = ? ORDER BY created_at DESC"
  ).bind(device.id).all();
  return json({ ok: true, playlists: result.results || [] });
}

export async function onRequestPost({ request, env }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  if (!canUsePlaylists(device)) return json({ ok: false, error: device.status === "blocked" ? "DEVICE_BLOCKED" : "SUBSCRIPTION_EXPIRED" }, 403);
  const body = await request.json();
  const name = String(body.name || "").trim().slice(0, 80);
  const type = String(body.type || "m3u");
  const url = String(body.url || "").trim();
  if (!name || !url || url.length > 8000 || !["m3u", "xtream"].includes(type)) return json({ ok: false, error: "INVALID_PLAYLIST" }, 400);
  if (!/^https?:\/\//i.test(url)) return json({ ok: false, error: "INVALID_PLAYLIST_URL" }, 400);
  let encrypted;
  try { encrypted = await encryptText(url, env.PLAYLIST_ENCRYPTION_KEY); }
  catch { return json({ ok: false, error: "ENCRYPTION_NOT_CONFIGURED" }, 503); }
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare(
    "INSERT INTO playlists (id, device_id, name, playlist_type, encrypted_url, iv, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(id, device.id, name, type, encrypted.encrypted, encrypted.iv, now, now).run();
  return json({ ok: true, playlist: { id, name, type, createdAt: now } }, 201);
}
