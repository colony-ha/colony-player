import { json, currentAdmin, audit } from "../../_shared/admin.js";
import { decryptText } from "../../_shared/auth.js";

export async function onRequestGet({request,env}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  try {
    const rows=await env.DB.prepare(`
      SELECT d.id,d.device_id,d.created_at,d.trial_ends_at,d.license_ends_at,d.status,
        (SELECT c.code_encrypted FROM activation_codes c WHERE c.redeemed_device_id=d.id AND c.status='redeemed' AND c.code_encrypted IS NOT NULL ORDER BY c.redeemed_at DESC LIMIT 1) AS code_encrypted,
        (SELECT c.code_iv FROM activation_codes c WHERE c.redeemed_device_id=d.id AND c.status='redeemed' AND c.code_encrypted IS NOT NULL ORDER BY c.redeemed_at DESC LIMIT 1) AS code_iv
      FROM devices d ORDER BY d.created_at DESC LIMIT 500
    `).all();
    const devices=[];
    for(const row of rows.results||[]){
      let activationCode=null;
      if(row.code_encrypted && row.code_iv && env.PLAYLIST_ENCRYPTION_KEY){
        try { activationCode=await decryptText(row.code_encrypted,row.code_iv,env.PLAYLIST_ENCRYPTION_KEY); } catch {}
      }
      const {code_encrypted,code_iv,...device}=row;
      devices.push({...device,activation_code:activationCode});
    }
    return json({ok:true,devices});
  } catch {
    return json({ok:false,error:"DEVICES_LOAD_FAILED"},500);
  }
}
