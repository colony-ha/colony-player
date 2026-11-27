const API="/api/admin";let token=sessionStorage.getItem("colony_admin_token")||"";const $=id=>document.getElementById(id);async function call(path,options={}){const headers={"content-type":"application/json",...(options.headers||{})};if(token)headers.authorization="Bearer "+token;const r=await fetch(API+path,{...options,headers});let data={};try{data=await r.json()}catch{}if(!r.ok)throw new Error(data.error||"REQUEST_FAILED");return data}function show(id){["setupPanel","loginPanel","dashboard"].forEach(x=>$(x).hidden=x!==id)}function message(id,text){$(id).textContent=text}function readable(e){const m={"INVALID_SETUP_SECRET":"مفتاح الإعداد السري غير مطابق للقيمة المحفوظة في Cloudflare.","INVALID_ADMIN_DETAILS":"تحقق من البريد الإلكتروني، واجعل كلمة المرور 12 حرفاً على الأقل.","ADMIN_ALREADY_EXISTS":"يوجد حساب مدير مسجل مسبقاً. انتقل إلى تسجيل الدخول.","INVALID_CREDENTIALS":"البريد الإلكتروني أو كلمة المرور غير صحيحة.","TOO_MANY_ATTEMPTS":"محاولات كثيرة. انتظر قليلاً.","SETUP_NOT_CONFIGURED":"المتغير ADMIN_SETUP_SECRET أو ربط قاعدة البيانات DB غير متاح في بيئة Production.","SETUP_DATABASE_ERROR":"فشل حفظ الحساب في قاعدة البيانات. تم تسجيل تفاصيل الخطأ في سجلات Cloudflare.","SETUP_INTERNAL_ERROR":"فشل تجهيز كلمة المرور. تحقق من سجلات Cloudflare.","ADMIN_DATABASE_NOT_READY":"جدول الإدارة غير جاهز في قاعدة البيانات.","UNAUTHORIZED":"انتهت الجلسة. سجل الدخول مجدداً."};return m[e]||("خطأ غير معروف: "+e)}
$("setupForm").addEventListener("submit",async e=>{e.preventDefault();const b=e.currentTarget.querySelector("button");b.disabled=true;message("setupNotice","جارٍ إنشاء الحساب...");try{await call("/setup",{method:"POST",body:JSON.stringify(Object.fromEntries(new FormData(e.currentTarget)))});show("loginPanel");message("loginNotice","تم إنشاء الحساب. سجل الدخول الآن.");}catch(err){message("setupNotice",readable(err.message))}finally{b.disabled=false}});
$("loginForm").addEventListener("submit",async e=>{e.preventDefault();const b=e.currentTarget.querySelector("button");b.disabled=true;message("loginNotice","جارٍ تسجيل الدخول...");try{const d=await call("/login",{method:"POST",body:JSON.stringify(Object.fromEntries(new FormData(e.currentTarget)))});token=d.token;sessionStorage.setItem("colony_admin_token",token);await dashboard(d.admin.email)}catch(err){message("loginNotice",readable(err.message))}finally{b.disabled=false}});
async function dashboard(email){$("adminEmail").textContent=email||"";show("dashboard");await Promise.all([loadCodes(),loadDevices()])}
function dateText(value){return value?new Date(value*1000).toLocaleString():"—"}
async function loadDevices(){
 const body=$("devicesBody");
 if(!body)return;
 body.innerHTML="<tr><td colspan='6'>جارٍ تحميل الأجهزة...</td></tr>";
 try{
  const d=await call("/devices");
  body.innerHTML="";
  const now=Math.floor(Date.now()/1000);
  for(const device of d.devices||[]){
   const tr=document.createElement("tr");
   const id=document.createElement("td");id.textContent=device.device_id;
   const created=document.createElement("td");created.textContent=dateText(device.created_at);
   const entitlement=document.createElement("td");
   const expiry=device.license_ends_at&&device.license_ends_at>now?device.license_ends_at:device.trial_ends_at;
   entitlement.textContent=device.license_ends_at&&device.license_ends_at>now?"اشتراك سنوي":device.trial_ends_at>now?"تجربة مجانية":"منتهي";
   const expires=document.createElement("td");expires.textContent=dateText(expiry);
   const state=document.createElement("td");
   const badge=document.createElement("span");badge.className="status "+device.status;
   badge.textContent=device.status==="active"?"نشط":device.status==="blocked"?"محظور":"ملغى";state.append(badge);
   const action=document.createElement("td");
   if(device.status==="active"||device.status==="blocked"){
    const btn=document.createElement("button");btn.className="small-action";
    btn.textContent=device.status==="active"?"حظر الجهاز":"إلغاء الحظر";
    btn.addEventListener("click",async()=>{
     const block=device.status==="active";
     if(!confirm(block?"هل تريد حظر هذا الجهاز؟ لن يتمكن من تسجيل الدخول أو تشغيل الخدمة.":"هل تريد إلغاء حظر هذا الجهاز؟"))return;
     btn.disabled=true;
     try{await call("/devices/"+encodeURIComponent(device.id),{method:"PATCH",body:JSON.stringify({action:block?"block":"unblock"})});await loadDevices()}
     catch(err){alert(readable(err.message));btn.disabled=false}
    });
    action.append(btn);
   }else action.textContent="—";
   tr.append(id,created,entitlement,expires,state,action);body.append(tr);
  }
  if(!(d.devices||[]).length)body.innerHTML="<tr><td colspan='6'>لا توجد أجهزة مسجلة.</td></tr>";
 }catch(err){
  if(err.message==="UNAUTHORIZED"){token="";sessionStorage.removeItem("colony_admin_token");show("loginPanel")}
  body.innerHTML="<tr><td colspan='6'>تعذر تحميل الأجهزة: "+readable(err.message)+"</td></tr>";
 }
}
async function loadCodes(){const body=$("codesBody");body.innerHTML="<tr><td colspan='5'>جارٍ التحميل...</td></tr>";try{const d=await call("/codes");body.innerHTML="";for(const c of d.codes){const tr=document.createElement("tr");const status=document.createElement("td"),created=document.createElement("td"),used=document.createElement("td"),device=document.createElement("td"),action=document.createElement("td");const badge=document.createElement("span");badge.className="status "+c.status;badge.textContent=c.status==="available"?"غير مستعمل":c.status==="redeemed"?"مستعمل":"ملغى";status.append(badge);created.textContent=new Date(c.created_at*1000).toLocaleString();used.textContent=c.redeemed_at?new Date(c.redeemed_at*1000).toLocaleString():"—";device.textContent=c.device_id||"—";if(c.status==="available"){const btn=document.createElement("button");btn.className="small-action";btn.textContent="إلغاء";btn.addEventListener("click",async()=>{if(!confirm("إلغاء هذا الكود؟"))return;try{await call("/codes/"+encodeURIComponent(c.id),{method:"DELETE"});await loadCodes()}catch(err){alert(readable(err.message))}});action.append(btn)}else action.textContent="—";tr.append(status,created,used,device,action);body.append(tr)}if(!d.codes.length)body.innerHTML="<tr><td colspan='5'>لا توجد أكواد بعد.</td></tr>"}catch(err){if(err.message==="UNAUTHORIZED"){token="";sessionStorage.removeItem("colony_admin_token");show("loginPanel")}message("loginNotice",readable(err.message))}}
$("generateForm").addEventListener("submit",async e=>{e.preventDefault();const b=e.currentTarget.querySelector("button");b.disabled=true;try{const d=await call("/codes",{method:"POST",body:JSON.stringify({count:Number(new FormData(e.currentTarget).get("count"))})});$("codesText").textContent=d.codes.join("\n");$("newCodes").hidden=false;await loadCodes()}catch(err){alert(readable(err.message))}finally{b.disabled=false}});
$("copyCodes").addEventListener("click",async()=>{await navigator.clipboard.writeText($("codesText").textContent);$("copyCodes").textContent="تم النسخ"});
$("refreshCodes").addEventListener("click",loadCodes);
$("refreshDevices").addEventListener("click",loadDevices);
$("logoutButton").addEventListener("click",async()=>{try{await call("/logout",{method:"POST"})}catch{}token="";sessionStorage.removeItem("colony_admin_token");show("loginPanel")});
(async()=>{if(token){try{const d=await call("/me");await dashboard(d.admin.email);return}catch{token="";sessionStorage.removeItem("colony_admin_token")}}try{const r=await fetch(API+"/setup");const state=await r.json();show(state.setupRequired?"setupPanel":"loginPanel")}catch{show("loginPanel")}})();
