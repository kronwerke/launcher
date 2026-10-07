"use strict";
/* Kronwerke Console. No framework: a few helpers, one live stream, pages drawn from the
   launcher's JSON API. Everything a person can click, a key can do through the same API. */

// ---- helpers ------------------------------------------------------------------------------

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

/** replaceChildren without the nulls and falses that conditional parts leave behind. */
function put(el, ...kids) {
  el.replaceChildren(...kids.flat(Infinity).filter(k => k != null && k !== false));
}

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "style" && typeof v === "object") for (const [p, x] of Object.entries(v)) el.style.setProperty(p, x);
      else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
      else if (k === "html") el.innerHTML = v;
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, v);
    }
  }
  for (const k of kids.flat(Infinity)) {
    if (k == null || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}

const svg = (paths, cls) => {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("viewBox", "0 0 24 24");
  s.setAttribute("fill", "none");
  s.setAttribute("stroke", "currentColor");
  s.setAttribute("stroke-width", "1.8");
  s.setAttribute("stroke-linecap", "round");
  s.setAttribute("stroke-linejoin", "round");
  s.setAttribute("aria-hidden", "true");
  if (cls) s.setAttribute("class", cls);
  s.innerHTML = paths;
  return s;
};
const ICON = {
  crown: '<path d="M4 19h16M5 19l-1-11 5 4.5L12 5l3 7.5L20 8l-1 11" />',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />',
  file: '<path d="M7 3h7l5 5v13H7z" /><path d="M14 3v5h5" />',
  link: '<path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1" /><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" />',
  play: '<path d="M7 5l12 7-12 7z" />',
  stop: '<rect x="6" y="6" width="12" height="12" rx="1.5" />',
  restart: '<path d="M20 11a8 8 0 1 0-2.3 5.7" /><path d="M20 4v7h-7" />',
  key: '<circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M17 6l3 3" />',
  search: '<circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" />',
  upload: '<path d="M12 16V4M6 10l6-6 6 6M4 20h16" />',
  download: '<path d="M12 4v12M6 10l6 6 6-6M4 20h16" />',
};

const SERVER_COLORS = { main: "var(--gold)", mining: "var(--blue)" };
const EXTRA = ["var(--violet)", "var(--teal)"];
function colorOf(name) {
  if (SERVER_COLORS[name]) return SERVER_COLORS[name];
  const names = (S.overview?.servers || []).map(s => s.name).filter(n => !SERVER_COLORS[n]);
  return EXTRA[Math.max(0, names.indexOf(name)) % EXTRA.length];
}
function resolveColor(v) {
  const m = /var\((--[a-z]+)\)/.exec(v);
  return m ? getComputedStyle(document.documentElement).getPropertyValue(m[1]).trim() : v;
}

const STATE_DE = { running: "läuft", starting: "startet", stopping: "stoppt", stopped: "gestoppt", updating: "aktualisiert", crashed: "abgestürzt" };
const ROLE_DE = { owner: "Inhaber", admin: "Admin", mod: "Moderation", view: "Nur lesen" };
const SCOPE_DE = { read: "Lesen", players: "Spieler", command: "Befehle", power: "Starten und Stoppen", files: "Dateien", pack: "Pack", config: "Einstellungen" };

const fmt = {
  bytes(b) {
    if (b == null || b < 0) return "?";
    const u = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;
    while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
    return (b >= 100 || i === 0 ? Math.round(b) : b.toFixed(1)).toString().replace(".", ",") + " " + u[i];
  },
  num(n, d = 1) { return n == null || n < 0 ? "?" : Number(n).toFixed(d).replace(".", ","); },
  clock(t) { const d = new Date(t); return d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }); },
  date(t) { const d = new Date(t); return d.toLocaleString("de-DE", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }); },
  since(iso) {
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return "gerade eben";
    if (s < 3600) return Math.floor(s / 60) + " min";
    if (s < 86400) return Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
    return Math.floor(s / 86400) + " d " + Math.floor((s % 86400) / 3600) + " h";
  },
};

function health(mspt) {
  if (mspt == null || mspt < 0) return "";
  if (mspt >= 50) return "bad";
  if (mspt >= 40) return "warn";
  return "ok";
}

function storage(key, value) {
  try {
    if (value === undefined) return JSON.parse(localStorage.getItem("kw." + key) || "null");
    localStorage.setItem("kw." + key, JSON.stringify(value));
  } catch { return null; }
}

// ---- state and API -----------------------------------------------------------------------

const S = { session: null, overview: null, logs: {}, all: [], stream: null, metrics: {}, page: null, keys: "" };

async function api(method, path, body) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (S.session?.csrf) headers["X-Kw-Csrf"] = S.session.csrf;
  let res;
  try {
    res = await fetch("/api" + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new Error("Der Launcher antwortet nicht.");
  }
  let j;
  try { j = await res.json(); } catch { j = { ok: false, error: "HTTP " + res.status }; }
  if (res.status === 401 && S.session?.user && !path.startsWith("/auth")) {
    S.session.user = null;
    door("login", "Die Sitzung ist abgelaufen.");
  }
  if (!j.ok) throw new Error(j.error || "HTTP " + res.status);
  return j.data;
}

function can(scope) { return (S.session?.scopes || []).includes(scope); }

function toast(title, text, bad) {
  const t = h("div", { class: "toast" + (bad ? " bad" : "") }, h("b", null, title), text ? h("span", null, text) : null);
  $("#toasts").append(t);
  setTimeout(() => t.remove(), bad ? 9000 : 4500);
}

async function run(title, fn) {
  try {
    const r = await fn();
    toast(title, typeof r === "string" ? r : "");
    return r;
  } catch (e) {
    toast(title + " hat nicht geklappt", e.message, true);
    throw e;
  }
}

function confirmDialog({ title, text, ok = "Weiter", danger, input }) {
  return new Promise(resolve => {
    const field = input ? h("input", { type: "text", placeholder: input.placeholder || "", value: input.value || "", required: input.required || null }) : null;
    const d = h("dialog", null,
      h("form", { method: "dialog" },
        h("h2", null, title),
        text ? h("p", null, text) : null,
        input ? h("label", { class: "field" }, h("span", null, input.label), field) : null,
        h("div", { class: "actions" },
          h("button", { class: "btn quiet", value: "cancel", type: "submit", formnovalidate: true }, "Abbrechen"),
          h("button", { class: "btn " + (danger ? "danger" : "primary"), value: "ok", type: "submit" }, ok))));
    document.body.append(d);
    d.addEventListener("close", () => {
      resolve(d.returnValue === "ok" ? (field ? field.value : true) : null);
      d.remove();
    });
    d.showModal();
    (field || $("button[value=ok]", d)).focus();
  });
}

function infoDialog(title, text, secret) {
  const d = h("dialog", null,
    h("form", { method: "dialog" },
      h("h2", null, title),
      text ? h("p", null, text) : null,
      secret ? h("div", { class: "secret" }, secret) : null,
      h("div", { class: "actions" },
        secret ? h("button", { class: "btn", type: "button", onclick: () => { navigator.clipboard?.writeText(secret); toast("Kopiert"); } }, "Kopieren") : null,
        h("button", { class: "btn primary", value: "ok" }, "Fertig"))));
  document.body.append(d);
  d.addEventListener("close", () => d.remove());
  d.showModal();
}

// ---- passkeys ----------------------------------------------------------------------------

const b64u = {
  enc(buf) {
    const b = new Uint8Array(buf);
    let s = "";
    for (const x of b) s += String.fromCharCode(x);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  },
  dec(str) {
    const s = atob(str.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((str.length + 3) % 4));
    return Uint8Array.from(s, c => c.charCodeAt(0)).buffer;
  },
};

async function createPasskey(o) {
  if (!window.PublicKeyCredential) throw new Error("Dieser Browser kann keine Passkeys.");
  const opts = structuredClone(o);
  opts.challenge = b64u.dec(opts.challenge);
  opts.user.id = b64u.dec(opts.user.id);
  opts.excludeCredentials = (opts.excludeCredentials || []).map(c => ({ ...c, id: b64u.dec(c.id) }));
  const cred = await navigator.credentials.create({ publicKey: opts });
  const r = cred.response;
  if (!r.getPublicKey || !r.getPublicKey()) throw new Error("Dieser Browser gibt den Schlüssel nicht heraus. Bitte einen aktuellen Browser nehmen.");
  return {
    id: b64u.enc(cred.rawId),
    publicKey: b64u.enc(r.getPublicKey()),
    publicKeyAlgorithm: r.getPublicKeyAlgorithm(),
    authenticatorData: b64u.enc(r.getAuthenticatorData()),
    clientDataJSON: b64u.enc(r.clientDataJSON),
  };
}

async function getPasskey(o) {
  if (!window.PublicKeyCredential) throw new Error("Dieser Browser kann keine Passkeys.");
  const opts = structuredClone(o);
  opts.challenge = b64u.dec(opts.challenge);
  const cred = await navigator.credentials.get({ publicKey: opts });
  const r = cred.response;
  return {
    id: b64u.enc(cred.rawId),
    authenticatorData: b64u.enc(r.authenticatorData),
    clientDataJSON: b64u.enc(r.clientDataJSON),
    signature: b64u.enc(r.signature),
    userHandle: r.userHandle ? b64u.enc(r.userHandle) : null,
  };
}

