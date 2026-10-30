// Escenas del video de arquitectura.
//
// Cada escena es un diagrama —zonas, nodos y aristas— que se cuenta por momentos, como los actos
// del video comercial: en cada momento aparecen piezas, se iluminan las que importan, se señalan
// con el mismo número que lleva el momento en el panel y, si hay flujo, los paquetes recorren las
// aristas. Lo que no se nombra en el momento se atenúa: el ojo va a donde dice el panel.
//
// Se dibuja con el Chromium de Playwright —la misma tipografía que las tarjetas— y se captura
// cuadro a cuadro. El reloj lo pone este script y no el navegador: la misma escena da los mismos
// cuadros en cada corrida, y una animación nunca se ve a saltos porque la máquina iba lenta.
//
//   node diagramas.cjs <escenas.json> <carpeta-salida> <ruta-a-node_modules-del-backoffice>
//
// Por cada momento j de una escena deja, en <salida>/<id>/:
//   b<j>-t00…t14.jpg   la transición desde el momento anterior (0.5 s a 30 cps)
//   b<j>-l00…l29.jpg   un segundo que se repite mientras dure el momento: los paquetes en marcha
//                      (sólo b<j>-l00.jpg si el momento no tiene flujo)
// y cuadros.json con cuántos cuadros de cada tipo hay. Una escena que no cambió no se vuelve a
// capturar: su firma —el contenido más este archivo— queda en .firma.
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const [, , entrada, salida, modulos] = process.argv;
const { chromium } = require(path.join(modulos, 'playwright'));

const FPS = 30;
const TRANSICION = 15; // cuadros: 0.5 s
const BUCLE = FPS; // un segundo: todas las animaciones tienen periodo de 1 s

const LOGO = `<svg viewBox="0 0 64 64" width="46" height="46" aria-hidden="true">
  <rect width="64" height="64" rx="14" fill="#FFFFFF"/>
  <g stroke-linecap="round" stroke-width="5" fill="none">
    <path d="M23 17 L23 47" stroke="#0D47A1"/><path d="M25.5 32 L43 19" stroke="#00B894"/>
    <path d="M25.5 32 L43 45" stroke="#0D47A1"/></g></svg>`;

