const encoder = new TextEncoder();

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

export async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}

export function randomHex(bytes = 32) {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return [...data].map(x => x.toString(16).padStart(2, "0")).join("");
}

export async function currentDevice(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) return null;
  const tokenHash = await sha256(token);
  const now = Math.floor(Date.now() / 1000);
  const row = await env.DB.prepare(
    `SELECT d.id, d.device_id, d.status, d.trial_ends_at, d.license_ends_at
     FROM device_sessions s JOIN devices d ON d.id = s.device_id
     WHERE s.token_hash = ? AND s.expires_at > ? LIMIT 1`
  ).bind(tokenHash, now).first();
  if (!row || row.status !== "active") return null;
  return row;
}

export function entitlement(device) {
  const now = Math.floor(Date.now() / 1000);
  if (device.license_ends_at && device.license_ends_at > now) {
    return { active: true, type: "annual", expiresAt: device.license_ends_at };
  }
  if (device.trial_ends_at > now) {
    return { active: true, type: "trial", expiresAt: device.trial_ends_at };
  }
  return { active: false, type: "expired", expiresAt: device.license_ends_at || device.trial_ends_at };
}

function fromBase64(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

export async function encryptText(value, secret) {
  if (!secret) throw new Error("PLAYLIST_ENCRYPTION_KEY is not configured");
  const keyBytes = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoder.encode(value));
  return { encrypted: btoa(String.fromCharCode(...new Uint8Array(encrypted))), iv: btoa(String.fromCharCode(...iv)) };
}

export async function decryptText(value, ivValue, secret) {
  const keyBytes = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  const clear = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromBase64(ivValue) }, key, fromBase64(value));
  return new TextDecoder().decode(clear);
}
