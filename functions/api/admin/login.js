import { json, randomHex, verifyPassword } from "../../_shared/admin.js";
import { sha256 } from "../../_shared/auth.js";

export async function onRequestPost({request,env}) {
  try {
    if (!env.DB) return json({ok:false,error:"SERVER_NOT_CONFIGURED"},503);
    const body=await request.json(), email=String(body.email||"").trim().toLowerCase(), password=String(body.password||"");
    const now=Math.floor(Date.now()/1000), ip=request.headers.get("cf-connecting-ip")||"unknown";
    const key=await sha256("admin:"+ip+":"+email);
    await env.DB.prepare("DELETE FROM login_attempts WHERE attempted_at < ?").bind(now-86400).run();
    const attempts=await env.DB.prepare("SELECT COUNT(*) AS total FROM login_attempts WHERE client_key=? AND attempted_at>?").bind(key,now-900).first();
    if ((attempts?.total||0)>=8) return json({ok:false,error:"TOO_MANY_ATTEMPTS"},429);
    await env.DB.prepare("INSERT INTO login_attempts (client_key,attempted_at) VALUES (?,?)").bind(key,now).run();
    const admin=await env.DB.prepare("SELECT id,email,password_hash,status FROM admin_accounts WHERE email=? LIMIT 1").bind(email).first();
    if (!admin || admin.status!=="active" || !(await verifyPassword(password,admin.password_hash)))
      return json({ok:false,error:"INVALID_CREDENTIALS"},401);
    const token=randomHex(32), tokenHash=await sha256(token);
    await env.DB.prepare("INSERT INTO admin_sessions (id,admin_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?)")
      .bind(crypto.randomUUID(),admin.id,tokenHash,now,now+12*60*60).run();
    return json({ok:true,token,admin:{email:admin.email}});
  } catch { return json({ok:false,error:"LOGIN_FAILED"},500); }
}