// La página: el fondo de la marca, el lienzo del diagrama a la izquierda y el panel a la derecha.
// Lo que cambia cuadro a cuadro lo pinta `pinta()`, que define `construye()` dentro de la página.
const PAGINA = `<!doctype html><html lang="es"><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;600;700;800&family=JetBrains+Mono:wght@500;600;700&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box;margin:0}
  html,body{width:1920px;height:1080px;overflow:hidden;font-family:Inter,sans-serif;color:#fff;
    background:radial-gradient(1500px 900px at 90% 0%,#1565C0 0%,#0D47A1 45%,#0A3578 100%)}
  .lienzo{position:absolute;left:48px;top:48px;width:1312px;height:984px;border-radius:22px;overflow:hidden;
    background-color:#082149;box-shadow:0 0 0 1px rgba(255,255,255,.14),0 30px 80px rgba(0,0,0,.4);
    background-image:radial-gradient(rgba(157,184,224,.16) 1.2px,transparent 1.3px);background-size:24px 24px}
  .capa{position:absolute;inset:0}
  svg.capa{overflow:visible}
  .g{position:absolute;border-radius:18px}
  .g.banda{background:color-mix(in srgb,var(--c) 9%,transparent);box-shadow:inset 0 0 0 1.5px color-mix(in srgb,var(--c) 30%,transparent)}
  .g.limite{border:2px dashed color-mix(in srgb,var(--c) 70%,transparent);background:color-mix(in srgb,var(--c) 4%,transparent)}
  .g .eti{position:absolute;white-space:nowrap;left:16px;top:12px;font-weight:800;font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:var(--c)}
  .g.limite .eti{top:-11px;left:18px;background:#082149;padding:0 8px;line-height:20px}
  .g.lado .eti{top:50%;transform:translateY(-50%);width:150px;white-space:normal;line-height:1.25}
  .g.vida{border-left:2px dashed color-mix(in srgb,var(--c) 55%,transparent);border-radius:0}
  .n{position:absolute;display:flex;align-items:center;gap:12px;padding:0 14px;border-radius:14px;
    background:#0C2B5C;border:2px solid var(--c)}
  .n.objetivo{border-style:dashed;background:#0A2550}
  .n .ic{flex:0 0 40px;height:40px;border-radius:11px;display:flex;align-items:center;justify-content:center;
    background:color-mix(in srgb,var(--c) 20%,transparent)}
  .n .ic svg{width:24px;height:24px;stroke:var(--c);fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
  .n .tx{min-width:0}
  .n b,.n span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .n b{display:block;font-size:21px;font-weight:700;line-height:1.15;letter-spacing:-.005em}
  .n span{display:block;font:500 16px/1.3 'JetBrains Mono',monospace;color:#B4CAE8;margin-top:3px}
  .n.mini{gap:8px;padding:0 10px;border-radius:11px}
  .n.mini b{font-size:17px;font-weight:600}
  .n.mini .ic{flex-basis:28px;height:28px;border-radius:8px}.n.mini .ic svg{width:17px;height:17px}
  .n.centro{justify-content:center;text-align:center}
  .n.barra{background:var(--c);border:none;border-radius:7px;padding:0}
  .n.barra.espera{background:repeating-linear-gradient(135deg,color-mix(in srgb,var(--c) 55%,transparent) 0 8px,transparent 8px 16px);
    box-shadow:inset 0 0 0 2px var(--c)}
  .n.texto{background:none;border:none;padding:0}
  .n.texto.der{justify-content:flex-end;text-align:right}
  .n .tag{position:absolute;right:-2px;top:-12px;font:700 11px/20px Inter,sans-serif;letter-spacing:.12em;text-transform:uppercase;
    padding:0 8px;border-radius:7px;background:#082149;color:var(--c);box-shadow:inset 0 0 0 1.5px var(--c)}
  .el{position:absolute;transform:translate(-50%,-50%);white-space:nowrap;font:600 15.5px/1 'JetBrains Mono',monospace;
    padding:6px 9px;border-radius:8px;background:#082149;box-shadow:inset 0 0 0 1px currentColor}
  .ma{position:absolute;display:flex;align-items:center;gap:10px;max-width:420px;padding:9px 16px 9px 9px;border-radius:14px;
    background:#fff;color:#0A2A5E;font-weight:700;font-size:20px;line-height:1.2;box-shadow:0 14px 34px rgba(0,0,0,.35)}
  .ma b,.badge{flex:0 0 32px;height:32px;border-radius:50%;background:#00D3A6;color:#04241C;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:16px}
  .badge{position:absolute;width:32px;box-shadow:0 0 0 3px #082149}
  .area{position:absolute;left:1396px;top:48px;width:476px;bottom:48px;display:flex;flex-direction:column}
  .top{display:flex;align-items:center;justify-content:space-between}
  .marca{display:flex;align-items:center;gap:14px;font-weight:800;font-size:28px;letter-spacing:-.02em}
  .seccion{font-weight:700;font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:#BFD3EE}
  .chip{display:flex;align-items:center;gap:14px;margin-top:40px}
  .chip i{flex:0 0 52px;height:52px;border-radius:14px;display:flex;align-items:center;justify-content:center;background:color-mix(in srgb,var(--c) 22%,transparent);box-shadow:inset 0 0 0 2px var(--c)}
  .chip i svg{width:28px;height:28px;stroke:var(--c);fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
  .chip b{display:block;font-size:22px;font-weight:700}.chip span{font-size:17px;color:#BFD3EE;font-weight:500}
  .estado{display:inline-flex;align-self:flex-start;align-items:center;gap:8px;margin-top:22px;padding:6px 12px;border-radius:9px;
    font-weight:700;font-size:14px;letter-spacing:.08em;text-transform:uppercase;color:var(--c);box-shadow:inset 0 0 0 1.5px var(--c)}
  .estado:before{content:"";width:8px;height:8px;border-radius:50%;background:var(--c)}
  .k{margin-top:22px;font-weight:700;font-size:17px;letter-spacing:.14em;text-transform:uppercase;color:#00D3A6}
  .t{font-weight:800;font-size:42px;line-height:1.06;letter-spacing:-.022em;margin-top:12px}
  ol{list-style:none;padding:0;margin:28px 0 0;display:flex;flex-direction:column;gap:10px}
  li{display:flex;align-items:center;gap:14px;font-size:20px;line-height:1.25;font-weight:600;color:rgba(255,255,255,.42);padding:10px 14px;border-radius:16px}
  li b{flex:0 0 34px;height:34px;border-radius:50%;border:2px solid rgba(255,255,255,.3);display:flex;align-items:center;justify-content:center;font-size:16px}
  li.hecho{color:#CFE0F5}li.hecho b{background:#00B894;border-color:#00B894;color:#04241C}
  li.actual{color:#fff;background:rgba(255,255,255,.12);box-shadow:inset 0 0 0 2px rgba(0,211,166,.7)}
  li.actual b{background:#00D3A6;border-color:#00D3A6;color:#04241C}
  .ley{margin-top:auto;display:flex;flex-wrap:wrap;gap:10px 22px;font-size:16px;font-weight:600;color:#BFD3EE}
  .ley span{display:flex;align-items:center;gap:9px}
  .ley svg{width:38px;height:12px}
  .ley .obj{width:26px;height:16px;border-radius:5px;border:2px dashed #BFD3EE}
  .pie{margin-top:16px;display:flex;justify-content:space-between;font-weight:500;font-size:16px;color:#9DB8E0}
</style>
<div class="lienzo" id="lz">
  <div class="capa" id="cg"></div><svg class="capa" id="sv" viewBox="0 0 1312 984"></svg>
  <div class="capa" id="ce"></div><svg class="capa" id="sp" viewBox="0 0 1312 984"></svg><div class="capa" id="cn"></div>
  <svg class="capa" id="sm" viewBox="0 0 1312 984"></svg><div class="capa" id="cm"></div>
</div>
<div class="area" id="panel"></div></html>`;