function passkeyError(e) {
  if (e?.name === "NotAllowedError") return "Abgebrochen oder abgelaufen.";
  if (e?.name === "InvalidStateError") return "Dieser Passkey ist hier schon angelegt.";
  return e?.message || String(e);
}

function deviceLabel() {
  const ua = navigator.userAgent;
  const os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "Gerät";
  const br = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  return os + ", " + br;
}

// ---- the door: sign in, first passkey, invites -------------------------------------------

function crownMark() {
  return svg('<path d="M4 19h16M5 19l-1-11 5 4.5L12 5l3 7.5L20 8l-1 11" stroke="#e5b451" stroke-width="1.6"/>');
}

function door(kind, message) {
  stopStream();
  document.title = "Kronwerke Console";
  const app = $("#app");
  app.className = "";
  app.removeAttribute("aria-busy");
  const err = h("p", { class: "error", role: "alert" }, message && kind !== "login" ? message : "");
  const note = message && kind === "login" ? h("p", { class: "note" }, message) : null;
  let card;
  if (kind === "setup") {
    const code = h("input", { type: "text", autocomplete: "one-time-code", placeholder: "KW-XXXX-XXXX", required: true, spellcheck: "false" });
    const name = h("input", { type: "text", autocomplete: "nickname", placeholder: "Samuel", required: true, maxlength: 40 });
    const form = h("form", { onsubmit: async e => {
      e.preventDefault();
      err.textContent = "";
      try {
        const o = await api("POST", "/auth/register/options", { purpose: "setup", secret: code.value, name: name.value });
        const cred = await createPasskey(o.options);
        S.session = await api("POST", "/auth/register", { id: o.id, credential: cred, label: deviceLabel() });
        history.replaceState(null, "", "/");
        start();
      } catch (x) { err.textContent = passkeyError(x); }
    } },
      h("label", { class: "field" }, h("span", null, "Einrichtungscode aus der Tavuru-Konsole"), code),
      h("label", { class: "field" }, h("span", null, "Dein Name"), name),
      h("button", { class: "btn primary", type: "submit" }, "Passkey anlegen"), err);
    card = [h("h1", null, "Erster Passkey"), h("p", null, "Wer diesen Code hat, hat Zugriff auf den Server. Er gilt nur einmal und verschwindet, sobald dein Passkey angelegt ist."), form];
  } else if (kind === "invite") {
    const token = location.hash.slice(1);
    const name = h("input", { type: "text", autocomplete: "nickname", required: true, maxlength: 40 });
    const form = h("form", { onsubmit: async e => {
      e.preventDefault();
      err.textContent = "";
      try {
        const o = await api("POST", "/auth/register/options", { purpose: "invite", secret: token, name: name.value });
        const cred = await createPasskey(o.options);
        S.session = await api("POST", "/auth/register", { id: o.id, credential: cred, label: deviceLabel() });
        history.replaceState(null, "", "/");
        start();
      } catch (x) { err.textContent = passkeyError(x); }
    } },
      h("label", { class: "field" }, h("span", null, "Dein Name"), name),
      h("button", { class: "btn primary", type: "submit" }, "Passkey anlegen und beitreten"), err);
    card = [h("h1", null, "Einladung"), h("p", null, "Du wurdest in die Kronwerke Console eingeladen. Ein Passkey ersetzt das Passwort: dein Gerät bestätigt mit Fingerabdruck, Gesicht oder PIN."), form];
  } else {
    const form = h("form", { onsubmit: async e => {
      e.preventDefault();
      err.textContent = "";
      try {
        const o = await api("POST", "/auth/options", {});
        const cred = await getPasskey(o.options);
        S.session = await api("POST", "/auth/login", { id: o.id, credential: cred });
        start();
      } catch (x) { err.textContent = passkeyError(x); }
    } },
      h("button", { class: "btn primary", type: "submit", autofocus: true }, svg(ICON.key), "Mit Passkey anmelden"), err);
    card = [h("h1", null, "Kronwerke Console"), h("p", null, "Server, Pack und Spieler an einem Ort."), form, note,
      S.session?.setup ? h("p", { class: "note" }, "Noch niemand eingerichtet? ", h("a", { href: "/setup" }, "Ersten Passkey anlegen")) : null];
  }
  put(app, h("div", { class: "door" }, h("div", { class: "door-card" }, crownMark(), card)));
  $("input, button", app)?.focus();
}

// ---- the shell ---------------------------------------------------------------------------

const PAGES = [
  { path: "/", name: "Übersicht", key: "o", draw: pageOverview },
  { path: "/konsole", name: "Konsole", key: "k", draw: pageConsole },
  { path: "/spieler", name: "Spieler", key: "s", draw: pagePlayers },
  { path: "/season", name: "Season", key: "e", draw: pageSeason },
  { path: "/pack", name: "Pack und Mods", key: "p", draw: pagePack },
  { path: "/dateien", name: "Dateien", key: "d", draw: pageFiles, scope: "files" },
  { path: "/ressourcen", name: "Ressourcen", key: "r", draw: pageResources },
  { path: "/verlauf", name: "Verlauf", key: "v", draw: pageHistory },
  { path: "/zugang", name: "Zugang", key: "z", draw: pageAccess },
];

function pageFor(path) {
  if (path === "/" || path === "") return PAGES[0];
  return PAGES.find(p => p.path !== "/" && (path === p.path || path.startsWith(p.path + "/"))) || PAGES[0];
}

function shell() {
  const app = $("#app");
  app.className = "";
  app.removeAttribute("aria-busy");
  const nav = h("nav", { class: "pages", "aria-label": "Bereiche" },
    PAGES.filter(p => !p.scope || can(p.scope)).map(p => h("a", { href: p.path, "data-link": true }, p.name, h("kbd", { title: "g dann " + p.key }, "g " + p.key))));
  const rail = h("aside", { class: "rail" },
    h("a", { class: "mark", href: "/", "data-link": true }, crownMark(), h("b", null, "Kronwerke ", h("span", null, "Console"))),
    h("div", { class: "fleet", id: "fleet" }),
    nav,
    h("div", { class: "rail-foot" },
      h("button", { class: "palette-hint", onclick: openPalette }, "Suchen und Befehle", h("kbd", null, navigator.platform.includes("Mac") ? "Cmd K" : "Strg K")),
      h("span", { id: "who" }, S.session.user.name + ", " + (ROLE_DE[S.session.user.role] || S.session.user.role)),
      h("span", { id: "ver", class: "dim" }, "Launcher " + (S.session.launcher || ""))));
  const main = h("main", { id: "main", tabindex: "-1" });
  put(app, h("div", { class: "shell" }, rail, main));
  drawFleet();
}

function drawFleet() {
  const el = $("#fleet");
  if (!el || !S.overview) return;
  put(el, ...S.overview.servers.map(s => {
    const m = s.last?.mspt;
    return h("a", { class: "srv", href: "/konsole/" + s.name, "data-link": true, "data-state": s.state, "data-health": s.state === "running" ? health(m) : "", style: { "--c": colorOf(s.name) } },
      h("i"), h("span", null, h("b", null, s.name), h("small", null, STATE_DE[s.state] || s.state)),
      h("em", { title: "Millisekunden pro Tick" }, s.state === "running" && m >= 0 ? fmt.num(m) + " ms" : ""));
  }));
}

function go(path, replace) {
  if (replace) history.replaceState(null, "", path);
  else if (location.pathname !== path) history.pushState(null, "", path);
  route();
}

