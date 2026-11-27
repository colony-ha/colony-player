import { json, currentDevice, entitlement } from "../../_shared/auth.js";

export async function onRequestDelete({ request, env, params }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  if (device.status === "blocked") return json({ ok: false, error: "DEVICE_BLOCKED" }, 403);
  if (!entitlement(device).active) return json({ ok: false, error: "SUBSCRIPTION_EXPIRED" }, 403);
  const result = await env.DB.prepare("DELETE FROM playlists WHERE id = ? AND device_id = ?")
    .bind(String(params.id), device.id).run();
  if (!result.meta?.changes) return json({ ok: false, error: "NOT_FOUND" }, 404);
  return json({ ok: true });
}
