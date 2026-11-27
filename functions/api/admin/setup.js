import { json, passwordHash } from "../../_shared/admin.js";

export async function onRequestPost({request,env}) {
  try {
    if (!env.DB || !env.ADMIN_SETUP_SECRET) return json({ok:false,error:"SETUP_NOT_CONFIGURED"},503);
    const body=await request.json();
    const secret=String(body.setupSecret||"");
    const email=String(body.email||"").trim().toLowerCase();
    const password=String(body.password||"");
    if (secret !== env.ADMIN_SETUP_SECRET) return json({ok:false,error:"INVALID_SETUP_SECRET"},403);
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || password.length<12 || password.length>256)
      return json({ok:false,error:"INVALID_ADMIN_DETAILS"},400);
    const count=await env.DB.prepare("SELECT COUNT(*) AS total FROM admin_accounts").first();
    if ((count?.total||0)>0) return json({ok:false,error:"ADMIN_ALREADY_EXISTS"},409);
    const now=Math.floor(Date.now()/1000), id=crypto.randomUUID(), hash=await passwordHash(password);
    const result=await env.DB.prepare("INSERT OR IGNORE INTO admin_accounts (id,email,password_hash,created_at,updated_at,status) VALUES (?,?,?,?,?,'active')")
      .bind(id,email,hash,now,now).run();
    if (!result.meta?.changes) return json({ok:false,error:"ADMIN_ALREADY_EXISTS"},409);
    return json({ok:true,message:"ADMIN_CREATED"});
  } catch { return json({ok:false,error:"SETUP_FAILED"},500); }
}