let cleanup = [];
function route() {
  for (const c of cleanup) try { c(); } catch {}
  cleanup = [];
  const page = pageFor(location.pathname);
  S.page = page;
  $$("nav.pages a").forEach(a => a.toggleAttribute("aria-current", false));
  const link = $$("nav.pages a").find(a => a.getAttribute("href") === page.path);
  if (link) link.setAttribute("aria-current", "page");
  document.title = page.name + ", Kronwerke Console";
  const main = $("#main");
  put(main);
  page.draw(main, location.pathname.slice(page.path.length).replace(/^\//, ""));
}

document.addEventListener("click", e => {
  const a = e.target.closest("a[data-link]");
  if (!a || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
  e.preventDefault();
  go(a.getAttribute("href"));
});
window.addEventListener("popstate", () => { if (S.session?.user) route(); });

function header(title, text, ...actions) {
  return h("div", { class: "head" }, h("div", null, h("h1", null, title), text ? h("p", null, text) : null), h("div", { class: "actions" }, actions));
}

// ---- live ---------------------------------------------------------------------------------

const listeners = { line: new Set(), event: new Set(), overview: new Set() };
function on(kind, fn) { listeners[kind].add(fn); cleanup.push(() => listeners[kind].delete(fn)); }

function startStream() {
  stopStream();
  const es = new EventSource("/api/stream?servers=*");
  S.stream = es;
  es.addEventListener("line", e => {
    const d = JSON.parse(e.data);
    const buf = (S.logs[d.server] ||= []);
    buf.push(d.text);
    if (buf.length > 6000) buf.splice(0, buf.length - 5000);
    S.all.push(d);
    if (S.all.length > 6000) S.all.splice(0, S.all.length - 5000);
    for (const f of listeners.line) f(d);
  });
  es.addEventListener("event", e => {
    const d = JSON.parse(e.data);
    if (S.overview) {
      S.overview.events.push(d);
      if (S.overview.events.length > 200) S.overview.events.shift();
    }
    for (const f of listeners.event) f(d);
    if (d.kind === "state") refreshOverview();
  });
  es.addEventListener("overview", e => {
    S.overview = JSON.parse(e.data);
    for (const s of S.overview.servers) if (s.last) pushMetric(s.name, s.last);
    drawFleet();
    for (const f of listeners.overview) f(S.overview);
  });
  es.onerror = () => {
    // EventSource reconnects by itself; a 401 means the session is gone
    setTimeout(async () => {
      if (es.readyState === EventSource.CLOSED) {
        try { S.session = await api("GET", "/session"); } catch {}
        if (S.session?.user) startStream(); else door("login", "Die Sitzung ist abgelaufen.");
      }
    }, 3000);
  };
}

function stopStream() {
  if (S.stream) S.stream.close();
  S.stream = null;
}

let refreshing = null;
function refreshOverview() {
  if (refreshing) return refreshing;
  refreshing = api("GET", "/overview").then(o => {
    S.overview = o;
    drawFleet();
    for (const f of listeners.overview) f(o);
    return o;
  }).finally(() => { refreshing = null; });
  return refreshing;
}

function pushMetric(name, sample) {
  const arr = S.metrics[name];
  if (!arr) return;
  if (!arr.length || arr[arr.length - 1].t < sample.t) arr.push(sample);
  if (arr.length > 400) arr.shift();
}

async function metricsOf(name) {
  if (!S.metrics[name]) S.metrics[name] = await api("GET", "/servers/" + name + "/metrics");
  return S.metrics[name];
}

// ---- the tick trace ---------------------------------------------------------------------------

function drawTrace(canvas, samples, color) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, hgt = canvas.clientHeight;
  if (!w || !hgt) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(hgt * dpr);
  const g = canvas.getContext("2d");
  g.scale(dpr, dpr);
  g.clearRect(0, 0, w, hgt);
  const now = Date.now() / 1000;
  const first = samples.find(s => s.mspt >= 0)?.t ?? now;
  const span = Math.min(3600, Math.max(120, now - first));
  const pts = samples.filter(s => s.mspt >= 0 && s.t > now - span);
  const max = Math.max(60, ...pts.map(s => s.mspt * 1.15));
  const y = v => hgt - 6 - (v / max) * (hgt - 14);
  const x = t => ((t - (now - span)) / span) * w;
  const c = resolveColor(color);
  // the limit: above 50 ms the server falls behind
  g.strokeStyle = "rgba(240, 101, 121, 0.45)";
  g.setLineDash([3, 5]);
  g.lineWidth = 1;
  g.beginPath(); g.moveTo(0, y(50)); g.lineTo(w, y(50)); g.stroke();
  g.setLineDash([]);
  g.fillStyle = "rgba(240, 101, 121, 0.7)";
  g.font = "500 11px Schibsted Grotesk, system-ui";
  g.fillText("50 ms", 6, y(50) - 5);
  g.fillStyle = "rgba(123, 116, 111, 0.9)";
  const label = span >= 3540 ? "letzte Stunde" : "letzte " + Math.round(span / 60) + " min";
  g.fillText(label, w - g.measureText(label).width - 8, hgt - 8);
  if (pts.length < 2) {
    g.fillStyle = "rgba(170, 163, 155, 0.7)";
    g.fillText(pts.length ? "Die Kurve wächst alle zehn Sekunden." : "Noch keine Messung.", 6, hgt - 12);
    return;
  }
  const path = new Path2D();
  pts.forEach((s, i) => (i ? path.lineTo(x(s.t), y(s.mspt)) : path.moveTo(x(s.t), y(s.mspt))));
  const area = new Path2D(path);
  area.lineTo(x(pts[pts.length - 1].t), hgt);
  area.lineTo(x(pts[0].t), hgt);
  area.closePath();
  const grad = g.createLinearGradient(0, 0, 0, hgt);
  grad.addColorStop(0, c + "38");
  grad.addColorStop(1, c + "00");
  g.fillStyle = grad;
  g.fill(area);
  g.strokeStyle = c;
  g.lineWidth = 1.6;
  g.stroke(path);
  const last = pts[pts.length - 1];
  g.fillStyle = c;
  g.beginPath(); g.arc(x(last.t), y(last.mspt), 3, 0, Math.PI * 2); g.fill();
}

function spark(samples, key, color) {
  const vals = samples.slice(-60).map(s => s[key]).filter(v => v >= 0);
  if (vals.length < 2) return h("span");
  const max = Math.max(...vals, 1), w = 64, hh = 16;
  const pts = vals.map((v, i) => (i / (vals.length - 1)) * w + "," + (hh - 1 - (v / max) * (hh - 2))).join(" ");
  const s = svg('<polyline points="' + pts + '" stroke="' + color + '" stroke-width="1.3" fill="none" />');
  s.setAttribute("viewBox", "0 0 " + w + " " + hh);
  s.setAttribute("width", w);
  s.setAttribute("height", hh);
  s.style.verticalAlign = "-3px";
  return s;
}

function heads(names, max = 8) {
  return h("span", { class: "heads" },
    names.slice(0, max).map(n => h("img", { src: "https://mc-heads.net/avatar/" + encodeURIComponent(n) + "/32", alt: n, title: n, loading: "lazy" })),
    names.length > max ? h("span", null, "+" + (names.length - max)) : null);
}

// ---- overview ---------------------------------------------------------------------------------

function powerButtons(s, small) {
  if (!can("power")) return [];
  const cls = "btn" + (small ? " small" : "");
  const out = [];
  if (s.state === "stopped" || s.state === "crashed") {
    out.push(h("button", { class: cls, onclick: () => power(s.name, "start") }, svg(ICON.play), "Starten"));
  } else {
    out.push(h("button", { class: cls, onclick: () => power(s.name, "restart") }, svg(ICON.restart), "Neustart"));
    out.push(h("button", { class: cls + " quiet", onclick: () => power(s.name, "stop") }, svg(ICON.stop), "Stoppen"));
  }
  return out;
}

async function power(name, action) {
  const s = S.overview.servers.find(x => x.name === name);
  const n = s?.players?.length || 0;
  const words = { start: ["Starten", "startet"], restart: ["Neustart", "startet neu"], stop: ["Stoppen", "stoppt"], kill: ["Beenden erzwingen", "wird hart beendet"] };
  if (action !== "start") {
    const ok = await confirmDialog({
      title: words[action][0] + ": " + name + "?",
      text: (n ? n + (n === 1 ? " Spieler ist" : " Spieler sind") + " gerade drauf. " : "Niemand ist drauf. ") + (action === "kill" ? "Ohne Speichern, nur wenn er hängt." : "Die Welt wird vorher gespeichert."),
      ok: words[action][0], danger: action !== "restart",
    });
    if (!ok) return;
  }
  await run(name + " " + words[action][1], () => api("POST", "/servers/" + name + "/power", { action }));
  refreshOverview();
}

function pageOverview(main) {
  const o = S.overview;
  const cards = h("div", { class: "stack", id: "pulses" });
  const side = h("div", { class: "stack" });
  main.append(header("Übersicht", null,
    can("pack") ? h("a", { class: "btn", href: "/pack", "data-link": true }, "Pack " + (o.pack.version || "?")) : null),
    h("div", { class: "grid-2" }, cards, side));

  const drawCards = () => {
    put(cards, ...S.overview.servers.map(s => {
      const m = s.last?.mspt ?? -1;
      const canvas = h("canvas", { class: "trace", "aria-label": "Tickzeit der letzten Stunde" });
      const card = h("section", { class: "panel pulse", style: { "--c": colorOf(s.name) }, "data-server": s.name },
        h("div", { class: "pulse-top" },
          h("div", null,
            h("div", { class: "pulse-name" },
              h("h2", null, h("a", { href: "/konsole/" + s.name, "data-link": true }, s.name)),
              h("span", { class: "state", "data-s": s.state }, (STATE_DE[s.state] || s.state) + " seit " + fmt.since(s.since))),
            h("div", { class: "pulse-meta" }, [s.detail, s.port ? "Port " + s.port : null, s.role && s.role !== s.name ? s.role : null].filter(Boolean).join(", "))),
          h("div", { class: "mspt", "data-health": s.state === "running" ? health(m) : "" },
            h("b", null, s.state === "running" && m >= 0 ? fmt.num(m) : "?", h("small", null, "ms")),
            h("span", null, s.state === "running" && s.last?.tps >= 0 ? fmt.num(s.last.tps) + " TPS" : "pro Tick"))),
        canvas,
        h("div", { class: "pulse-foot" },
          h("span", null, "CPU ", h("b", null, s.last?.cpu >= 0 ? fmt.num(s.last.cpu / 100) + " Kerne" : "?"), " ", spark(S.metrics[s.name] || [], "cpu", resolveColor(colorOf(s.name)))),
          h("span", null, "RAM ", h("b", null, fmt.bytes(s.last?.rss)), " von ", s.memory || "?"),
          h("span", null, h("b", null, s.players.length), s.players.length === 1 ? " Spieler" : " Spieler"),
          s.players.length ? heads(s.players) : null,
          h("span", { class: "actions nowrap end" }, powerButtons(s, true))));
      metricsOf(s.name).then(ms => drawTrace(canvas, ms, colorOf(s.name))).catch(() => {});
      return card;
    }));
  };

  const drawSide = () => {
    const c = S.overview.container;
    const cpu = c.cpu >= 0 ? c.cpu / 100 : -1;
    const memPct = c.memoryMax > 0 ? c.memory / c.memoryMax : 0;
    const diskPct = c.diskMax > 0 ? c.disk / c.diskMax : 0;
    const cpuPct = c.cpuLimit > 0 && cpu >= 0 ? cpu / c.cpuLimit : 0;
    const bar = (label, value, pct) => h("div", { class: "bar" },
      h("div", { class: "bar-top" }, h("span", null, label), h("b", null, value)),
      h("div", { class: "meter" }, h("span", { style: { width: Math.min(100, pct * 100).toFixed(1) + "%" }, "data-health": pct > 0.9 ? "bad" : pct > 0.75 ? "warn" : "" })));
    put(side, 
      h("section", { class: "panel" },
        h("header", null, h("h2", null, "Container"), h("p", null, "Launcher " + S.overview.launcher.version)),
        h("div", { class: "body bars" },
          bar("CPU", (cpu >= 0 ? fmt.num(cpu) : "?") + " von " + fmt.num(c.cpuLimit, 0) + " Kernen", cpuPct),
          bar("Arbeitsspeicher", fmt.bytes(c.memory) + " von " + fmt.bytes(c.memoryMax), memPct),
          bar("Festplatte", fmt.bytes(c.disk) + " von " + fmt.bytes(c.diskMax), diskPct))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, "Zeitleiste"), h("a", { class: "btn small quiet", href: "/verlauf", "data-link": true }, "Alles")),
        timeline(S.overview.events.slice(-40).reverse(), "tl")));
  };

  drawCards();
  drawSide();
  on("overview", () => { drawCards(); drawSide(); });
  const onResize = () => $$("#pulses canvas").forEach((cv, i) => {
    const s = S.overview.servers[i];
    if (s) drawTrace(cv, S.metrics[s.name] || [], colorOf(s.name));
  });
  window.addEventListener("resize", onResize);
  cleanup.push(() => window.removeEventListener("resize", onResize));
}

