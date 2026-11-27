import { json, currentDevice, sha256 } from "../../_shared/auth.js";

export async function onRequestPost({ request, env }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);

  let body;
  try { body = await request.json(); } catch { return json({ ok: false, error: "INVALID_REQUEST" }, 400); }
  const code = String(body.code || "").trim().toUpperCase().replace(/[\s-]/g, "");
  if (code.length < 8 || code.length > 64) return json({ ok: false, error: "INVALID_OR_USED_CODE" }, 400);

  const now = Math.floor(Date.now() / 1000);
  const codeHash = await sha256(code);
  const currentLicenseEnd = Number(device.license_ends_at || 0);
  const licenseEnd = Math.max(now, currentLicenseEnd) + 365 * 24 * 60 * 60;
  const redeemedAt = now;

  try {
    const results = await env.DB.batch([
      env.DB.prepare(
        "UPDATE activation_codes SET status = 'redeemed', redeemed_at = ?, redeemed_device_id = ? WHERE code_hash = ? AND status = 'available'"
      ).bind(redeemedAt, device.id, codeHash),
      env.DB.prepare(
        "UPDATE devices SET license_ends_at = ? WHERE id = ? AND EXISTS (SELECT 1 FROM activation_codes WHERE code_hash = ? AND status = 'redeemed' AND redeemed_device_id = ? AND redeemed_at = ?)"
      ).bind(licenseEnd, device.id, codeHash, device.id, redeemedAt)
    ]);
    if (!results[0]?.meta?.changes || !results[1]?.meta?.changes) {
      return json({ ok: false, error: "INVALID_OR_USED_CODE" }, 400);
    }
    return json({ ok: true, entitlement: { active: true, type: "annual", expiresAt: licenseEnd } });
  } catch {
    return json({ ok: false, error: "ACTIVATION_FAILED" }, 500);
  }
}
