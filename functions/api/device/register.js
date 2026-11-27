import { json, sha256 } from "../../_shared/auth.js";

export async function onRequestPost({ request, env }) {
  try {
    if (!env.DB || !env.DEVICE_KEY_PEPPER) return json({ ok: false, error: "SERVER_NOT_CONFIGURED" }, 503);
    const body = await request.json();
    const deviceId = String(body.deviceId || "").trim().toUpperCase().replace(/\s+/g, "");
    const deviceKey = String(body.deviceKey || "").trim();
    const validMac = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(deviceId);
    const legacyId = /^CP-[A-F0-9]{12,32}$/.test(deviceId);
    const validKey = /^\d{6}$/.test(deviceKey) || /^[A-Za-z0-9_-]{32,128}$/.test(deviceKey);
    if ((!validMac && !legacyId) || !validKey) return json({ ok: false, error: "INVALID_DEVICE_DETAILS" }, 400);

    const now = Math.floor(Date.now() / 1000);
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    // Only genuinely new registrations and wrong-key attempts count; normal app startup does not.
    const clientKey = await sha256("register:v2:" + ip);
    await env.DB.prepare("DELETE FROM login_attempts WHERE attempted_at < ?").bind(now - 86400).run();

    const keyHash = await sha256(env.DEVICE_KEY_PEPPER + ":" + deviceKey);
    const existing = await env.DB.prepare(
      "SELECT device_key_hash, trial_ends_at FROM devices WHERE device_id = ? LIMIT 1"
    ).bind(deviceId).first();
    if (existing) {
      if (existing.device_key_hash !== keyHash) {
        const attempts = await env.DB.prepare(
          "SELECT COUNT(*) AS total FROM login_attempts WHERE client_key = ? AND attempted_at > ?"
        ).bind(clientKey, now - 60 * 60).first();
        if ((attempts?.total || 0) >= 20) return json({ ok: false, error: "TOO_MANY_ATTEMPTS" }, 429);
        await env.DB.prepare("INSERT INTO login_attempts (client_key, attempted_at) VALUES (?, ?)").bind(clientKey, now).run();
        return json({ ok: false, error: "DEVICE_ALREADY_REGISTERED" }, 409);
      }
      return json({ ok: true, registered: true, alreadyRegistered: true, trialEndsAt: existing.trial_ends_at });
    }

    const attempts = await env.DB.prepare(
      "SELECT COUNT(*) AS total FROM login_attempts WHERE client_key = ? AND attempted_at > ?"
    ).bind(clientKey, now - 60 * 60).first();
    if ((attempts?.total || 0) >= 20) return json({ ok: false, error: "TOO_MANY_ATTEMPTS" }, 429);

    const id = crypto.randomUUID();
    const trialEndsAt = now + 7 * 24 * 60 * 60;
    try {
      await env.DB.prepare(
        "INSERT INTO devices (id, device_id, device_key_hash, created_at, trial_ends_at, license_ends_at, status) VALUES (?, ?, ?, ?, ?, NULL, 'active')"
      ).bind(id, deviceId, keyHash, now, trialEndsAt).run();
    } catch {
      const raced = await env.DB.prepare("SELECT device_key_hash, trial_ends_at FROM devices WHERE device_id = ? LIMIT 1").bind(deviceId).first();
      if (!raced || raced.device_key_hash !== keyHash) {
        await env.DB.prepare("INSERT INTO login_attempts (client_key, attempted_at) VALUES (?, ?)").bind(clientKey, now).run();
        return json({ ok: false, error: "DEVICE_ALREADY_REGISTERED" }, 409);
      }
      return json({ ok: true, registered: true, alreadyRegistered: true, trialEndsAt: raced.trial_ends_at });
    }
    await env.DB.prepare("INSERT INTO login_attempts (client_key, attempted_at) VALUES (?, ?)").bind(clientKey, now).run();
    return json({ ok: true, registered: true, alreadyRegistered: false, trialEndsAt });
  } catch {
    return json({ ok: false, error: "REGISTRATION_FAILED" }, 500);
  }
}
