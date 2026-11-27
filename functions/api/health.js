export async function onRequestGet({ env }) {
  try {
    await env.DB.prepare("SELECT 1 AS ok").first();
    return Response.json({ ok: true, service: "COLONY PLAYER API", database: true });
  } catch {
    return Response.json({ ok: false, service: "COLONY PLAYER API", database: false }, { status: 503 });
  }
}