function eventText(e) {
  let t = e.text || "";
  if (e.kind === "state") {
    const [word, ...rest] = t.split(": ");
    t = (STATE_DE[word] || word) + (rest.length ? ": " + rest.join(": ") : "");
  }
  if (e.kind === "action") {
    const words = { "command": "Befehl", "start": "Start", "stop": "Stopp", "restart": "Neustart", "kill": "hart beendet",
      "config": "Einstellung", "write": "Datei geschrieben", "delete": "Datei gelöscht", "pack update": "Pack-Update",
      "launcher reload": "Launcher neu geladen", "launcher update": "Launcher-Update", "invite": "Einladung",
      "drop invite": "Einladung zurückgezogen", "new key": "neuer Schlüssel", "drop key": "Schlüssel widerrufen",
      "remove person": "Person entfernt", "role": "Rolle", "drop passkey": "Passkey entfernt" };
    const m = /^(.*?): (.*)$/.exec(t);
    if (m) {
      const key = Object.keys(words).sort((a, b) => b.length - a.length).find(k => m[2] === k || m[2].startsWith(k + " "));
      if (key) t = m[1] + ": " + words[key] + m[2].slice(key.length);
    }
  }
  return t.replace(/ signed in$/, " hat sich angemeldet")
    .replace(/^pack update: stopping (\d+) servers$/, "Pack-Update: $1 Server stoppen")
    .replace(/^pack (\S+) to (\S+)$/, "Pack $1 auf $2")
    .replace(/^console on port (\d+)(, Cloudflare only)?$/, (m, p, cf) => "Console auf Port " + p + (cf ? ", nur über Cloudflare" : ""))
    .replace(/^Kronwerke launcher (\S+), java (\S+), servers (.+)$/, "Launcher $1 gestartet (Java $2), Server: $3")
    .replace(/^Handing the servers to the next launcher$/, "Launcher wird neu geladen, die Server laufen weiter");
}

function timeline(events, id) {
  if (!events.length) return h("p", { class: "empty" }, "Noch nichts passiert.");
  return h("ul", { class: "timeline", id }, events.map(e => h("li", { "data-kind": e.kind, "data-bad": /crash|fail|abgest/i.test(e.text) || null },
    h("time", { datetime: e.t }, fmt.clock(e.t)),
    h("span", null, e.server ? h("span", { class: "who", style: { "--c": colorOf(e.server) } }, e.server) : null, h("span", { class: "what" }, eventText(e))))));
}

// ---- console ------------------------------------------------------------------------------------

const COMMANDS = ["list", "say ", "msg ", "kick ", "tp ", "gamemode spectator ", "gamemode survival ", "time set day", "weather clear",
  "neoforge tps", "neoforge entity list", "spark tps", "spark health", "spark profiler start", "spark profiler stop", "save-all",
  "whitelist list", "op ", "deop ", "kw admin list", "kw admin season json", "kw admin goals json", "kw admin obelisk info",
  "kw admin season start", "kw admin season pause", "kw admin active", "kw admin goal reload", "forceload query", "chunky progress"];

function lineClass(t) {
  if (t.startsWith("[Kronwerke]")) return "kw";
  if (/\/(ERROR|FATAL)\]|^\s+at |Exception|^Caused by/.test(t)) return "error";
  if (/\/WARN\]/.test(t)) return "warn";
  return "";
}

function pageConsole(main, rest) {
  const servers = S.overview.servers.map(s => s.name);
  let which = rest && (servers.includes(rest) || rest === "alle") ? rest : servers[0];
  let filter = "", onlyErrors = false, stick = true;
  const log = h("div", { class: "log", role: "log", "aria-live": "off", tabindex: "0" });
  const newer = h("button", { class: "btn small newer", hidden: true, onclick: () => { log.scrollTop = log.scrollHeight; } }, "Neue Zeilen");
  const input = h("input", { type: "text", placeholder: "Befehl, ohne Schrägstrich. Pfeiltasten für den Verlauf, Tab ergänzt.", autocomplete: "off", spellcheck: "false", "aria-label": "Befehl" });
  const suggest = h("ul", { class: "suggest", hidden: true, role: "listbox" });
  const seg = h("div", { class: "seg", role: "group", "aria-label": "Server" });
  const search = h("input", { type: "search", placeholder: "Filtern", "aria-label": "Zeilen filtern" });
  const errBox = h("input", { type: "checkbox", class: "switch" });

  const row = (text, server, cls) => {
    const c = cls ?? lineClass(text);
    const d = h("div", c ? { class: c } : null);
    if (which === "alle" && server) d.append(h("span", { class: "tag", style: { "--c": colorOf(server) } }, server));
    d.append(text);
    return d;
  };
  const visible = t => (!onlyErrors || /error|warn/.test(lineClass(t))) && (!filter || t.toLowerCase().includes(filter));

  const fill = async () => {
    put(log, h("div", { class: "dim" }, "Lade..."));
    let rows = [];
    if (which === "alle") {
      for (const n of servers) {
        if (!S.logs[n]) S.logs[n] = await api("GET", "/servers/" + n + "/console?n=2000");
      }
      rows = S.all.length ? S.all.map(d => [d.text, d.server]) : servers.flatMap(n => S.logs[n].slice(-300).map(t => [t, n]));
    } else {
      if (!S.logs[which] || S.logs[which].length < 50) S.logs[which] = await api("GET", "/servers/" + which + "/console?n=5000");
      rows = S.logs[which].map(t => [t, which]);
    }
    const frag = document.createDocumentFragment();
    rows.filter(([t]) => visible(t)).slice(-3000).forEach(([t, n]) => frag.append(row(t, n)));
    put(log, frag);
    log.scrollTop = log.scrollHeight;
    stick = true;
  };

  const drawSeg = () => put(seg, ...[...servers, "alle"].map(n => h("button", {
    type: "button", "aria-pressed": String(n === which), style: n === "alle" ? null : { "--c": colorOf(n) },
    onclick: () => { which = n; history.replaceState(null, "", "/konsole/" + n); drawSeg(); fill(); input.focus(); },
  }, n === "alle" ? null : h("span", { class: "dot" }), n === "alle" ? "Alle" : n)));

  log.addEventListener("scroll", () => {
    stick = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    if (stick) newer.hidden = true;
  });
  on("line", d => {
    if (which !== "alle" && d.server !== which) return;
    if (!visible(d.text)) return;
    log.append(row(d.text, d.server));
    while (log.childElementCount > 4000) log.firstElementChild.remove();
    if (stick) log.scrollTop = log.scrollHeight;
    else newer.hidden = false;
  });

  search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); fill(); });
  errBox.addEventListener("change", () => { onlyErrors = errBox.checked; fill(); });

  // history and completion
  let hist = storage("history") || [], at = hist.length, sel = -1, options = [];
  const players = () => S.overview.servers.flatMap(s => s.players);
  const complete = () => {
    const v = input.value;
    const words = v.split(" ");
    if (words.length > 1 && /^(kick|tp|msg|tell|op|deop|w)$/.test(words[0])) {
      const p = words[words.length - 1].toLowerCase();
      options = players().filter(n => n.toLowerCase().startsWith(p)).map(n => [...words.slice(0, -1), n].join(" ") + " ");
    } else {
      options = v ? COMMANDS.filter(c => c.startsWith(v) && c !== v) : [];
    }
    sel = options.length ? 0 : -1;
    suggest.hidden = !options.length;
    put(suggest, ...options.slice(0, 12).map((o, i) => h("li", { role: "option", "aria-selected": String(i === sel), onmousedown: e => { e.preventDefault(); input.value = o; complete(); } }, o)));
  };
  input.addEventListener("input", complete);
  input.addEventListener("blur", () => { suggest.hidden = true; });
  input.addEventListener("keydown", async e => {
    if (e.key === "Tab" && options.length) {
      e.preventDefault();
      input.value = options[Math.max(0, sel)];
      complete();
    } else if (e.key === "ArrowUp" && suggest.hidden) {
      e.preventDefault();
      if (at > 0) input.value = hist[--at];
    } else if (e.key === "ArrowDown" && suggest.hidden) {
      e.preventDefault();
      at = Math.min(hist.length, at + 1);
      input.value = hist[at] || "";
    } else if ((e.key === "ArrowDown" || e.key === "ArrowUp") && options.length) {
      e.preventDefault();
      sel = (sel + (e.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
      $$("li", suggest).forEach((li, i) => li.setAttribute("aria-selected", String(i === sel)));
    } else if (e.key === "Escape") {
      suggest.hidden = true;
    } else if (e.key === "Enter") {
      e.preventDefault();
      const cmd = input.value.trim().replace(/^\//, "");
      if (!cmd) return;
      const target = which === "alle" ? servers[0] : which;
      hist = [...hist.filter(x => x !== cmd), cmd].slice(-100);
      storage("history", hist);
      at = hist.length;
      input.value = "";
      suggest.hidden = true;
      log.append(row(cmd, target, "cmd"));
      log.scrollTop = log.scrollHeight;
      try {
        const answer = await api("POST", "/servers/" + target + "/command", { cmd });
        if (answer && answer.trim()) log.append(row(answer.trim(), target, "answer"));
      } catch (x) {
        log.append(row(x.message, target, "error"));
      }
      log.scrollTop = log.scrollHeight;
    }
  });

  drawSeg();
  const panel = h("section", { class: "panel console" },
    h("div", { class: "console-bar" }, seg, search, h("label", { class: "check" }, errBox, "Nur Warnungen und Fehler"),
      h("span", { style: { flex: "1" } }),
      ...(which !== "alle" ? powerButtons(S.overview.servers.find(s => s.name === which) || {}, true) : [])),
    h("div", { style: { position: "relative", minHeight: "0", display: "grid" } }, log, newer),
    can("command") || can("players") ? h("div", { class: "prompt" }, suggest, h("label", null, ">"), input) : h("div", { class: "prompt dim" }, "Nur lesen."));
  main.append(panel);
  fill();
  input.focus();
}

// ---- players ------------------------------------------------------------------------------------

function pagePlayers(main) {
  const body = h("div");
  main.append(header("Spieler", "Wer gerade auf welchem Server ist. Die Liste erneuert sich alle zehn Sekunden."), body);
  const draw = () => {
    const rows = S.overview.servers.flatMap(s => s.players.map(p => ({ name: p, server: s.name })));
    if (!rows.length) {
      put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, "Gerade ist niemand online.")));
      return;
    }
    put(body, h("section", { class: "panel" }, h("table", null,
      h("thead", null, h("tr", null, h("th", null, "Spieler"), h("th", null, "Server"), h("th", { class: "right" }, ""))),
      h("tbody", null, rows.map(r => h("tr", null,
        h("td", null, h("span", { class: "player" }, h("img", { src: "https://mc-heads.net/avatar/" + encodeURIComponent(r.name) + "/48", alt: "" }), r.name)),
        h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(r.server) } }, r.server)),
        h("td", { class: "right" }, h("div", { class: "actions", style: { justifyContent: "flex-end" } },
          can("players") ? h("button", { class: "btn small", onclick: () => message(r) }, "Nachricht") : null,
          can("players") ? h("button", { class: "btn small danger", onclick: () => kick(r) }, "Kicken") : null))))))));
  };
  draw();
  on("overview", draw);
}

