import { json, currentAdmin, audit } from "../../../_shared/admin.js";
export async function onRequestDelete({request,env,params}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  const id=String(params.id||"");
  const result=await env.DB.prepare("UPDATE activation_codes SET status='revoked' WHERE id=? AND status='available'").bind(id).run();
  if (!result.meta?.changes) return json({ok:false,error:"CODE_NOT_AVAILABLE"},409);
  await audit(env,admin.id,"code_revoked","activation_code",id);
  return json({ok:true});
}
