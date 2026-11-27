import { json, passwordHash } from "../../_shared/admin.js";

export async function onRequestPost({request,env}) {
  let stage = "database";
  try {
    if (!env.DB || !env.ADMIN_SETUP_SECRET) return json({ok:false,error:"SETUP_NOT_CONFIGURED"},503);
    const body = await request.json();
    const secret = String(body.setupSecret || "");
    const email = String(body.email || "").trim().toLowerCase();
    const password = String(body.password || "");
    if (secret !== env.ADMIN_SETUP_SECRET) return json({ok:false,error:"INVALID_SETUP_SECRET"},403);
    if (!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email) || password.length < 12 || password.length > 256)
      return json({ok:false,error:"INVALID_ADMIN_DETAILS"},400);

    // Ensure the bootstrap table exists even if an earlier schema step was skipped.
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS admin_accounts (
      id TEXT PRIMARY KEY,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled'))
    )`).run();

    const count = await env.DB.prepare("SELECT COUNT(*) AS total FROM admin_accounts").first();
    if ((count?.total || 0) > 0) return json({ok:false,error:"ADMIN_ALREADY_EXISTS"},409);

    stage = "password";
    const hash = await passwordHash(password);
    const now = Math.floor(Date.now()/1000);
    const id = crypto.randomUUID();

    stage = "database";
    const result = await env.DB.prepare("INSERT OR IGNORE INTO admin_accounts (id,email,password_hash,created_at,updated_at,status) VALUES (?,?,?,?,?,'active')")
      .bind(id,email,hash,now,now).run();
    if (!result.meta?.changes) return json({ok:false,error:"ADMIN_ALREADY_EXISTS"},409);
    return json({ok:true,message:"ADMIN_CREATED"});
  } catch (error) {
    console.error("COLONY PLAYER admin setup failed at", stage, error);
    return json({ok:false,error:stage === "database" ? "SETUP_DATABASE_ERROR" : "SETUP_INTERNAL_ERROR"},500);
  }
}

export async function onRequestGet({env}) {
  if (!env.DB) return json({ok:false,error:"SERVER_NOT_CONFIGURED"},503);
  try {
    const row = await env.DB.prepare("SELECT COUNT(*) AS total FROM admin_accounts").first();
    return json({ok:true,setupRequired:(row?.total || 0) === 0});
  } catch {
    return json({ok:false,error:"ADMIN_DATABASE_NOT_READY"},503);
  }
}
