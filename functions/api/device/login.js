import { json, randomHex, sha256, entitlement } from "../../_shared/auth.js";

export async function onRequestPost({ request, env }) {
  try {
    if (!env.DB || !env.DEVICE_KEY_PEPPER) {
      return json({ ok: false, error: "SERVER_NOT_CONFIGURED" }, 503);
    }

    const body = await request.json();
    const deviceId = String(body.deviceId || "").trim().toUpperCase().replace(/\s+/g, "");
    const deviceKey = String(body.deviceKey || "").trim();
    const validMac = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(deviceId);
    const legacyId = /^CP-[A-F0-9]{12,32}$/.test(deviceId);
    const validKey = /^\d{6}$/.test(deviceKey) || /^[A-Za-z0-9_-]{32,128}$/.test(deviceKey);
    if ((!validMac && !legacyId) || !validKey) {
      return json({ ok: false, error: "INVALID_DEVICE_DETAILS" }, 400);
    }

    const now = Math.floor(Date.now() / 1000);
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    // New namespace clears stale counters from the previous broken login flow.
    const clientKey = await sha256("login:v3:" + ip + ":" + deviceId);
    await env.DB.prepare("DELETE FROM login_attempts WHERE attempted_at < ?").bind(now - 86400).run();

    const attempts = await env.DB.prepare(
      "SELECT COUNT(*) AS total FROM login_attempts WHERE client_key = ? AND attempted_at > ?"
    ).bind(clientKey, now - 15 * 60).first();
    if ((attempts?.total || 0) >= 10) {
      return json({ ok: false, error: "TOO_MANY_ATTEMPTS" }, 429);
    }

    const keyHash = await sha256(env.DEVICE_KEY_PEPPER + ":" + deviceKey);
    let device = await env.DB.prepare(
      "SELECT id, device_key_hash, status, trial_ends_at, license_ends_at FROM devices WHERE device_id = ? LIMIT 1"
    ).bind(deviceId).first();

    // The website can complete first-time registration when the TV has displayed
    // its generated MAC/key but its earlier registration request never reached D1.
    if (!device) {
      const id = crypto.randomUUID();
      const trialEndsAt = now + 7 * 24 * 60 * 60;
      try {
        await env.DB.prepare(
          "INSERT INTO devices (id, device_id, device_key_hash, created_at, trial_ends_at, license_ends_at, status) VALUES (?, ?, ?, ?, ?, NULL, 'active')"
        ).bind(id, deviceId, keyHash, now, trialEndsAt).run();
      } catch (insertError) {
        // A concurrent first login may have inserted this same device.
        device = await env.DB.prepare(
          "SELECT id, device_key_hash, status, trial_ends_at, license_ends_at FROM devices WHERE device_id = ? LIMIT 1"
        ).bind(deviceId).first();
        if (!device) throw insertError;
      }
      if (!device) {
        device = { id, device_key_hash: keyHash, status: "active", trial_ends_at: trialEndsAt, license_ends_at: null };
      }
    }

    if (device.device_key_hash !== keyHash) {
      await env.DB.prepare(
        "INSERT INTO login_attempts (client_key, attempted_at) VALUES (?, ?)"
      ).bind(clientKey, now).run();
      return json({ ok: false, error: "DEVICE_ALREADY_REGISTERED" }, 409);
    }
    if (device.status === "blocked") {
      return json({ ok: false, error: "DEVICE_BLOCKED" }, 403);
    }
    if (device.status !== "active") {
      return json({ ok: false, error: "DEVICE_NOT_AUTHORIZED" }, 401);
    }

    const access = entitlement(device);
    const token = randomHex(32);
    const tokenHash = await sha256(token);
    await env.DB.prepare(
      "INSERT INTO device_sessions (id, device_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)"
    ).bind(crypto.randomUUID(), device.id, tokenHash, now, now + 30 * 24 * 60 * 60).run();

    return json({
      ok: true,
      token,
      device: {
        deviceId,
        status: device.status,
        playbackAllowed: access.active,
        entitlement: access
      }
    });
  } catch (error) {
    console.error("COLONY_PLAYER_DEVICE_LOGIN_FAILED", error?.message || "unknown");
    return json({ ok: false, error: "LOGIN_SERVICE_ERROR" }, 500);
  }
}
