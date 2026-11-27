import { json, currentAdmin, audit } from "../../../_shared/admin.js";

export async function onRequestPatch({request,env,params}) {
  const admin=await currentAdmin(request,env);
  if (!admin) return json({ok:false,error:"UNAUTHORIZED"},401);
  let body={};
  try { body=await request.json(); } catch { return json({ok:false,error:"INVALID_REQUEST"},400); }
  const action=String(body.action||"");
  if (!["block","unblock"].includes(action)) return json({ok:false,error:"INVALID_ACTION"},400);
  const id=String(params.id||"");
  const status=action==="block"?"blocked":"active";
  const result=await env.DB.prepare(
    "UPDATE devices SET status=? WHERE id=? AND status IN ('active','blocked')"
  ).bind(status,id).run();
  if (!result.meta?.changes) return json({ok:false,error:"DEVICE_NOT_FOUND_OR_REVOKED"},404);
  await audit(env,admin.id,action==="block"?"device_blocked":"device_unblocked","device",id);
  return json({ok:true,status});
}
