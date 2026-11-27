import { json, currentAdmin, audit, makeCode } from "../../_shared/admin.js";
import { sha256 } from "../../_shared/auth.js";

export async function onRequestGet({request,env}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  const rows=await env.DB.prepare(`SELECT c.id,c.created_at,c.redeemed_at,c.status,d.device_id
    FROM activation_codes c LEFT JOIN devices d ON d.id=c.redeemed_device_id
    ORDER BY c.created_at DESC LIMIT 300`).all();
  return json({ok:true,codes:rows.results||[]});
}

export async function onRequestPost({request,env}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  try {
    const body=await request.json(), count=Number(body.count||1);
    if (!Number.isInteger(count)||count<1||count>50) return json({ok:false,error:"INVALID_COUNT"},400);
    const created=[], statements=[];
    for(let i=0;i<count;i++){
      const code=makeCode(), id=crypto.randomUUID(), hash=await sha256(code.replace(/[\s-]/g,"").toUpperCase());
      created.push(code);
      statements.push(env.DB.prepare("INSERT INTO activation_codes (id,code_hash,created_at,status) VALUES (?,?,?,'available')").bind(id,hash,Math.floor(Date.now()/1000)));
    }
    await env.DB.batch(statements);
    await audit(env,admin.id,"codes_generated","activation_codes",null,JSON.stringify({count}));
    return json({ok:true,codes:created,validityDays:365});
  } catch { return json({ok:false,error:"CODE_GENERATION_FAILED"},500); }
}
