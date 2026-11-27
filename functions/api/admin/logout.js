import { json } from "../../_shared/admin.js";
import { sha256 } from "../../_shared/auth.js";
export async function onRequestPost({request,env}) {
  const header=request.headers.get("authorization")||"", token=header.startsWith("Bearer ")?header.slice(7).trim():"";
  if (token && env.DB) await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash=?").bind(await sha256(token)).run();
  return json({ok:true});
}
