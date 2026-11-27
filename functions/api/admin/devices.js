import { json, currentAdmin, audit } from "../../_shared/admin.js";

export async function onRequestGet({request,env}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  try {
    const rows=await env.DB.prepare(`
      SELECT id,device_id,created_at,trial_ends_at,license_ends_at,status
      FROM devices
      ORDER BY created_at DESC
      LIMIT 500
    `).all();
    return json({ok:true,devices:rows.results||[]});
  } catch {
    return json({ok:false,error:"DEVICES_LOAD_FAILED"},500);
  }
}
