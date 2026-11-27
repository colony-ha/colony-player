import { json, currentDevice, entitlement } from "../../_shared/auth.js";

export async function onRequestGet({ request, env }) {
  const device = await currentDevice(request, env);
  if (!device) return json({ ok: false, error: "UNAUTHORIZED" }, 401);
  const access = entitlement(device);
  return json({ ok: true, device: { deviceId: device.device_id, status: device.status, playbackAllowed: access.active, entitlement: access } });
}