async function message(r) {
  const text = await confirmDialog({ title: "Nachricht an " + r.name, ok: "Senden", input: { label: "Text", required: true } });
  if (!text) return;
  await run("Nachricht gesendet", () => api("POST", "/servers/" + r.server + "/command", { cmd: "tell " + r.name + " " + text }));
}

async function kick(r) {
  const reason = await confirmDialog({ title: r.name + " kicken?", text: "Der Grund steht auf seinem Bildschirm.", ok: "Kicken", danger: true, input: { label: "Grund", value: "Kurze Wartung, gleich wieder da." } });
  if (reason == null) return;
  await run(r.name + " gekickt", () => api("POST", "/servers/" + r.server + "/command", { cmd: "kick " + r.name + " " + reason }));
}

// ---- season --------------------------------------------------------------------------------------

function pageSeason(main) {
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, "Lade..."));
  const acts = h("span", { class: "actions" });
  main.append(header("Season", "Was Kronwerke Core über die Season und die Ziele des Obelisken sagt.", acts), body);
  const load = async () => {
    try {
      const d = await api("GET", "/season");
      if (!d.season) {
        put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, d.server + " läuft nicht, die Season ist gerade nicht lesbar.")));
        return;
      }
      const s = d.season;
      const goals = Array.isArray(d.goals) ? d.goals : [];
      if (can("command")) {
        put(acts, s.running
          ? h("button", { class: "btn quiet", onclick: () => core("kw admin season pause", "Season pausieren?", "Zurück in die Vorbereitung: Abgaben zählen nicht mehr, bis die Season wieder läuft.") }, "Pausieren")
          : h("button", { class: "btn primary", onclick: () => core("kw admin season start", "Season starten?", "Ab jetzt zählen Abgaben und Fortschritt.") }, "Season starten"));
      }
      put(body, 
        h("section", { class: "panel" }, h("div", { class: "body", style: { display: "flex", flexWrap: "wrap", gap: "1rem 2.5rem" } },
          h("div", null, h("div", { class: "muted" }, "Season"), h("b", { style: { fontSize: "1.25rem" } }, s.running ? "läuft" : "in Vorbereitung")),
          h("div", null, h("div", { class: "muted" }, "Nummer"), h("b", { style: { fontSize: "1.25rem" } }, s.number)),
          h("div", null, h("div", { class: "muted" }, "Obelisk"), h("b", { style: { fontSize: "1.25rem" } }, "Stufe " + s.tier + (s.slumbering ? ", schlummert" : ""))),
          s.startedAt ? h("div", null, h("div", { class: "muted" }, "Gestartet"), h("b", { style: { fontSize: "1.25rem" } }, fmt.date(s.startedAt))) : null)),
        h("div", { class: "stages" }, goals.map(g => h("section", { class: "panel stage", "data-state": g.state },
          h("header", null, h("h2", null, g.title), h("p", null, g.state === "active" ? fmt.num(g.percent, 0) + " %" : g.state === "locked" ? "gesperrt" : g.state)),
          h("div", { class: "body" }, h("div", { class: "pillars" }, (g.pillars || []).map(p => h("div", null, h("h3", null, p.title),
            h("div", { class: "bars" }, (p.items || []).map(it => {
              const pct = it.target > 0 ? it.have / it.target : 0;
              return h("div", { class: "bar" },
                h("div", { class: "bar-top" }, h("span", null, it.name), h("b", null, it.have + " / " + (it.target || "?"))),
                h("div", { class: "meter" }, h("span", { style: { width: Math.min(100, pct * 100).toFixed(1) + "%", background: "var(--gold)" } })));
            }))))),
            g.recent?.length ? h("p", { class: "muted", style: { marginTop: "1rem" } }, "Zuletzt: " + g.recent.slice(0, 3).map(r => r.name.trim() + " " + r.amount + " " + r.itemName).join(", ")) : null)))));
    } catch (e) {
      put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, e.message)));
    }
  };
  load();
}

async function core(cmd, title, text) {
  if (!(await confirmDialog({ title, text, ok: "Ja" }))) return;
  await run(title.replace("?", ""), () => api("POST", "/servers/" + S.overview.servers[0].name + "/command", { cmd }));
  if (S.page?.path === "/season") route();
}

// ---- pack ------------------------------------------------------------------------------------------

