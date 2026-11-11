const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type"
};

const ADMIN_HTML = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HB Browser - إدارة الأكواد</title><style>*{box-sizing:border-box}body{margin:0;background:#090a0c;color:#eee;font-family:Arial,sans-serif;padding:22px}main{max-width:520px;margin:35px auto;background:#17191d;border:1px solid #66552a;border-radius:18px;padding:22px}h2{color:#d4af37;text-align:center}label{display:block;margin:16px 0 7px}input,select,button{width:100%;padding:13px;border-radius:10px;border:1px solid #66552a;background:#0f1115;color:#fff;font-size:16px}button{background:#d4af37;color:#090a0c;font-weight:bold;border:0;margin-top:14px;cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#0b0d10;padding:14px;border-radius:10px;min-height:50px}small{color:#aaa}</style><main><h2>HB Browser | إدارة الأكواد</h2><label>مفتاح الإدارة</label><input id="key" type="password" autocomplete="off" placeholder="أدخل مفتاح الإدارة"><label>مدة الاشتراك</label><select id="days"><option value="30">30 يوماً</option><option value="90">90 يوماً</option><option value="180">180 يوماً</option><option value="365">365 يوماً</option><option value="0">دائم</option></select><button onclick="createCode()">إنشاء كود جديد</button><label>إيقاف كود</label><input id="revoke" placeholder="HB-XXXX-XXXX-XXXX"><button onclick="revokeCode()">إيقاف الكود</button><label>النتيجة</label><pre id="out">الأكواد الجديدة تظهر هنا مرة واحدة. احتفظ بها.</pre><small>لا تشارك مفتاح الإدارة مع العملاء.</small></main><script>async function send(action,extra){const r=await fetch('/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,admin_key:document.getElementById('key').value,...extra})});return await r.json()}async function createCode(){try{const x=await send('admin_create',{duration_days:Number(document.getElementById('days').value)});document.getElementById('out').textContent=x.ok?('الكود: '+x.code+'\\nالمدة: '+(x.duration_days===0?'دائم':x.duration_days+' يوم')+'\\nاحتفظ به الآن.'):x.message}catch(e){document.getElementById('out').textContent='تعذر الاتصال: '+e}}async function revokeCode(){try{const x=await send('admin_revoke',{code:document.getElementById('revoke').value});document.getElementById('out').textContent=x.message}catch(e){document.getElementById('out').textContent='تعذر الاتصال: '+e}}</script></html>`;

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}
function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
}
function randomCode() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  const raw = [...bytes].map(x => alphabet[x % alphabet.length]).join("");
  return "HB-" + raw.slice(0,4) + "-" + raw.slice(4,8) + "-" + raw.slice(8,12);
}
async function handleApi(request, env) {
  let input;
  try { input = await request.json(); } catch { return json({ok:false,message:"JSON غير صالح"},400); }
  const action = String(input.action || "");
  const now = Math.floor(Date.now() / 1000);
  if (action === "activate") {
    const code = String(input.code || "").trim().toUpperCase();
    const device = String(input.device_id || "").trim();
    if (!code || !device) return json({ok:false,message:"بيانات التفعيل ناقصة"},400);
    const ch = await sha256(code), dh = await sha256(device);
    const row = await env.DB.prepare("SELECT * FROM licenses WHERE code_hash = ?").bind(ch).first();
    if (!row || row.revoked) return json({ok:false,message:"الكود غير صالح أو موقوف"},403);
    if (row.device_hash && row.device_hash !== dh) return json({ok:false,message:"هذا الكود مرتبط بجهاز آخر"},403);
    if (row.expires_at !== null && row.expires_at !== undefined && Number(row.expires_at) < now) return json({ok:false,message:"انتهت صلاحية الاشتراك"},403);
    const expires = row.activated_at ? row.expires_at : (Number(row.duration_days) === 0 ? null : now + Number(row.duration_days) * 86400);
    const tokenBytes = crypto.getRandomValues(new Uint8Array(32));
    const token = [...tokenBytes].map(x => x.toString(16).padStart(2,"0")).join("");
    const tokenHash = await sha256(token);
    const update = await env.DB.prepare("UPDATE licenses SET device_hash = ?, activated_at = COALESCE(activated_at, ?), expires_at = ?, token_hash = ? WHERE code_hash = ? AND revoked = 0 AND (device_hash IS NULL OR device_hash = ?)").bind(dh, now, expires, tokenHash, ch, dh).run();
    if (!update.meta.changes) return json({ok:false,message:"تعذر ربط الكود بهذا الجهاز"},409);
    return json({ok:true,token,expires_at:expires === null ? "دائم" : new Date(expires*1000).toISOString().slice(0,10)});
  }
  if (action === "validate") {
    const token = String(input.token || "").trim(), device = String(input.device_id || "").trim();
    if (!token || !device) return json({ok:false,message:"بيانات التحقق ناقصة"},400);
    const row = await env.DB.prepare("SELECT expires_at, device_hash, revoked FROM licenses WHERE token_hash = ?").bind(await sha256(token)).first();
    const valid = row && !row.revoked && row.device_hash === await sha256(device) && (row.expires_at === null || row.expires_at === undefined || Number(row.expires_at) >= now);
    return valid ? json({ok:true,expires_at:row.expires_at}) : json({ok:false,message:"الاشتراك غير صالح أو منتهي"},403);
  }
  if (action === "admin_create" || action === "admin_revoke") {
    const supplied = String(input.admin_key || "");
    if (!env.HB_ADMIN_KEY || supplied.length < 1 || supplied !== env.HB_ADMIN_KEY) return json({ok:false,message:"غير مصرح"},401);
    if (action === "admin_create") {
      const days = Number(input.duration_days);
      if (![30,90,180,365,0].includes(days)) return json({ok:false,message:"مدة غير مدعومة"},400);
      const code = randomCode();
      await env.DB.prepare("INSERT INTO licenses (code_hash,suffix,duration_days,created_at) VALUES (?,?,?,?)").bind(await sha256(code),code.slice(-4),days,now).run();
      return json({ok:true,code,duration_days:days});
    }
    const code = String(input.code || "").trim().toUpperCase();
    const result = await env.DB.prepare("UPDATE licenses SET revoked = 1, token_hash = NULL WHERE code_hash = ?").bind(await sha256(code)).run();
    const changed = result.meta.changes > 0;
    return json({ok:changed,message:changed ? "تم إيقاف الكود" : "الكود غير موجود"});
  }
  return json({ok:false,message:"طلب غير معروف"},404);
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/admin")) {
      return new Response(ADMIN_HTML, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
    }
    if (request.method === "POST" && (url.pathname === "/api" || url.pathname === "/")) {
      try {
        await env.DB.prepare("CREATE TABLE IF NOT EXISTS licenses (code_hash TEXT PRIMARY KEY, suffix TEXT NOT NULL, duration_days INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER, device_hash TEXT, activated_at INTEGER, token_hash TEXT, revoked INTEGER NOT NULL DEFAULT 0)").run();
        return await handleApi(request, env);
      } catch (error) {
        return json({ok:false,message:"خطأ في الخادم أو قاعدة البيانات"},500);
      }
    }
    return new Response("HB Browser License API", { status: 200, headers: { "Content-Type":"text/plain; charset=utf-8" } });
  }
};
