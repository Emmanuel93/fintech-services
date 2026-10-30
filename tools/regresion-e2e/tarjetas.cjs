// Tarjetas del video de demostración.
//
// Se dibujan con el Chromium que ya trae Playwright y no con `drawtext` de ffmpeg: las fuentes de
// la imagen de ffmpeg no son las de la marca, y un título con acentos rotos en el video que se le
// enseña a un cliente arruina la pieza por una razón que no tiene nada que ver con el producto.
//
// Una sola familia tipográfica —Inter, la de la marca y la del sitio—. Cinco formatos: portada,
// personajes, panel narrativo (con el hueco de la grabación), momento (el dinero que llega) y cierre.
//
//   node tarjetas.cjs <tarjetas.json> <carpeta-salida> <ruta-a-node_modules-del-backoffice>
//
// tarjetas.json = [{ archivo, tipo: portada|personajes|panel|momento|cierre, … }] — ver video.py
const path = require('path');
const fs = require('fs');

const [, , entrada, salida, modulos] = process.argv;
const { chromium } = require(path.join(modulos, 'playwright'));

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const LOGO = `<svg viewBox="0 0 64 64" width="72" height="72" aria-hidden="true">
  <rect width="64" height="64" rx="14" fill="#FFFFFF"/>
  <g stroke-linecap="round" stroke-width="5" fill="none">
    <path d="M23 17 L23 47" stroke="#0D47A1"/><path d="M25.5 32 L43 19" stroke="#00B894"/>
    <path d="M25.5 32 L43 45" stroke="#0D47A1"/></g></svg>`;