function versionKey(v) { return (v || "0").split(".").map(n => parseInt(n, 10) || 0); }
function newer(a, b) {
  const x = versionKey(a), y = versionKey(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  return false;
}

function pagePack(main) {
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, "Lade..."));
  main.append(header("Pack und Mods", "Was auf dem Server läuft und was im Repository wartet."), body);
  const load = async fresh => {
    const p = await api("GET", "/pack" + (fresh ? "?fresh=1" : ""));
    const local = p.local?.version, remote = p.remote?.version;
    const behind = remote && local && newer(remote, local);
    const sections = (p.remote?.changelog || "").split(/^## /m).slice(1).map(s => {
      const [head, ...rest] = s.split("\n");
      return { v: head.trim(), text: rest.join("\n").trim() };
    }).filter(s => local && newer(s.v, local));
    const players = S.overview.servers.reduce((n, s) => n + s.players.length, 0);
    const update = async () => {
      const ok = await confirmDialog({
        title: "Pack auf " + (remote || "den neuesten Stand") + " bringen?",
        text: "Alle Server stoppen, packwiz holt Mods und Configs, dann starten sie wieder. " + (players ? players + " Spieler fliegen dabei raus." : "Gerade ist niemand online."),
        ok: "Aktualisieren", danger: players > 0,
      });
      if (ok) { await run("Pack-Update läuft", () => api("POST", "/pack/update", {})); go("/konsole/" + S.overview.servers[0].name); }
    };
    const mods = p.mods || [];
    const q = h("input", { type: "search", placeholder: mods.length + " Mods durchsuchen", "aria-label": "Mods durchsuchen" });
    const tbody = h("tbody");
    const drawMods = () => put(tbody, ...mods.filter(m => m.file.toLowerCase().includes(q.value.toLowerCase())).map(m =>
      h("tr", null, h("td", null, m.file.startsWith("kronwerke-core") ? h("b", null, m.file) : m.file), h("td", { class: "right dim" }, fmt.bytes(m.size)))));
    q.addEventListener("input", drawMods);
    drawMods();
    put(body, 
      h("section", { class: "panel" }, h("div", { class: "body", style: { display: "flex", flexWrap: "wrap", gap: "1rem 2.5rem", alignItems: "center" } },
        h("div", null, h("div", { class: "muted" }, "Auf dem Server"), h("b", { style: { fontSize: "1.5rem" } }, local || "?")),
        h("div", null, h("div", { class: "muted" }, "Im Repository"), h("b", { style: { fontSize: "1.5rem" } }, remote || (p.remote?.error ? "nicht erreichbar" : "?"))),
        h("div", null, h("div", { class: "muted" }, "NeoForge"), h("b", null, p.local?.neoforge || "?")),
        h("span", { style: { flex: "1" } }),
        p.updating ? h("span", { class: "pill warn" }, "Update läuft") : behind ? h("span", { class: "pill warn" }, sections.length + (sections.length === 1 ? " Version" : " Versionen") + " dahinter") : h("span", { class: "pill ok" }, "Aktuell"),
        h("button", { class: "btn quiet", onclick: () => load(true) }, "Neu prüfen"),
        can("pack") ? h("button", { class: "btn " + (behind ? "primary" : ""), onclick: update, disabled: p.updating || null }, "Pack aktualisieren") : null)),
      sections.length ? h("section", { class: "panel" }, h("header", null, h("h2", null, "Was dazukommt")),
        h("div", { class: "body stack" }, sections.map(s => h("div", null, h("b", null, s.v), h("div", { class: "muted", style: { whiteSpace: "pre-wrap", marginTop: "0.3rem" } }, s.text))))) : null,
      h("section", { class: "panel" }, h("header", null, h("h2", null, "Mods"), q), h("table", null, tbody)));
  };
  load(false).catch(e => put(body, h("p", { class: "empty" }, e.message)));
}

// ---- files -------------------------------------------------------------------------------------------

function pageFiles(main, rest) {
  const servers = S.overview.servers.map(s => s.name);
  const parts = rest.split("/").filter(Boolean);
  const server = servers.includes(parts[0]) ? parts[0] : servers[0];
  const path = servers.includes(parts[0]) ? parts.slice(1).join("/") : "";
  const at = p => "/dateien/" + server + (p ? "/" + p : "");
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, "Lade..."));
  const seg = h("div", { class: "seg" }, servers.map(n => h("button", { type: "button", "aria-pressed": String(n === server), style: { "--c": colorOf(n) }, onclick: () => go("/dateien/" + n) }, h("span", { class: "dot" }), n)));
  const crumbs = h("nav", { class: "crumbs", "aria-label": "Pfad" },
    h("a", { href: at(""), "data-link": true }, server),
    path.split("/").filter(Boolean).flatMap((p, i, arr) => [h("span", null, "/"), h("a", { href: at(arr.slice(0, i + 1).join("/")), "data-link": true }, p)]));
  main.append(header("Dateien", "Configs, KubeJS und Mods dürfen geändert werden, die Welt nur gelesen. Schlüssel bleiben unsichtbar.", seg), body);

  api("GET", "/servers/" + server + "/files?path=" + encodeURIComponent(path)).then(d => {
    if (d.dir) {
      const up = h("input", { type: "file", multiple: true, hidden: true });
      up.addEventListener("change", async () => {
        for (const f of up.files) {
          const buf = await f.arrayBuffer();
          let s = "";
          const b = new Uint8Array(buf);
          for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
          await run(f.name + " hochgeladen", () => api("PUT", "/servers/" + server + "/files", { path: (path ? path + "/" : "") + f.name, data: btoa(s) }));
        }
        route();
      });
      const entries = d.entries.sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name));
      put(body, h("section", { class: "panel files" },
        h("header", null, crumbs, h("div", { class: "actions" }, up, h("button", { class: "btn small", onclick: () => up.click() }, svg(ICON.upload), "Hochladen"))),
        entries.length ? h("table", null, h("tbody", null, entries.map(e => h("tr", null,
          h("td", null, h("a", { href: at((path ? path + "/" : "") + e.name), "data-link": true }, svg(e.dir ? ICON.folder : ICON.file), e.name)),
          h("td", { class: "right dim hide-s" }, e.dir ? "" : fmt.bytes(e.size)),
          h("td", { class: "right dim hide-s" }, fmt.date(e.modified)))))) : h("p", { class: "empty" }, "Der Ordner ist leer.")));
      return;
    }
    const bytes = Uint8Array.from(atob(d.data), c => c.charCodeAt(0));
    const binary = bytes.slice(0, 8000).includes(0);
    const text = binary ? "" : new TextDecoder().decode(bytes);
    const name = path.split("/").pop();
    const download = () => {
      const a = h("a", { href: URL.createObjectURL(new Blob([bytes])), download: name });
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    };
    const area = binary ? null : h("textarea", { class: "editor", spellcheck: "false", readonly: !d.writable || null, "aria-label": name }, text);
    const save = async () => {
      const enc = new TextEncoder().encode(area.value);
      let s = "";
      for (let i = 0; i < enc.length; i += 0x8000) s += String.fromCharCode(...enc.subarray(i, i + 0x8000));
      await run(name + " gespeichert", () => api("PUT", "/servers/" + server + "/files", { path, data: btoa(s) }));
    };
    const del = async () => {
      if (!(await confirmDialog({ title: name + " löschen?", text: "Das lässt sich nicht rückgängig machen.", ok: "Löschen", danger: true }))) return;
      await run(name + " gelöscht", () => api("DELETE", "/servers/" + server + "/files?path=" + encodeURIComponent(path)));
      go(at(path.split("/").slice(0, -1).join("/")));
    };
    if (area) {
      area.addEventListener("keydown", e => {
        if ((e.ctrlKey || e.metaKey) && e.key === "s" && d.writable) { e.preventDefault(); save(); }
        if (e.key === "Tab" && d.writable) {
          e.preventDefault();
          const s = area.selectionStart;
          area.setRangeText("    ", s, area.selectionEnd, "end");
        }
      });
    }
    put(body, h("section", { class: "panel" },
      h("header", null, crumbs, h("div", { class: "actions" },
        h("span", { class: "dim" }, fmt.bytes(d.size) + (d.writable ? "" : ", nur lesen")),
        h("button", { class: "btn small", onclick: download }, svg(ICON.download), "Herunterladen"),
        d.writable ? h("button", { class: "btn small danger", onclick: del }, "Löschen") : null,
        d.writable && area ? h("button", { class: "btn small primary", onclick: save }, "Speichern") : null)),
      area || h("p", { class: "empty" }, "Binärdatei, nur zum Herunterladen.")));
  }).catch(e => put(body, h("section", { class: "panel" }, h("header", null, crumbs), h("p", { class: "empty" }, e.message))));
}

// ---- resources ---------------------------------------------------------------------------------------

