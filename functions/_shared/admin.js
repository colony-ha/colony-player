import { json, randomHex, sha256 } from "./auth.js";

const enc = new TextEncoder();
const ITERATIONS = 210000;

export { json };

export async function passwordHash(password) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const material = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({name:"PBKDF2",salt,iterations:ITERATIONS,hash:"SHA-256"},material,256);
  const hex = bytes => [...new Uint8Array(bytes)].map(x=>x.toString(16).padStart(2,"0")).join("");
  return `pbkdf2$${ITERATIONS}$${hex(salt)}$${hex(bits)}`;
}

export async function verifyPassword(password, stored) {
  const [kind, rounds, saltHex, expected] = String(stored || "").split("$");
  if (kind !== "pbkdf2" || !/^\d+$/.test(rounds) || !saltHex || !expected) return false;
  const salt = Uint8Array.from(saltHex.match(/.{2}/g) || [], x=>parseInt(x,16));
  const material = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({name:"PBKDF2",salt,iterations:Number(rounds),hash:"SHA-256"},material,256);
  const actual = [...new Uint8Array(bits)].map(x=>x.toString(16).padStart(2,"0")).join("");
  return actual.length === expected.length && [...actual].reduce((n,c,i)=>n | (c.charCodeAt(0)^expected.charCodeAt(i)),0) === 0;
}

export async function currentAdmin(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token || !env.DB) return null;
  const tokenHash = await sha256(token);
  const now = Math.floor(Date.now()/1000);
  return await env.DB.prepare(`SELECT a.id,a.email FROM admin_sessions s JOIN admin_accounts a ON a.id=s.admin_id
    WHERE s.token_hash=? AND s.expires_at>? AND a.status='active' LIMIT 1`).bind(tokenHash,now).first();
}

export async function audit(env, adminId, action, targetType=null, targetId=null, details=null) {
  await env.DB.prepare("INSERT INTO admin_audit_logs (id,admin_id,action,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?,?)")
    .bind(crypto.randomUUID(),adminId,action,targetType,targetId,details,Math.floor(Date.now()/1000)).run();
}

export function makeCode() {
  const alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes=crypto.getRandomValues(new Uint8Array(16));
  const raw=[...bytes].map(b=>alphabet[b%alphabet.length]).join("");
  return "CP-"+raw.slice(0,4)+"-"+raw.slice(4,8)+"-"+raw.slice(8,12)+"-"+raw.slice(12,16);
}