// Dentro de la página. Recibe la escena, arma el DOM una vez y deja `window.pinta(j, k, t)`:
// momento j, avance k (0→1) de la transición desde j−1, y t segundos de reloj para los paquetes.
function construye(E) {
  const ICONOS = {
    usuario: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 4-6 8-6s8 2 8 6"/>',
    movil: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
    web: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M7 6.5h.01M10 6.5h.01"/>',
    globo: '<circle cx="12" cy="12" r="9.5"/><path d="M2.5 12h19M12 2.5c3 3.2 3 15.8 0 19M12 2.5c-3 3.2-3 15.8 0 19"/>',
    escudo: '<path d="M12 2.5l8 3v6c0 5-3.4 8.6-8 10.5C7.4 20.1 4 16.5 4 11.5v-6z"/><path d="M9 12l2 2 4-4"/>',
    balanceador: '<circle cx="5" cy="12" r="2.5"/><path d="M7.5 12h4M11.5 12l5-6h3M11.5 12l5 6h3M11.5 12h8"/>',
    proxy: '<path d="M3 12h15M14 7l5 5-5 5"/><path d="M8 4v16"/>',
    cubo: '<path d="M12 2.5l8.5 4.8v9.4L12 21.5l-8.5-4.8V7.3z"/><path d="M3.5 7.3L12 12l8.5-4.7M12 12v9.5"/>',
    bd: '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v13c0 1.7 3.6 3 8 3s8-1.3 8-3v-13"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    cola: '<rect x="3" y="4" width="18" height="4.5" rx="1.5"/><rect x="3" y="10" width="18" height="4.5" rx="1.5"/><rect x="3" y="16" width="18" height="4.5" rx="1.5"/>',
    llave: '<circle cx="8" cy="15" r="4.5"/><path d="M11.2 11.8L20 3M16.5 6.5l3 3"/>',
    grafica: '<path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 6-7"/>',
    banco: '<path d="M3 10l9-6 9 6"/><path d="M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20.5h18"/>',
    nube: '<path d="M7 19h10.5a4.5 4.5 0 0 0 .4-9A6.5 6.5 0 0 0 5.5 11.5 3.8 3.8 0 0 0 7 19z"/>',
    reloj: '<circle cx="12" cy="12" r="9.5"/><path d="M12 6.5V12l4 2.5"/>',
    candado: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7.5a4 4 0 0 1 8 0V11"/>',
    rayo: '<path d="M13 2L4.5 13.5H11L10 22l8.5-11.5H12z"/>',
    doc: '<path d="M6 2.5h8l4 4v15H6z"/><path d="M14 2.5v4h4M9 12h6M9 16h6"/>',
    git: '<circle cx="6" cy="5.5" r="2.5"/><circle cx="6" cy="18.5" r="2.5"/><circle cx="18" cy="8.5" r="2.5"/><path d="M6 8v8M18 11c0 3.5-6.5 2.5-11 5.7"/>',
    timon: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/><path d="M12 3v6.5M12 14.5V21M3.4 9.2l6.2 2M14.4 12.8l6.2 2M6.7 19.3l3.8-5.3M13.5 10l3.8-5.3"/>',
    capas: '<path d="M12 2.5l9.5 5-9.5 5-9.5-5z"/><path d="M2.5 12.5l9.5 5 9.5-5M2.5 17l9.5 5 9.5-5"/>',
    red: '<circle cx="18" cy="5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="M8.2 10.8l7.6-4.6M8.2 13.2l7.6 4.6"/>',
    respaldo: '<rect x="3" y="3.5" width="18" height="5" rx="1.5"/><path d="M5 8.5V20h14V8.5M10 13h4"/>',
    escala: '<path d="M4 20V13M9.5 20V8M15 20v-5M20.5 20V4M2 21h20"/>',
    pilar: '<path d="M3 8.5L12 3.5l9 5z"/><path d="M5.5 10.5v7M10 10.5v7M14 10.5v7M18.5 10.5v7M3 20.5h18"/>',
    engrane: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
    reintento: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4.5V11h-6.5"/>',
    lupa: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/>',
    hexagono: '<path d="M12 2.5l8.5 4.8v9.4L12 21.5l-8.5-4.8V7.3z"/><path d="M12 8l3.5 2v4L12 16l-3.5-2v-4z"/>',
  };
  const svgIcono = (n) => `<svg viewBox="0 0 24 24">${ICONOS[n] || ICONOS.cubo}</svg>`;
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const NS = 'http://www.w3.org/2000/svg';
  const $ = (id) => document.getElementById(id);
  const C = E.colores;
  const col = (k) => C[k] || k || '#9FC3F0';
  const rgba = (hex, a) => {
    const h = hex.replace('#', '');
    const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
  };
  const D = E.diagrama;
  const rects = {};

  // ── Zonas ────────────────────────────────────────────────────────────────────────────────────
  for (const g of D.grupos || []) {
    const el = document.createElement('div');
    el.className = `g ${g.estilo || 'banda'}${g.lado ? ' lado' : ''}`;
    Object.assign(el.style, { left: `${g.x}px`, top: `${g.y}px`, width: `${g.w}px`, height: `${g.h}px` });
    el.style.setProperty('--c', col(g.c));
    el.innerHTML = g.t ? `<div class="eti">${esc(g.t)}</div>` : '';
    $('cg').appendChild(el);
    rects[g.id] = { ...g, el, tipo: 'grupo' };
  }
  // ── Nodos ────────────────────────────────────────────────────────────────────────────────────
  for (const n of D.nodos || []) {
    const el = document.createElement('div');
    el.className = `n${n.mini ? ' mini' : ''}${n.objetivo ? ' objetivo' : ''}${n.centro ? ' centro' : ''}`
      + `${n.barra ? ' barra' : ''}${n.espera ? ' espera' : ''}${n.texto ? ' texto' : ''}${n.der ? ' der' : ''}`;
    Object.assign(el.style, { left: `${n.x}px`, top: `${n.y}px`, width: `${n.w}px`, height: `${n.h}px` });
    el.style.setProperty('--c', col(n.c));
    el.innerHTML = n.barra ? '' : `${n.icono === false ? '' : `<div class="ic">${svgIcono(n.icono)}</div>`}
      <div class="tx"><b>${esc(n.t)}</b>${n.s ? `<span>${esc(n.s)}</span>` : ''}</div>
      ${n.objetivo ? `<div class="tag">${esc(E.etiquetaObjetivo || 'propuesta')}</div>` : ''}`;
    $('cn').appendChild(el);
    rects[n.id] = { ...n, el, tipo: 'nodo' };
  }

  // ── Aristas ──────────────────────────────────────────────────────────────────────────────────
  // Ortogonales con esquinas redondeadas. Sin `via`, se elige el camino más corto: recta si los
  // nodos se traslapan en un eje, una L o una Z si no.
  const lado = (r, l, d = 0) => ({
    r: [r.x + r.w, r.y + r.h / 2 + d], l: [r.x, r.y + r.h / 2 + d],
    t: [r.x + r.w / 2 + d, r.y], b: [r.x + r.w / 2 + d, r.y + r.h],
  })[l];
  const horizontal = (l) => l === 'r' || l === 'l';
  function puntos(a, b, e) {
    if (e.via) {
      const v0 = e.via[0], v1 = e.via[e.via.length - 1];
      const la = e.lados?.[0] || (v0[0] > a.x + a.w ? 'r' : v0[0] < a.x ? 'l' : v0[1] > a.y + a.h ? 'b' : 't');
      const lb = e.lados?.[1] || (v1[0] > b.x + b.w ? 'r' : v1[0] < b.x ? 'l' : v1[1] > b.y + b.h ? 'b' : 't');
      const s = lado(a, la), f = lado(b, lb);
      // El primer y último tramo se alinean con el lado de salida y de llegada.
      const p0 = horizontal(la) ? [v0[0], s[1]] : [s[0], v0[1]];
      const p1 = horizontal(lb) ? [v1[0], f[1]] : [f[0], v1[1]];
      return [s, p0, ...e.via.slice(1, -1), p1, f].filter((p, i, xs) => i === 0 || p[0] !== xs[i - 1][0] || p[1] !== xs[i - 1][1]);
    }
    if (!e.lados) {
      const ox0 = Math.max(a.x, b.x), ox1 = Math.min(a.x + a.w, b.x + b.w);
      const oy0 = Math.max(a.y, b.y), oy1 = Math.min(a.y + a.h, b.y + b.h);
      if (ox1 - ox0 > 24) {
        const x = (ox0 + ox1) / 2 + (e.d || 0);
        return b.y > a.y ? [[x, a.y + a.h], [x, b.y]] : [[x, a.y], [x, b.y + b.h]];
      }
      if (oy1 - oy0 > 16) {
        const y = (oy0 + oy1) / 2 + (e.d || 0);
        return b.x > a.x ? [[a.x + a.w, y], [b.x, y]] : [[a.x, y], [b.x + b.w, y]];
      }
    }
    const [la, lb] = e.lados || (b.x >= a.x + a.w ? ['r', 'l'] : b.x + b.w <= a.x ? ['l', 'r'] : b.y >= a.y + a.h ? ['b', 't'] : ['t', 'b']);
    const s = lado(a, la, e.da || 0), f = lado(b, lb, e.db || 0);
    if (horizontal(la) && horizontal(lb)) { const mx = e.mx ?? (s[0] + f[0]) / 2; return [s, [mx, s[1]], [mx, f[1]], f]; }
    if (!horizontal(la) && !horizontal(lb)) { const my = e.my ?? (s[1] + f[1]) / 2; return [s, [s[0], my], [f[0], my], f]; }
    return horizontal(la) ? [s, [f[0], s[1]], f] : [s, [s[0], f[1]], f];
  }
  function trazo(pts, r = 14) {
    let d = `M${pts[0][0]} ${pts[0][1]}`;
    for (let i = 1; i < pts.length - 1; i++) {
      const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], [x2, y2] = pts[i + 1];
      const d1 = Math.hypot(x1 - x0, y1 - y0), d2 = Math.hypot(x2 - x1, y2 - y1);
      const rr = Math.min(r, d1 / 2, d2 / 2);
      d += ` L${x1 - ((x1 - x0) / d1) * rr} ${y1 - ((y1 - y0) / d1) * rr} Q${x1} ${y1} ${x1 + ((x2 - x1) / d2) * rr} ${y1 + ((y2 - y1) / d2) * rr}`;
    }
    const f = pts[pts.length - 1];
    return `${d} L${f[0]} ${f[1]}`;
  }
  const flecha = (p, q, tam = 11) => {
    const ang = Math.atan2(q[1] - p[1], q[0] - p[0]);
    const a = [q[0] - tam * Math.cos(ang - 0.45), q[1] - tam * Math.sin(ang - 0.45)];
    const b = [q[0] - tam * Math.cos(ang + 0.45), q[1] - tam * Math.sin(ang + 0.45)];
    return `M${q[0]} ${q[1]} L${a[0]} ${a[1]} L${b[0]} ${b[1]}Z`;
  };
  const ESTILO = E.aristas; // tipo → { color, ancho, guion }
  const aristas = {};
  for (const e of D.aristas || []) {
    const punto = (v) => (Array.isArray(v) ? { x: v[0], y: v[1], w: 0, h: 0, tipo: 'punto' } : rects[v]);
    const a = punto(e.de), b = punto(e.a);
    if (!a || !b) throw new Error(`arista ${e.id}: no existe ${!a ? e.de : e.a}`);
    const pts = puntos(a, b, e);
    // La línea termina antes de la punta de flecha, para que no la asome.
    const st = ESTILO[e.tipo || 'sync'];
    const color = col(e.c) || st.color;
    const g = document.createElementNS(NS, 'g');
    const linea = document.createElementNS(NS, 'path');
    linea.setAttribute('d', trazo(pts));
    linea.setAttribute('fill', 'none');
    linea.setAttribute('stroke', e.c ? col(e.c) : st.color);
    linea.setAttribute('stroke-width', st.ancho);
    linea.setAttribute('stroke-linecap', 'round');
    if (st.guion) linea.setAttribute('stroke-dasharray', st.guion);
    g.appendChild(linea);
    const puntas = [];
    const n = pts.length;
    if (e.flecha !== false) puntas.push(flecha(pts[n - 2], pts[n - 1]));
    if (e.doble) puntas.push(flecha(pts[1], pts[0]));
    for (const d of puntas) {
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('d', d);
      p.setAttribute('fill', e.c ? col(e.c) : st.color);
      g.appendChild(p);
    }
    $('sv').appendChild(g);
    const largo = linea.getTotalLength();
    let et = null;
    if (e.t) {
      et = document.createElement('div');
      et.className = 'el';
      const p = linea.getPointAtLength(largo * (e.pos ?? 0.5));
      Object.assign(et.style, { left: `${p.x + (e.ex || 0)}px`, top: `${p.y + (e.ey || 0)}px`, color: e.c ? col(e.c) : st.color });
      et.textContent = e.t;
      $('ce').appendChild(et);
    }
    // Paquetes: uno cada ~260 px, y cada uno tarda tantos segundos como paquetes hay. Así la
    // figura se repite exacta cada segundo y el bucle de 1 s no salta.
    const cuantos = Math.max(1, Math.min(4, Math.round(largo / 260)));
    const paquetes = [];
    for (let i = 0; i < cuantos; i++) {
      const c = document.createElementNS(NS, 'circle');
      c.setAttribute('r', st.paquete || 6);
      c.setAttribute('fill', st.relleno || '#fff');
      c.setAttribute('stroke', e.c ? col(e.c) : st.color);
      c.setAttribute('stroke-width', 3);
      c.setAttribute('opacity', 0);
      $('sp').appendChild(c); // sobre las etiquetas: un paquete no se esconde detrás del nombre del tópico
      paquetes.push(c);
    }
    aristas[e.id] = { ...e, g, linea, et, largo, paquetes, guion: st.guion, marcha: st.marcha };
  }

  // ── Estado por momento ───────────────────────────────────────────────────────────────────────
  // Visibles acumulados: lo que apareció se queda. Una arista aparece sola cuando ya se ven sus
  // dos extremos, salvo que diga `tarde: true` (entonces la trae un momento por su nombre).
  const todos = [...Object.keys(rects), ...Object.keys(aristas)];
  const visibles = [], focos = [], flujos = [];
  let vis = new Set(E.base === '*' ? todos : E.base || []);
  for (const m of E.momentos) {
    vis = new Set(vis);
    for (const id of m.ve === '*' ? todos : m.ve || []) vis.add(id);
    for (const id of m.flujo || []) vis.add(id);
    for (const id of m.oculta || []) vis.delete(id);
    for (const [id, a] of Object.entries(aristas)) if (!a.tarde && typeof a.de === 'string' && typeof a.a === 'string' && vis.has(a.de) && vis.has(a.a)) vis.add(id);
    visibles.push(vis);
    const f = new Set([...(m.foco || []), ...(m.flujo || [])]);
    if (!(m.foco || []).length && f.size && Array.isArray(m.ve)) for (const id of m.ve) f.add(id);
    for (const id of m.flujo || []) { const a = aristas[id]; if (a) { if (typeof a.de === 'string') f.add(a.de); if (typeof a.a === 'string') f.add(a.a); } }
    for (const [id, a] of Object.entries(aristas)) if (typeof a.de === 'string' && f.has(a.de) && f.has(a.a) && m.foco_aristas !== false) f.add(id);
    // Una zona enfocada enfoca lo que contiene.
    for (const id of [...f]) {
      const g = rects[id];
      if (g?.tipo !== 'grupo') continue;
      for (const [nid, r] of Object.entries(rects)) {
        if (r.tipo === 'nodo' && r.x >= g.x && r.y >= g.y && r.x + r.w <= g.x + g.w && r.y + r.h <= g.y + g.h) f.add(nid);
      }
    }
    focos.push(f);
    flujos.push(new Set(m.flujo || []));
  }
  // Una secuencia pide ver los mensajes anteriores; un mapa, apagarlos.
  const ATENUA = { nodo: 0.24, grupo: 0.4, arista: 0.14, ...(E.atenua || {}) };
  const opacidad = (id, j, tipo) => {
    if (j < 0 || !visibles[j].has(id)) return 0;
    return focos[j].size && !focos[j].has(id) ? ATENUA[tipo] : 1;
  };
  const brillo = (id, j) => (j >= 0 && focos[j].has(id) && (E.momentos[j].foco || []).includes(id) ? 1 : 0);
  const suave = (k) => (k < 0.5 ? 2 * k * k : 1 - (-2 * k + 2) ** 2 / 2);
  const mezcla = (a, b, k) => a + (b - a) * k;

  // ── Señales: número y texto que apuntan a un nodo ────────────────────────────────────────────
  const senales = E.momentos.map((m, j) => (m.marcas || []).map((s) => {
    // Se señala un nodo o una zona por su id, o un punto suelto [x, y] (un mensaje de una secuencia).
    const r = Array.isArray(s.en) ? { x: s.en[0], y: s.en[1], w: 0, h: 0 } : rects[s.en];
    if (!r) throw new Error(`marca del momento ${j + 1}: no existe ${s.en}`);
    const n = s.n ?? j + 1;
    if (!s.t) {
      const b = document.createElement('div');
      b.className = 'badge';
      b.textContent = n;
      Object.assign(b.style, { left: `${r.x + r.w - 18 + (s.dx || 0)}px`, top: `${r.y - 14 + (s.dy || 0)}px`, opacity: 0 });
      $('cm').appendChild(b);
      return { el: b, linea: null, dx: 0, dy: -8 };
    }
    const el = document.createElement('div');
    el.className = 'ma';
    el.innerHTML = `<b>${esc(n)}</b><span>${esc(s.t)}</span>`;
    el.style.opacity = 0;
    $('cm').appendChild(el);
    const w = el.offsetWidth, h = el.offsetHeight;
    const pos = s.pos || 'arriba', sep = s.sep ?? 34;
    const cx = r.x + r.w / 2 + (s.dx || 0), cy = r.y + r.h / 2 + (s.dy || 0);
    let x, y, de, a;
    if (pos === 'arriba') { x = cx - w / 2; y = r.y - sep - h; de = [r.x + r.w / 2 + (s.dx || 0), y + h]; a = [de[0], r.y - 4]; }
    else if (pos === 'abajo') { x = cx - w / 2; y = r.y + r.h + sep; de = [r.x + r.w / 2 + (s.dx || 0), y]; a = [de[0], r.y + r.h + 4]; }
    else if (pos === 'der') { x = r.x + r.w + sep; y = cy - h / 2; de = [x, r.y + r.h / 2 + (s.dy || 0)]; a = [r.x + r.w + 4, de[1]]; }
    else { x = r.x - sep - w; y = cy - h / 2; de = [x + w, r.y + r.h / 2 + (s.dy || 0)]; a = [r.x - 4, de[1]]; }
    x = Math.max(12, Math.min(1312 - w - 12, x));
    y = Math.max(12, Math.min(984 - h - 12, y));
    Object.assign(el.style, { left: `${x}px`, top: `${y}px` });
    const g = document.createElementNS(NS, 'g');
    g.setAttribute('opacity', 0);
    const l = document.createElementNS(NS, 'path');
    l.setAttribute('d', `M${de[0]} ${de[1]} L${a[0]} ${a[1]}`);
    l.setAttribute('stroke', '#fff');
    l.setAttribute('stroke-width', 2.5);
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', flecha(de, a, 10));
    p.setAttribute('fill', '#fff');
    g.append(l, p);
    $('sm').appendChild(g);
    const v = { arriba: [0, 10], abajo: [0, -10], der: [-10, 0], izq: [10, 0] }[pos];
    return { el, linea: g, dx: v[0], dy: v[1] };
  }));

  // ── Panel ────────────────────────────────────────────────────────────────────────────────────
  const P = E.panel;
  const leyenda = (E.leyenda || []).map((k) => {
    if (k === 'objetivo') return `<span><i class="obj"></i>${esc(E.etiquetaObjetivo === 'propuesta' ? 'Propuesta' : E.etiquetaObjetivo)}</span>`;
    const st = ESTILO[k];
    return `<span><svg viewBox="0 0 38 12"><path d="M2 6H36" stroke="${st.color}" stroke-width="3" ${st.guion ? `stroke-dasharray="${st.guion}"` : ''} stroke-linecap="round"/></svg>${esc(st.nombre)}</span>`;
  }).join('');
  const panel = (j) => `
    <div class="top"><div class="marca">${E.logo}Kredius</div><div class="seccion">${esc(P.seccion)}</div></div>
    <div class="chip" style="--c:${col(P.color)}"><i>${svgIcono(P.icono)}</i><div><b>${esc(P.capa)}</b><span>${esc(P.sub)}</span></div></div>
    ${P.estado ? `<div class="estado" style="--c:${col(P.estado.c)}">${esc(P.estado.t)}</div>` : ''}
    <div class="k">${esc(P.kicker)}</div>
    <div class="t">${esc(P.titulo)}</div>
    <ol>${E.momentos.map((m, i) => `<li class="${i < j ? 'hecho' : i === j ? 'actual' : ''}"><b>${i < j ? '✓' : i + 1}</b><span>${esc(m.texto)}</span></li>`).join('')}</ol>
    <div class="ley">${leyenda}</div>
    <div class="pie"><span>${esc(P.pie || 'Arquitectura de referencia')}</span><span>kredius.mx</span></div>`;

  window.desbordes = Object.entries(rects).filter(([, r]) => r.tipo === 'nodo')
    .flatMap(([id, r]) => [...r.el.querySelectorAll('b, span')].filter((x) => x.scrollWidth > x.clientWidth + 1)
      .map((x) => `${id}: «${x.textContent}»`));

  window.pinta = (j, k, t) => {
    const e = suave(k);
    $('panel').innerHTML = panel(j);
    for (const [id, r] of Object.entries(rects)) {
      const o = mezcla(opacidad(id, j - 1, r.tipo), opacidad(id, j, r.tipo), e);
      r.el.style.opacity = o;
      if (r.tipo === 'nodo' && !r.texto) { // el texto suelto se ilumina sin marco: un anillo lo volvería caja
        const b = mezcla(brillo(id, j - 1), brillo(id, j), e);
        const c = col(r.c);
        r.el.style.boxShadow = b > 0.01 ? `0 0 0 ${2 * b}px ${c}, 0 0 ${34 * b}px ${8 * b}px ${rgba(c, 0.45 * b)}` : 'none';
        r.el.style.transform = `scale(${1 + 0.025 * b})`;
      }
    }
    for (const [id, a] of Object.entries(aristas)) {
      const o = mezcla(opacidad(id, j - 1, 'arista'), opacidad(id, j, 'arista'), e);
      a.g.setAttribute('opacity', o);
      if (a.et) a.et.style.opacity = o;
      const antes = j > 0 && flujos[j - 1].has(id), ahora = flujos[j].has(id);
      const po = ahora && antes ? 1 : ahora ? e : antes ? 1 - e : 0;
      a.linea.setAttribute('stroke-dashoffset', a.marcha && (ahora || antes) ? -(t * a.marcha) : 0);
      a.paquetes.forEach((c, i) => {
        if (po < 0.01) { c.setAttribute('opacity', 0); return; }
        const n = a.paquetes.length;
        const f = (((t / n) + i / n) % 1 + 1) % 1;
        const p = a.linea.getPointAtLength(f * a.largo);
        // Entra y sale con suavidad en los extremos: un paquete que aparece de golpe parpadea.
        const borde = Math.min(1, f / 0.08, (1 - f) / 0.08);
        c.setAttribute('cx', p.x);
        c.setAttribute('cy', p.y);
        c.setAttribute('opacity', po * borde);
      });
    }
    senales.forEach((lista, i) => {
      const o = i === j ? e : i === j - 1 ? 1 - e : 0;
      for (const s of lista) {
        s.el.style.opacity = o;
        s.el.style.transform = `translate(${s.dx * (1 - (i === j ? e : 1))}px, ${s.dy * (1 - (i === j ? e : 1))}px)`;
        if (s.linea) s.linea.setAttribute('opacity', o);
      }
    });
  };
}