function pageResources(main) {
  const body = h("div", { class: "stack" });
  main.append(header("Ressourcen", "CPU-Anteile wirken sofort, Arbeitsspeicher ab dem nächsten Start des Servers."), body);
  const setServer = (s, key, value, label) => run(label, () => api("POST", "/servers/" + s + "/config", { key, value: String(value) })).then(refreshOverview);
  const setLauncher = (key, value) => run("Gespeichert", () => api("POST", "/launcher/config", { key, value: String(value) })).then(refreshOverview);
  const draw = () => {
    const o = S.overview, c = o.container;
    const cells = [];
    const pinned = c.pinned || {};
    const owner = {};
    for (const [n, cpus] of Object.entries(pinned)) for (const cpu of cpus) owner[cpu] = n;
    const total = Math.max(c.cpus || 0, 1);
    for (let i = 0; i < total; i++) cells.push(h("span", owner[i] != null ? { style: { "--c": colorOf(owner[i]) }, title: "CPU " + i + ": " + owner[i] } : { title: "CPU " + i }, i));
    const sumHeap = o.servers.reduce((n, s) => n + (parseInt(s.memory, 10) || 0) * (/M$/i.test(s.memory) ? 1 / 1024 : 1), 0);
    put(body, 
      h("section", { class: "panel" },
        h("header", null, h("h2", null, "CPU"), h("p", null, fmt.num(c.cpuLimit, 1) + " Kerne erlaubt, " + c.cpus + " sichtbar")),
        h("div", { class: "body stack" },
          h("div", { class: "cpus", "aria-label": "Welcher Server welche CPU nutzt" }, cells),
          can("config") ? h("div", { class: "actions" },
            h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: c.pin || null, onchange: e => setLauncher("cpu.pin", e.target.checked) }), "Server auf ihre Anteile begrenzen"),
            h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: c.balance || null, disabled: !c.pin || null, onchange: e => setLauncher("cpu.balance", e.target.checked) }), "Automatisch umverteilen, wenn einer hängt")) : null,
          h("div", { class: "stack" }, o.servers.map(s => {
            const out = h("output", { class: "num" }, s.share);
            const range = h("input", { type: "range", min: 1, max: 16, value: s.share, style: { "--c": colorOf(s.name) }, disabled: !can("config") || null, "aria-label": "CPU-Anteil " + s.name });
            range.addEventListener("input", () => { out.textContent = range.value; });
            range.addEventListener("change", () => setServer(s.name, "cpu.share", range.value, s.name + " bekommt Anteil " + range.value));
            return h("div", { class: "share" }, h("span", { class: "pill c", style: { "--c": colorOf(s.name) } }, s.name), range, out);
          })))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, "Arbeitsspeicher"), h("p", null, fmt.num(sumHeap, 0) + " GB Heap vergeben, " + (c.memoryLimitGb || "?") + " GB im Container, pro Server 3 GB Puffer")),
        h("table", null, h("thead", null, h("tr", null, h("th", null, "Server"), h("th", null, "Heap"), h("th", { class: "hide-s" }, "Belegt"), h("th", null, "Autostart"), h("th", null, "Nach Absturz neu"))),
          h("tbody", null, o.servers.map(s => {
            const mem = h("input", { type: "text", value: s.memory, size: 6, disabled: !can("config") || null, "aria-label": "Heap " + s.name });
            mem.addEventListener("change", () => setServer(s.name, "memory", mem.value.trim().toUpperCase(), s.name + ": Heap " + mem.value));
            return h("tr", null,
              h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(s.name) } }, s.name)),
              h("td", null, mem),
              h("td", { class: "hide-s dim" }, fmt.bytes(s.last?.rss)),
              h("td", null, h("input", { type: "checkbox", class: "switch", checked: s.autostart || null, disabled: !can("config") || null, onchange: e => setServer(s.name, "autostart", e.target.checked, "Gespeichert") })),
              h("td", null, h("input", { type: "checkbox", class: "switch", checked: s.restartOnCrash || null, disabled: !can("config") || null, onchange: e => setServer(s.name, "restart.on.crash", e.target.checked, "Gespeichert") })));
          })))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, "Langsamste Dimensionen"), h("p", null, "Aus neoforge tps, alle zehn Sekunden")),
        h("table", null, h("tbody", null, o.servers.flatMap(s => (s.dimensions || []).slice(0, 6).map(d => h("tr", null,
          h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(s.name) } }, s.name)),
          h("td", null, d.name),
          h("td", { class: "right num" }, fmt.num(d.mspt, 2) + " ms"))))))));
  };
  draw();
  let last = 0;
  on("overview", () => { if (Date.now() - last > 30000 && !document.activeElement?.matches("input")) { last = Date.now(); draw(); } });
}

// ---- history -----------------------------------------------------------------------------------------

function pageHistory(main, rest) {
  const tabs = [["zeitleiste", "Zeitleiste"], ["protokoll", "Wer hat was getan"], ["abstuerze", "Abstürze"]];
  const tab = tabs.some(t => t[0] === rest) ? rest : "zeitleiste";
  const body = h("div", { class: "stack" });
  main.append(header("Verlauf", null, h("div", { class: "seg" }, tabs.map(([k, n]) => h("button", { type: "button", "aria-pressed": String(k === tab), onclick: () => go("/verlauf/" + k, true) }, n)))), body);
  if (tab === "zeitleiste") {
    const draw = () => put(body, h("section", { class: "panel" }, timeline([...S.overview.events].reverse(), "tl-all")));
    draw();
    on("event", draw);
  } else if (tab === "protokoll") {
    api("GET", "/audit?n=300").then(rows => put(body, h("section", { class: "panel" }, rows.length ? h("table", null,
      h("thead", null, h("tr", null, h("th", null, "Wann"), h("th", null, "Wer"), h("th", null, "Was"), h("th", { class: "hide-s" }, "Von"))),
      h("tbody", null, rows.map(r => h("tr", null,
        h("td", { class: "dim" }, fmt.date(r.t)),
        h("td", null, r.who || "unbekannt", r.via === "key" ? h("span", { class: "pill", style: { marginLeft: "0.4rem" } }, "Schlüssel") : null),
        h("td", null, r.ok ? "" : h("span", { class: "pill bad", style: { marginRight: "0.4rem" } }, "fehlgeschlagen"), r.action, r.server ? " auf " + r.server : "", r.detail ? h("div", { class: "dim" }, r.detail) : null),
        h("td", { class: "dim hide-s" }, r.ip))))) : h("p", { class: "empty" }, "Noch keine Einträge."))));
  } else {
    Promise.all(S.overview.servers.map(s => api("GET", "/servers/" + s.name + "/crashes").then(list => list.map(c => ({ ...c, server: s.name }))))).then(all => {
      const rows = all.flat().sort((a, b) => b.modified - a.modified);
      put(body, h("section", { class: "panel" }, rows.length ? h("table", null, h("tbody", null, rows.map(c => h("tr", null,
        h("td", { class: "dim" }, fmt.date(c.modified)),
        h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(c.server) } }, c.server)),
        h("td", null, c.headline || c.name),
        h("td", { class: "right" }, can("files") ? h("a", { class: "btn small", href: "/dateien/" + c.server + "/crash-reports/" + c.name, "data-link": true }, "Lesen") : null)))))
        : h("p", { class: "empty" }, "Keine Absturzberichte. So soll es sein.")));
    });
  }
}

// ---- access ------------------------------------------------------------------------------------------

