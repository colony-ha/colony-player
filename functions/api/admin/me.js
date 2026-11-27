import { json, currentAdmin } from "../../_shared/admin.js";
export async function onRequestGet({request,env}) {
  const admin=await currentAdmin(request,env);
  return admin?json({ok:true,admin:{email:admin.email}}):json({ok:false,error:"UNAUTHORIZED"},401);
}