(async () => {
  const lote = JSON.parse(fs.readFileSync(entrada, 'utf8'));
  const propio = fs.readFileSync(__filename, 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  page.on('pageerror', (e) => { console.error(e); process.exitCode = 1; });
  for (const escena of lote.escenas) {
    const dir = path.join(salida, escena.id);
    const firma = crypto.createHash('sha1').update(propio).update(JSON.stringify(escena)).digest('hex');
    if (fs.existsSync(path.join(dir, '.firma')) && fs.readFileSync(path.join(dir, '.firma'), 'utf8') === firma) {
      console.error(`▸ escena ${escena.id}: sin cambios, se reutiliza`);
      continue;
    }
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    await page.setContent(PAGINA, { waitUntil: 'networkidle' }).catch(() => page.setContent(PAGINA));
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(construye, { ...escena, logo: LOGO });
    for (const d of await page.evaluate(() => window.desbordes)) console.error(`▸ ${escena.id}: no cabe ${d}`);
    const cuadros = [];
    const foto = (archivo) => page.screenshot({ path: path.join(dir, archivo), type: 'jpeg', quality: 93 });
    for (let j = 0; j < escena.momentos.length; j++) {
      const animado = (escena.momentos[j].flujo || []).length > 0 || (j > 0 && (escena.momentos[j - 1].flujo || []).length > 0);
      for (let f = 0; f < TRANSICION; f++) {
        await page.evaluate(([j, k, t]) => window.pinta(j, k, t), [j, f / (TRANSICION - 1), f / FPS]);
        await foto(`b${j}-t${String(f).padStart(2, '0')}.jpg`);
      }
      const bucle = (escena.momentos[j].flujo || []).length > 0 ? BUCLE : 1;
      for (let f = 0; f < bucle; f++) {
        await page.evaluate(([j, k, t]) => window.pinta(j, k, t), [j, 1, (TRANSICION + f) / FPS]);
        await foto(`b${j}-l${String(f).padStart(2, '0')}.jpg`);
      }
      cuadros.push({ transicion: TRANSICION, bucle, animado });
    }
    fs.writeFileSync(path.join(dir, 'cuadros.json'), JSON.stringify(cuadros));
    fs.writeFileSync(path.join(dir, '.firma'), firma);
    console.error(`▸ escena ${escena.id}: ${cuadros.reduce((s, c) => s + c.transicion + c.bucle, 0)} cuadros`);
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