function pageAccess(main) {
  const body = h("div", { class: "stack" });
  main.append(header("Zugang", "Passkeys statt Passwörtern. Schlüssel für Programme wie Elchi Ops.",
    h("button", { class: "btn quiet", onclick: async () => { await api("POST", "/auth/logout", {}); S.session.user = null; door("login", "Abgemeldet."); } }, "Abmelden")), body);
  const addPasskey = async () => {
    try {
      const o = await api("POST", "/auth/register/options", { purpose: "add" });
      const cred = await createPasskey(o.options);
      await api("POST", "/auth/register", { id: o.id, credential: cred, label: deviceLabel() });
      toast("Passkey hinzugefügt", deviceLabel());
      route();
    } catch (e) { toast("Passkey nicht angelegt", passkeyError(e), true); }
  };
  api("GET", "/access").then(a => {
    if (a.me) {
      put(body, h("section", { class: "panel" }, h("div", { class: "body" }, h("p", null, "Angemeldet als ", h("b", null, a.me.name), ", Rolle ", ROLE_DE[a.me.role] || a.me.role, "."),
        h("button", { class: "btn", onclick: addPasskey }, svg(ICON.key), "Weiteren Passkey anlegen"))));
      return;
    }
    const me = S.session.user.id;
    const invite = async () => {
      const role = h("select", null, a.roles.filter(r => r !== "owner").map(r => h("option", { value: r }, ROLE_DE[r])), h("option", { value: "owner" }, ROLE_DE.owner));
      const name = h("input", { type: "text", placeholder: "Name, optional" });
      const d = h("dialog", null, h("form", { method: "dialog" }, h("h2", null, "Jemanden einladen"),
        h("p", null, "Der Link gilt 24 Stunden und nur einmal. Wer ihn öffnet, legt seinen Passkey an."),
        h("label", { class: "field" }, h("span", null, "Rolle"), role), h("label", { class: "field" }, h("span", null, "Name"), name),
        h("div", { class: "actions" }, h("button", { class: "btn quiet", value: "cancel" }, "Abbrechen"), h("button", { class: "btn primary", value: "ok" }, "Link erstellen"))));
      document.body.append(d);
      d.addEventListener("close", async () => {
        d.remove();
        if (d.returnValue !== "ok") return;
        const r = await run("Einladung erstellt", () => api("POST", "/access/invites", { role: role.value, name: name.value }));
        infoDialog("Einladungslink", "Schick ihn direkt an die Person. Er zeigt sich nur jetzt.", r.link);
        route();
      });
      d.showModal();
    };
    const newKey = async () => {
      const name = h("input", { type: "text", placeholder: "Elchi Ops", required: true, maxlength: 60 });
      const days = h("select", null, h("option", { value: "0" }, "Läuft nicht ab"), h("option", { value: "30" }, "30 Tage"), h("option", { value: "90" }, "90 Tage"), h("option", { value: "365" }, "Ein Jahr"));
      const boxes = a.scopes.map(s => h("label", { class: "check" }, h("input", { type: "checkbox", value: s, checked: s !== "config" || null }), SCOPE_DE[s] || s));
      const d = h("dialog", null, h("form", { method: "dialog" }, h("h2", null, "Neuer API-Schlüssel"),
        h("p", null, "Für Programme, die die Console ohne Passkey nutzen. Der Schlüssel zeigt sich einmal, danach nur noch sein Anfang."),
        h("label", { class: "field" }, h("span", null, "Name"), name), h("label", { class: "field" }, h("span", null, "Gültig"), days),
        h("div", { class: "field" }, h("span", null, "Darf"), h("div", { style: { display: "flex", flexWrap: "wrap", gap: "0.5rem 1rem" } }, boxes)),
        h("div", { class: "actions" }, h("button", { class: "btn quiet", value: "cancel", formnovalidate: true }, "Abbrechen"), h("button", { class: "btn primary", value: "ok" }, "Erstellen"))));
      document.body.append(d);
      d.addEventListener("close", async () => {
        d.remove();
        if (d.returnValue !== "ok") return;
        const scopes = boxes.map(b => $("input", b)).filter(i => i.checked).map(i => i.value);
        const r = await run("Schlüssel erstellt", () => api("POST", "/access/keys", { name: name.value, scopes, days: Number(days.value) }));
        infoDialog("Dein API-Schlüssel", "Als Bearer-Token senden. Er erscheint nie wieder; wer ihn verliert, erstellt einen neuen.", r.token);
        route();
      });
      d.showModal();
    };
    put(body, 
      h("section", { class: "panel" }, h("header", null, h("h2", null, "Personen"), h("div", { class: "actions" },
        h("button", { class: "btn small", onclick: addPasskey }, svg(ICON.key), "Passkey für mich"),
        h("button", { class: "btn small primary", onclick: invite }, "Einladen"))),
        h("table", null, h("tbody", null, a.users.map(u => h("tr", null,
          h("td", null, h("b", null, u.name), u.id === me ? h("span", { class: "dim" }, " (du)") : null,
            h("div", { class: "dim" }, u.passkeys.map(p => p.label).join(", "))),
          h("td", null, u.id === me ? ROLE_DE[u.role] : h("select", { "aria-label": "Rolle von " + u.name, onchange: e => run("Rolle geändert", () => api("POST", "/access/users/" + u.id, { role: e.target.value })) },
            a.roles.map(r => h("option", { value: r, selected: r === u.role || null }, ROLE_DE[r])))),
          h("td", { class: "dim hide-s" }, u.sessions + (u.sessions === 1 ? " Sitzung" : " Sitzungen")),
          h("td", { class: "right" }, u.id === me ? (u.passkeys.length > 1 ? u.passkeys.map(p => h("button", { class: "btn small quiet", onclick: async () => {
            if (await confirmDialog({ title: "Passkey " + p.label + " entfernen?", ok: "Entfernen", danger: true })) { await run("Entfernt", () => api("DELETE", "/access/passkeys/" + encodeURIComponent(p.id), { user: u.id })); route(); }
          } }, p.label + " entfernen")) : null) : h("button", { class: "btn small danger", onclick: async () => {
            if (await confirmDialog({ title: u.name + " entfernen?", text: "Alle Passkeys und Sitzungen der Person enden sofort.", ok: "Entfernen", danger: true })) { await run(u.name + " entfernt", () => api("DELETE", "/access/users/" + u.id, {})); route(); }
          } }, "Entfernen"))))))),
      a.invites.length ? h("section", { class: "panel" }, h("header", null, h("h2", null, "Offene Einladungen")),
        h("table", null, h("tbody", null, a.invites.map(i => h("tr", null, h("td", null, i.name || "ohne Name"), h("td", null, ROLE_DE[i.role]), h("td", { class: "dim" }, "bis " + fmt.date(i.expires * 1000)),
          h("td", { class: "right" }, h("button", { class: "btn small quiet", onclick: async () => { await run("Einladung zurückgezogen", () => api("DELETE", "/access/invites/" + i.id, {})); route(); } }, "Zurückziehen"))))))) : null,
      h("section", { class: "panel" }, h("header", null, h("h2", null, "API-Schlüssel"), h("button", { class: "btn small", onclick: newKey }, svg(ICON.key), "Neuer Schlüssel")),
        a.keys.length ? h("table", null, h("tbody", null, a.keys.map(k => h("tr", null,
          h("td", null, h("b", null, k.name), h("div", { class: "dim" }, k.prefix + "...")),
          h("td", { class: "hide-s" }, (k.scopes || []).map(s => h("span", { class: "pill", style: { marginRight: "0.3rem" } }, SCOPE_DE[s] || s))),
          h("td", { class: "dim" }, k.used ? "zuletzt " + fmt.date(k.used * 1000) : "nie benutzt", k.expires ? h("div", null, "bis " + fmt.date(k.expires * 1000)) : null),
          h("td", { class: "right" }, h("button", { class: "btn small danger", onclick: async () => {
            if (await confirmDialog({ title: k.name + " widerrufen?", text: "Programme mit diesem Schlüssel kommen ab sofort nicht mehr rein.", ok: "Widerrufen", danger: true })) { await run("Widerrufen", () => api("DELETE", "/access/keys/" + k.id, {})); route(); }
          } }, "Widerrufen")))))) : h("p", { class: "empty" }, "Noch keine Schlüssel.")));
  }).catch(e => put(body, h("p", { class: "empty" }, e.message)));
}

// ---- command palette ---------------------------------------------------------------------------------

function paletteItems() {
  const items = PAGES.filter(p => !p.scope || can(p.scope)).map(p => ({ name: p.name, hint: "Seite", act: () => go(p.path) }));
  for (const s of S.overview?.servers || []) {
    items.push({ name: "Konsole " + s.name, hint: "Server", act: () => go("/konsole/" + s.name) });
    if (can("files")) items.push({ name: "Dateien " + s.name, hint: "Server", act: () => go("/dateien/" + s.name) });
    if (can("power")) {
      if (s.state === "stopped" || s.state === "crashed") items.push({ name: s.name + " starten", hint: "Aktion", act: () => power(s.name, "start") });
      else {
        items.push({ name: s.name + " neu starten", hint: "Aktion", act: () => power(s.name, "restart") });
        items.push({ name: s.name + " stoppen", hint: "Aktion", act: () => power(s.name, "stop") });
        items.push({ name: s.name + " hart beenden", hint: "Aktion", act: () => power(s.name, "kill") });
      }
    }
    for (const p of s.players) if (can("players")) items.push({ name: "Kicken: " + p, hint: s.name, act: () => kick({ name: p, server: s.name }) });
  }
  if (can("pack")) items.push({ name: "Pack aktualisieren", hint: "Aktion", act: () => go("/pack") });
  if (can("power")) items.push({ name: "Launcher neu laden", hint: "Aktion", act: async () => {
    if (await confirmDialog({ title: "Launcher neu laden?", text: "Die Server laufen weiter, nur der Launcher startet frisch. Die Seite verbindet sich danach neu.", ok: "Neu laden" })) {
      await run("Launcher lädt neu", () => api("POST", "/launcher/reload", {}));
    }
  } });
  items.push({ name: "Abmelden", hint: "Konto", act: async () => { await api("POST", "/auth/logout", {}); S.session.user = null; door("login", "Abgemeldet."); } });
  return items;
}

function openPalette() {
  if ($("dialog.palette")) return;
  const input = h("input", { type: "text", placeholder: "Wohin, oder was tun?", "aria-label": "Suchen" });
  const list = h("ul", { role: "listbox" });
  const d = h("dialog", { class: "palette" }, input, list);
  let items = paletteItems(), shown = [], sel = 0;
  const draw = () => {
    const q = input.value.toLowerCase().trim();
    shown = items.filter(i => !q || q.split(/\s+/).every(w => (i.name + " " + i.hint).toLowerCase().includes(w))).slice(0, 30);
    sel = Math.min(sel, Math.max(0, shown.length - 1));
    put(list, ...shown.map((i, k) => h("li", { role: "option", "aria-selected": String(k === sel), onmousedown: e => { e.preventDefault(); pick(k); } }, i.name, h("small", null, i.hint))));
  };
  const pick = k => { const i = shown[k]; d.close(); if (i) i.act(); };
  input.addEventListener("input", () => { sel = 0; draw(); });
  input.addEventListener("keydown", e => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      sel = (sel + (e.key === "ArrowDown" ? 1 : -1) + shown.length) % Math.max(1, shown.length);
      draw();
      $$("li", list)[sel]?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") { e.preventDefault(); pick(sel); }
  });
  d.addEventListener("close", () => d.remove());
  d.addEventListener("click", e => { if (e.target === d) d.close(); });
  document.body.append(d);
  draw();
  d.showModal();
  input.focus();
}

document.addEventListener("keydown", e => {
  if (!S.session?.user) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); openPalette(); return; }
  if (e.target.matches("input, textarea, select") || e.ctrlKey || e.metaKey || e.altKey || $("dialog[open]")) return;
  if (S.keys === "g") {
    S.keys = "";
    const p = PAGES.find(x => x.key === e.key);
    if (p) { e.preventDefault(); go(p.path); }
    return;
  }
  if (e.key === "g") { S.keys = "g"; setTimeout(() => { S.keys = ""; }, 1200); }
  if (e.key === "/" && S.page?.path === "/konsole") { e.preventDefault(); $(".prompt input")?.focus(); }
});

// ---- start --------------------------------------------------------------------------------------------

async function start() {
  try {
    S.session = await api("GET", "/session");
  } catch (e) {
    put($("#app"), h("div", { class: "door" }, h("div", { class: "door-card" }, crownMark(), h("h1", null, "Keine Verbindung"), h("p", null, e.message),
      h("button", { class: "btn", onclick: () => location.reload() }, "Nochmal versuchen"))));
    return;
  }
  const path = location.pathname;
  if (!S.session.user) {
    if (path === "/setup" && S.session.setup) return door("setup");
    if (path === "/invite" && location.hash.length > 1) return door("invite");
    return door("login");
  }
  if (path === "/setup" || path === "/invite") history.replaceState(null, "", "/");
  try {
    S.overview = await api("GET", "/overview");
  } catch (e) {
    toast("Übersicht nicht geladen", e.message, true);
    return;
  }
  shell();
  route();
  startStream();
}

start();
