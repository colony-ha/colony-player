import { json, currentDevice, entitlement } from "../../_shared/auth.js";

export async function onRequestGet({ request, env }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  const access = entitlement(device);
  if (!access.active) return json({ ok: false, error: "SUBSCRIPTION_EXPIRED", entitlement: access }, 403);
  return json({ ok: true, device: { deviceId: device.device_id, entitlement: access } });
}