// ── Panel narrativo ─────────────────────────────────────────────────────────────────────────────
// Se dibuja con un HUECO transparente donde va la grabación: el video se compone debajo y este PNG
// encima, así el fondo, el marco del teléfono o la ventana y el texto quedan nítidos y la pantalla
// grabada no se reescala. El hueco se recorta con `clip-path: path(evenodd, …)`.
const hueco = ({ x, y, w, h, r }) =>
  `M0 0H1920V1080H0Z M${x + r} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${y + h - r}` +
  `A${r} ${r} 0 0 1 ${x + w - r} ${y + h}H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;

const panelHtml = (t) => {
  const tel = t.layout === 'telefono';
  const m = t.hueco;
  // En el navegador el hueco va debajo de la barra de la ventana: sólo se redondean las esquinas
  // de abajo, y la barra cubre las de arriba.
  const marco = tel
    ? `<div class="bisel" style="left:${m.x - 16}px;top:${m.y - 16}px;width:${m.w + 32}px;height:${m.h + 32}px;border-radius:${m.r + 16}px"></div>`
    : `<div class="ventana" style="left:${m.x}px;top:${m.y - 44}px;width:${m.w}px">
         <i></i><i></i><i></i><span>${esc(t.url || 'backoffice.kredius.mx')}</span></div>
       <div class="borde" style="left:${m.x - 1}px;top:${m.y - 45}px;width:${m.w}px;height:${m.h + 44}px"></div>`;
  const area = tel ? 'left:140px;top:64px;width:1040px;bottom:64px' : 'left:1392px;top:56px;width:470px;bottom:64px';
  const beats = (t.beats || []).map((b, i) => `<li class="${b.estado}"><b>${b.estado === 'hecho' ? '✓' : i + 1}</b><span>${esc(b.texto)}</span></li>`).join('');
  return `<!doctype html><html lang="es"><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;600;700;800&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box;margin:0}
  html,body{width:1920px;height:1080px;background:transparent;font-family:Inter,sans-serif;color:#fff}
  .fondo{position:absolute;inset:0;clip-path:path(evenodd,'${hueco(m)}');
    background:radial-gradient(1300px 900px at ${tel ? '80% 20%' : '90% 10%'},#1565C0 0%,#0D47A1 45%,#0A3578 100%)}
  .bisel{position:absolute;border:16px solid #0B1220;box-shadow:0 30px 80px rgba(0,0,0,.45),inset 0 0 0 2px #2A3446}
  .ventana{position:absolute;height:44px;background:#E8EEF6;border-radius:14px 14px 0 0;display:flex;align-items:center;gap:8px;padding:0 18px}
  .ventana i{width:12px;height:12px;border-radius:50%;background:#C6D1DF}
  .ventana span{margin-left:18px;flex:1;height:26px;border-radius:13px;background:#fff;color:#4B5B70;font-size:14px;font-weight:600;display:flex;align-items:center;padding:0 14px}
  .borde{position:absolute;border-radius:14px;box-shadow:0 30px 80px rgba(0,0,0,.45);pointer-events:none}
  .area{position:absolute;display:flex;flex-direction:column}
  .top{display:flex;align-items:center;justify-content:space-between}
  .marca{display:flex;align-items:center;gap:14px;font-weight:800;font-size:${tel ? 34 : 28}px;letter-spacing:-.02em}
  .marca svg{width:${tel ? 56 : 46}px;height:${tel ? 56 : 46}px}
  .fecha{font-weight:500;font-size:${tel ? 22 : 18}px;color:#BFD3EE}
  .persona{display:flex;align-items:center;gap:16px;margin-top:${tel ? 64 : 40}px}
  .av{width:${tel ? 64 : 52}px;height:${tel ? 64 : 52}px;border-radius:50%;background:#00B894;color:#04241C;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:${tel ? 24 : 20}px}
  .persona b{display:block;font-size:${tel ? 28 : 22}px;font-weight:700}
  .persona span{font-size:${tel ? 21 : 17}px;color:#BFD3EE;font-weight:500}
  .k{margin-top:${tel ? 40 : 30}px;font-weight:700;font-size:${tel ? 22 : 17}px;letter-spacing:.14em;text-transform:uppercase;color:#00D3A6}
  .t{font-weight:800;font-size:${tel ? 70 : 44}px;line-height:1.05;letter-spacing:-.025em;margin-top:14px}
  ol{list-style:none;padding:0;margin:${tel ? 44 : 30}px 0 0;display:flex;flex-direction:column;gap:${tel ? 16 : 12}px}
  li{display:flex;align-items:center;gap:${tel ? 20 : 14}px;font-size:${tel ? 30 : 21}px;font-weight:600;color:rgba(255,255,255,.42);
     padding:${tel ? '14px 20px' : '11px 14px'};border-radius:16px;transition:none}
  li b{flex:0 0 ${tel ? 44 : 34}px;height:${tel ? 44 : 34}px;border-radius:50%;border:2px solid rgba(255,255,255,.3);display:flex;align-items:center;justify-content:center;font-size:${tel ? 20 : 16}px}
  li.hecho{color:#CFE0F5}li.hecho b{background:#00B894;border-color:#00B894;color:#04241C}
  li.actual{color:#fff;background:rgba(255,255,255,.12);box-shadow:inset 0 0 0 2px rgba(0,211,166,.7)}
  li.actual b{background:#fff;border-color:#fff;color:#0D47A1}
  .pie{margin-top:auto;font-weight:500;font-size:${tel ? 20 : 16}px;color:#9DB8E0}
</style>
<div class="fondo"></div>${marco}
<div class="area" style="${area}">
  <div class="top"><div class="marca">${LOGO}Kredius</div><div class="fecha">${esc(t.fecha)}</div></div>
  <div class="persona"><div class="av">${esc(t.persona.iniciales)}</div><div><b>${esc(t.persona.nombre)}</b><span>${esc(t.persona.rol)}</span></div></div>
  <div class="k">${esc(t.paso)} · ${esc(t.canal)}</div>
  <div class="t">${esc(t.titulo)}</div>
  <ol>${beats}</ol>
  <div class="pie">kredius.mx</div>
</div></html>`;
};

// ── Panel amplio (demo completa) ────────────────────────────────────────────────────────────────
// El backoffice casi a pantalla completa —la grabación de 1280×800 escalada para que se lean las
// cifras de una póliza o una conciliación— con la narración arriba: paso, titular y el momento en
// curso en una pastilla. La lista completa de momentos no cabe sin robarle espacio a la pantalla,
// y en estos actos lo que importa es lo que la pantalla muestra.
const amplioHtml = (t) => {
  const m = t.hueco;
  const actual = (t.beats || []).findIndex((b) => b.estado === 'actual');
  const beat = actual >= 0 ? t.beats[actual] : null;
  return `<!doctype html><html lang="es"><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;600;700;800&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box;margin:0}
  html,body{width:1920px;height:1080px;background:transparent;font-family:Inter,sans-serif;color:#fff}
  .fondo{position:absolute;inset:0;clip-path:path(evenodd,'${hueco(m)}');
    background:radial-gradient(1500px 700px at 70% 0%,#1565C0 0%,#0D47A1 45%,#0A3578 100%)}
  .borde{position:absolute;left:${m.x}px;top:${m.y}px;width:${m.w}px;height:${m.h}px;border-radius:${m.r}px;
    box-shadow:0 0 0 2px rgba(255,255,255,.18),0 24px 70px rgba(0,0,0,.45)}
  .marca{position:absolute;left:40px;top:34px;display:flex;align-items:center;gap:12px;font-weight:800;font-size:26px}
  .marca svg{width:44px;height:44px}
  .persona{position:absolute;left:40px;top:${m.y + 20}px;width:${m.x - 60}px;display:flex;flex-direction:column;gap:10px}
  .av{width:56px;height:56px;border-radius:50%;background:#00B894;color:#04241C;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:21px}
  .persona b{font-size:20px;font-weight:700}.persona span{font-size:16px;color:#BFD3EE;font-weight:500;line-height:1.3}
  .cab{position:absolute;left:${m.x}px;top:22px;width:${m.w}px;display:flex;align-items:flex-end;justify-content:space-between;gap:30px}
  .k{font-weight:700;font-size:17px;letter-spacing:.14em;text-transform:uppercase;color:#00D3A6}
  .t{font-weight:800;font-size:44px;line-height:1.05;letter-spacing:-.02em;margin-top:8px}
  .pill{flex:0 0 auto;max-width:640px;display:flex;align-items:center;gap:14px;background:rgba(255,255,255,.12);
    box-shadow:inset 0 0 0 2px rgba(0,211,166,.75);border-radius:18px;padding:14px 22px;font-weight:700;font-size:24px;line-height:1.2}
  .pill b{flex:0 0 38px;height:38px;border-radius:50%;background:#fff;color:#0D47A1;display:flex;align-items:center;justify-content:center;font-size:18px}
  .prog{position:absolute;right:40px;top:${m.y + 20}px;width:${1920 - m.x - m.w - 60}px;display:flex;flex-direction:column;gap:10px;align-items:flex-end}
  .prog i{display:block;width:12px;height:12px;border-radius:50%;background:rgba(255,255,255,.28)}
  .prog i.h{background:#00B894}.prog i.a{background:#fff;box-shadow:0 0 0 4px rgba(0,211,166,.5)}
</style>
<div class="fondo"></div><div class="borde"></div>
<div class="marca">${LOGO}Kredius</div>
<div class="persona"><div class="av">${esc(t.persona.iniciales)}</div><b>${esc(t.persona.nombre)}</b><span>${esc(t.persona.rol)}</span></div>
<div class="cab"><div><div class="k">${esc(t.paso)} · ${esc(t.canal)}</div><div class="t">${esc(t.titulo)}</div></div>
  ${beat ? `<div class="pill"><b>${actual + 1}</b><span>${esc(beat.texto)}</span></div>` : ''}</div>
<div class="prog">${(t.beats || []).map((b) => `<i class="${b.estado === 'hecho' ? 'h' : b.estado === 'actual' ? 'a' : ''}"></i>`).join('')}</div>
</html>`;
};

const personajesHtml = (t) => `<!doctype html><html lang="es"><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;700;800&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box;margin:0}
  html,body{width:1920px;height:1080px;font-family:Inter,sans-serif;color:#fff;
    background:radial-gradient(1400px 900px at 85% 10%,#1565C0 0%,#0D47A1 45%,#0A3578 100%)}
  .top{position:absolute;top:72px;left:140px;right:140px;display:flex;align-items:center;justify-content:space-between}
  .marca{display:flex;align-items:center;gap:22px;font-weight:800;font-size:40px;letter-spacing:-.02em}
  .fecha{font-weight:500;font-size:26px;color:#D6E4F7}
  .w{position:absolute;left:140px;right:140px;top:250px}
  .k{font-weight:700;font-size:28px;letter-spacing:.14em;text-transform:uppercase;color:#00D3A6}
  .t{font-weight:800;font-size:92px;line-height:1.04;letter-spacing:-.025em;margin-top:22px}
  .g{display:grid;grid-template-columns:repeat(4,1fr);gap:28px;margin-top:70px}
  .c{background:rgba(255,255,255,.08);border-radius:28px;padding:34px 30px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.12)}
  .av{width:88px;height:88px;border-radius:50%;background:#00B894;color:#04241C;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:32px}
  .n{font-weight:800;font-size:36px;margin-top:26px;letter-spacing:-.01em}
  .r{font-weight:600;font-size:24px;color:#D6E4F7;margin-top:6px}
  .ch{display:inline-block;margin-top:22px;font-weight:700;font-size:18px;letter-spacing:.1em;text-transform:uppercase;color:#00D3A6}
  .pie{position:absolute;left:140px;right:140px;bottom:64px;display:flex;justify-content:space-between;font-weight:500;font-size:24px;color:#9DB8E0}
</style>
<div class="top"><div class="marca">${LOGO}Kredius</div><div class="fecha">${esc(t.fecha)}</div></div>
<div class="w"><div class="k">${esc(t.kicker)}</div><div class="t">${esc(t.titulo)}</div>
<div class="g">${(t.elenco || []).map((p) => `<div class="c"><div class="av">${esc(p.iniciales)}</div><div class="n">${esc(p.nombre)}</div><div class="r">${esc(p.rol)}</div><div class="ch">${esc(p.canal)}</div></div>`).join('')}</div></div>
<div class="pie"><span>${esc(t.pie || 'Core de crédito para redes de distribución')}</span><span>kredius.mx</span></div></html>`;

const html = (t) => `<!doctype html><html lang="es"><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;700;800&display=swap" rel="stylesheet">
<style>
  :root{--blue:#0D47A1;--deep:#0A3578;--mint:#00B894;--ice:#D6E4F7;--soft:#9DB8E0}
  *{box-sizing:border-box}
  html,body{margin:0;width:1920px;height:1080px;font-family:Inter,sans-serif;color:#fff;
    background:radial-gradient(1400px 900px at 85% 10%,#1565C0 0%,var(--blue) 45%,var(--deep) 100%)}
  .top{position:absolute;top:72px;left:140px;right:140px;display:flex;align-items:center;justify-content:space-between}
  .marca{display:flex;align-items:center;gap:22px;font-weight:800;font-size:40px;letter-spacing:-.02em}
  .fecha{font-weight:500;font-size:26px;color:var(--ice)}
  .w{position:absolute;left:140px;right:140px;top:220px;bottom:150px;display:flex;flex-direction:column;justify-content:center}
  .k{font-weight:700;font-size:28px;letter-spacing:.14em;text-transform:uppercase;color:var(--mint)}
  .t{font-weight:800;font-size:92px;line-height:1.04;letter-spacing:-.025em;margin-top:26px;max-width:1500px}
  .s{font-weight:500;font-size:36px;line-height:1.42;margin-top:34px;color:var(--ice);max-width:1360px}
  .num{position:absolute;right:140px;bottom:110px;font-weight:800;font-size:220px;line-height:1;color:rgba(255,255,255,.07)}
  .pie{position:absolute;left:140px;right:140px;bottom:64px;display:flex;justify-content:space-between;
    font-weight:500;font-size:24px;color:var(--soft)}
  .portada .t{font-size:112px}
  .momento{background:radial-gradient(1400px 900px at 80% 20%,#14D3A6 0%,var(--mint) 40%,#00876C 100%)}
  .momento .k{color:#E6FFF8} .momento .s{color:#E6FFF8} .momento .pie{color:#CFF7EC}
  .monto{font-weight:800;font-size:150px;letter-spacing:-.03em;margin-top:18px}
  ul{list-style:none;padding:0;margin:40px 0 0;display:grid;grid-template-columns:1fr 1fr;gap:22px 64px;max-width:1500px}
  li{font-weight:700;font-size:34px;display:flex;gap:18px;align-items:baseline}
  li:before{content:"";flex:0 0 14px;height:14px;border-radius:50%;background:var(--mint);transform:translateY(-4px)}
  .cta{margin-top:56px;display:inline-flex;align-self:flex-start;background:var(--mint);color:#04241C;
    font-weight:800;font-size:34px;padding:22px 40px;border-radius:999px}
</style>
<body class="${esc(t.tipo || 'paso')}">
  <div class="top"><div class="marca">${LOGO}Kredius</div><div class="fecha">${esc(t.fecha)}</div></div>
  <div class="w">
    <div class="k">${esc(t.kicker)}</div>
    <div class="t">${esc(t.titulo)}</div>
    ${t.monto ? `<div class="monto">${esc(t.monto)}</div>` : ''}
    ${t.sub ? `<div class="s">${esc(t.sub)}</div>` : ''}
    ${t.puntos && t.puntos.length ? `<ul>${t.puntos.map((p) => `<li>${esc(p)}</li>`).join('')}</ul>` : ''}
    ${t.cta ? `<div class="cta">${esc(t.cta)}</div>` : ''}
  </div>
  ${t.numero ? `<div class="num">${esc(t.numero)}</div>` : ''}
  <div class="pie"><span>${esc(t.pie || 'Core de crédito para redes de distribución')}</span><span>kredius.mx</span></div>
</body></html>`;

(async () => {
  const tarjetas = JSON.parse(fs.readFileSync(entrada, 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  for (const t of tarjetas) {
    const doc = t.tipo === 'panel' ? (t.layout === 'amplio' ? amplioHtml(t) : panelHtml(t))
      : t.tipo === 'personajes' ? personajesHtml(t) : html(t);
    await page.setContent(doc, { waitUntil: 'networkidle' }).catch(() => page.setContent(doc));
    // La fuente llega de Google Fonts: sin esperarla, la primera tarjeta sale con la de respaldo.
    await page.evaluate(() => document.fonts.ready);
    // El panel lleva transparencia: es el hueco por donde se ve la grabación.
    await page.screenshot({ path: path.join(salida, t.archivo), omitBackground: t.tipo === 'panel' });
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
