import { json, randomHex, sha256, entitlement } from "../../_shared/auth.js";

export async function onRequestPost({ request, env }) {
  try {
    const body = await request.json();
    const deviceId = String(body.deviceId || "").trim().toUpperCase().replace(/\s+/g, "");
    const deviceKey = String(body.deviceKey || "").trim();
    const validMac = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(deviceId);
    const legacyId = /^CP-[A-F0-9]{12,32}$/.test(deviceId);
    const validKey = /^\\d{6}$/.test(deviceKey) || /^[A-Za-z0-9_-]{32,128}$/.test(deviceKey);
    if ((!validMac && !legacyId) || !validKey) {
      return json({ ok: false, error: "INVALID_DEVICE_DETAILS" }, 400);
    }
    if (!env.DB || !env.DEVICE_KEY_PEPPER) return json({ ok: false, error: "SERVER_NOT_CONFIGURED" }, 503);
    const now = Math.floor(Date.now() / 1000);
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const clientKey = await sha256(ip + ":" + deviceId);
    await env.DB.prepare("DELETE FROM login_attempts WHERE attempted_at < ?").bind(now - 86400).run();
    const attempts = await env.DB.prepare(
      "SELECT COUNT(*) AS total FROM login_attempts WHERE client_key = ? AND attempted_at > ?"
    ).bind(clientKey, now - 15 * 60).first();
    if ((attempts?.total || 0) >= 10) return json({ ok: false, error: "TOO_MANY_ATTEMPTS" }, 429);
    await env.DB.prepare("INSERT INTO login_attempts (client_key, attempted_at) VALUES (?, ?)").bind(clientKey, now).run();
    const device = await env.DB.prepare(
      "SELECT id, device_key_hash, status, trial_ends_at, license_ends_at FROM devices WHERE device_id = ? LIMIT 1"
    ).bind(deviceId).first();
    const keyHash = await sha256(env.DEVICE_KEY_PEPPER + ":" + deviceKey);
    if (!device || device.device_key_hash !== keyHash || !["active", "blocked"].includes(device.status)) {
      return json({ ok: false, error: "DEVICE_NOT_AUTHORIZED" }, 401);
    }
    const access = entitlement(device);
    const token = randomHex(32);
    const tokenHash = await sha256(token);
    await env.DB.prepare(
      "INSERT INTO device_sessions (id, device_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)"
    ).bind(crypto.randomUUID(), device.id, tokenHash, now, now + 30 * 24 * 60 * 60).run();
    return json({ ok: true, token, device: { deviceId, status: device.status, playbackAllowed: access.active, entitlement: access } });
  } catch {
    return json({ ok: false, error: "LOGIN_FAILED" }, 500);
  }
}
