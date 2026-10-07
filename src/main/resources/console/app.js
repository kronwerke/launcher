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
      else if (k === "style" && typeof v === "object") {
        for (const [p, x] of Object.entries(v)) el.style.setProperty(p.startsWith("--") ? p : p.replace(/[A-Z]/g, c => "-" + c.toLowerCase()), x);
      }
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
  const own = (S.overview?.servers || []).find(s => s.name === name)?.color;
  if (own) return own;
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

const dec = v => (LANG === "de" ? String(v).replace(".", ",") : String(v));
const fmt = {
  bytes(b) {
    if (b == null || b < 0) return "?";
    const u = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;
    while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
    return dec(b >= 100 || i === 0 ? Math.round(b) : b.toFixed(1)) + " " + u[i];
  },
  num(n, d = 1) { return n == null || n < 0 ? "?" : dec(Number(n).toFixed(d)); },
  clock(t) { const d = new Date(t); return d.toLocaleTimeString(LANG === "de" ? "de-DE" : "en-GB", { hour: "2-digit", minute: "2-digit" }); },
  date(t) { const d = new Date(t); return d.toLocaleString(LANG === "de" ? "de-DE" : "en-GB", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }); },
  since(iso) {
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return T("gerade eben");
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

// ---- language ----------------------------------------------------------------------------
// The interface is written in German; English comes from this table. Missing entries stay German.

let LANG = "en";
const EN = {
  "läuft": "running",
  "startet": "starting",
  "stoppt": "stopping",
  "gestoppt": "stopped",
  "aktualisiert": "updating",
  "abgestürzt": "crashed",
  "Inhaber": "Owner",
  "Moderation": "Moderator",
  "Nur lesen": "Read only",
  "Lesen": "Read",
  "Spieler": "Players",
  "Befehle": "Commands",
  "Starten und Stoppen": "Start and stop",
  "Dateien": "Files",
  "Einstellungen": "Settings",
  "gerade eben": "just now",
  "Der Launcher antwortet nicht.": "The launcher does not answer.",
  "Die Sitzung ist abgelaufen.": "The session has expired.",
  " hat nicht geklappt": " did not work",
  "Weiter": "Continue",
  "Abbrechen": "Cancel",
  "Kopiert": "Copied",
  "Kopieren": "Copy",
  "Fertig": "Done",
  "Dieser Browser kann keine Passkeys.": "This browser cannot use passkeys.",
  "Dieser Browser gibt den Schlüssel nicht heraus. Bitte einen aktuellen Browser nehmen.": "This browser does not hand out the key. Please use a current browser.",
  "Abgebrochen oder abgelaufen.": "Cancelled or expired.",
  "Dieser Passkey ist hier schon angelegt.": "This passkey is already registered here.",
  "Einrichtungscode aus der Server-Konsole oder setup.code im Konsolen-Ordner": "Setup code from the server console, or setup.code in the console folder",
  "Dein Name": "Your name",
  "Passkey anlegen": "Create passkey",
  "Erster Passkey": "First passkey",
  "Wer diesen Code hat, hat Zugriff auf den Server. Er gilt nur einmal und verschwindet, sobald dein Passkey angelegt ist.": "Whoever has this code has access to the server. It works once and is gone as soon as your passkey exists.",
  "Passkey anlegen und beitreten": "Create passkey and join",
  "Einladung": "Invitation",
  "Du wurdest eingeladen. Ein Passkey ersetzt das Passwort: dein Gerät bestätigt mit Fingerabdruck, Gesicht oder PIN.": "You have been invited. A passkey replaces the password: your device confirms with a fingerprint, your face or a PIN.",
  "Mit Passkey anmelden": "Sign in with a passkey",
  "Server, Dateien und Spieler an einem Ort.": "Servers, files and players in one place.",
  "Noch niemand eingerichtet? ": "Nobody set up yet? ",
  "Ersten Passkey anlegen": "Create the first passkey",
  "Übersicht": "Overview",
  "Konsole": "Console",
  "Pack und Mods": "Pack and mods",
  "Ressourcen": "Resources",
  "Verlauf": "History",
  "Zugang": "Access",
  "Bereiche": "Sections",
  "Suchen und Befehle": "Search and commands",
  "Millisekunden pro Tick": "Milliseconds per tick",
  "letzte Stunde": "last hour",
  "Die Kurve wächst alle zehn Sekunden.": "The curve grows every ten seconds.",
  "Noch keine Messung.": "No sample yet.",
  "Starten": "Start",
  "Neustart": "Restart",
  "Stoppen": "Stop",
  "startet neu": "restarts",
  "Beenden erzwingen": "Force quit",
  "wird hart beendet": "is killed",
  " Spieler ist": " player is",
  " Spieler sind": " players are",
  " gerade drauf. ": " online right now. ",
  "Niemand ist drauf. ": "Nobody is online. ",
  "Ohne Speichern, nur wenn er hängt.": "Without saving, only when it hangs.",
  "Die Welt wird vorher gespeichert.": "The world is saved first.",
  "Tickzeit der letzten Stunde": "Tick time of the last hour",
  "pro Tick": "per tick",
  " Kerne": " cores",
  " Spieler": " players",
  " Kernen": " cores",
  " von ": " of ",
  " sichtbar": " visible",
  "wird gezählt": "counting",
  " belegt": " used",
  "Arbeitsspeicher": "Memory",
  "Festplatte": "Disk",
  "Zeitleiste": "Timeline",
  "Alles": "All",
  "Befehl": "Command",
  "Stopp": "Stop",
  "hart beendet": "killed",
  "Einstellung": "Setting",
  "Datei geschrieben": "File written",
  "Datei gelöscht": "File deleted",
  "Pack-Update": "Pack update",
  "Launcher neu geladen": "Launcher reloaded",
  "Launcher-Update": "Launcher update",
  "Einladung zurückgezogen": "Invitation withdrawn",
  "neuer Schlüssel": "new key",
  "Schlüssel widerrufen": "Key revoked",
  "Person entfernt": "Person removed",
  "Rolle": "Role",
  "Passkey entfernt": "Passkey removed",
  " hat sich angemeldet": " signed in",
  "Pack-Update: $1 Server stoppen": "Pack update: stopping $1 servers",
  "Pack $1 auf $2": "Pack $1 to $2",
  "Console auf Port ": "Console on port ",
  ", nur über Cloudflare": ", Cloudflare only",
  "Launcher $1 gestartet (Java $2), Server: $3": "Launcher $1 started (Java $2), servers: $3",
  "Launcher wird neu geladen, die Server laufen weiter": "Launcher reloading, the servers keep running",
  "Noch nichts passiert.": "Nothing has happened yet.",
  "Neue Zeilen": "New lines",
  "Befehl, ohne Schrägstrich. Pfeiltasten für den Verlauf, Tab ergänzt.": "Command, without a slash. Arrow keys for history, Tab completes.",
  "Filtern": "Filter",
  "Zeilen filtern": "Filter lines",
  "Lade...": "Loading...",
  "Alle": "All",
  "Nur Warnungen und Fehler": "Only warnings and errors",
  "Nur lesen.": "Read only.",
  "Wer gerade auf welchem Server ist. Die Liste erneuert sich alle zehn Sekunden.": "Who is on which server. The list refreshes every ten seconds.",
  "Gerade ist niemand online.": "Nobody is online right now.",
  "Nachricht": "Message",
  "Kicken": "Kick",
  "Nachricht an ": "Message to ",
  "Senden": "Send",
  "Nachricht gesendet": "Message sent",
  "Der Grund steht auf seinem Bildschirm.": "The reason appears on their screen.",
  "Grund": "Reason",
  "Kurze Wartung, gleich wieder da.": "Short maintenance, back in a moment.",
  "Was Kronwerke Core über die Season und die Ziele des Obelisken sagt.": "What Kronwerke Core says about the season and the obelisk's goals.",
  " läuft nicht, die Season ist gerade nicht lesbar.": " is not running, the season cannot be read right now.",
  "Season pausieren?": "Pause the season?",
  "Zurück in die Vorbereitung: Abgaben zählen nicht mehr, bis die Season wieder läuft.": "Back to preparation: deposits stop counting until the season runs again.",
  "Pausieren": "Pause",
  "Season starten?": "Start the season?",
  "Ab jetzt zählen Abgaben und Fortschritt.": "From now on deposits and progress count.",
  "Season starten": "Start season",
  "in Vorbereitung": "in preparation",
  "Nummer": "Number",
  "Stufe ": "Tier ",
  "Gestartet": "Started",
  "Zuletzt: ": "Latest: ",
  "Ja": "Yes",
  "Was auf dem Server läuft und was im Repository wartet.": "What runs on the server and what waits in the repository.",
  "Pack auf ": "Bring the pack to ",
  "den neuesten Stand": "the latest version",
  "Alle Server stoppen, packwiz holt Mods und Configs, dann starten sie wieder. ": "Every server stops, packwiz fetches mods and configs, then they start again. ",
  " Spieler fliegen dabei raus.": " players will be disconnected.",
  "Aktualisieren": "Update",
  "Pack-Update läuft": "Pack update running",
  " Mods durchsuchen": " mods, search",
  "Mods durchsuchen": "Search mods",
  "Auf dem Server": "On the server",
  "Im Repository": "In the repository",
  "nicht erreichbar": "not reachable",
  "Update läuft": "Update running",
  " Version": " version",
  " Versionen": " versions",
  "Aktuell": "Up to date",
  "Neu prüfen": "Check again",
  "Pack aktualisieren": "Update pack",
  "Was dazukommt": "What comes with it",
  "Pfad": "Path",
  "Configs, KubeJS und Mods dürfen geändert werden, die Welt nur gelesen. Schlüssel bleiben unsichtbar.": "Configs, KubeJS and mods can be changed, the world only read. Keys stay invisible.",
  "Hochladen": "Upload",
  "Der Ordner ist leer.": "The folder is empty.",
  " löschen?": ": delete?",
  "Das lässt sich nicht rückgängig machen.": "This cannot be undone.",
  "Löschen": "Delete",
  " gelöscht": " deleted",
  ", nur lesen": ", read only",
  "Herunterladen": "Download",
  "Speichern": "Save",
  "Binärdatei, nur zum Herunterladen.": "Binary file, download only.",
  "CPU-Anteile wirken sofort, Arbeitsspeicher ab dem nächsten Start des Servers.": "CPU shares apply at once, memory from the server's next start.",
  "Gespeichert": "Saved",
  " Kerne erlaubt, ": " cores allowed, ",
  "Welcher Server welche CPU nutzt": "Which server uses which CPU",
  "Server auf ihre Anteile begrenzen": "Limit servers to their shares",
  "Automatisch umverteilen, wenn einer hängt": "Rebalance when one struggles",
  "CPU-Anteil ": "CPU share ",
  " bekommt Anteil ": " gets share ",
  " GB Heap vergeben, ": " GB heap given, ",
  " GB im Container, pro Server 3 GB Puffer": " GB in the container, 3 GB headroom per server",
  "Belegt": "Used",
  "Nach Absturz neu": "Restart after crash",
  "Langsamste Dimensionen": "Slowest dimensions",
  "Aus neoforge tps, alle zehn Sekunden": "From neoforge tps, every ten seconds",
  "Wer hat was getan": "Who did what",
  "Abstürze": "Crashes",
  "Wann": "When",
  "Wer": "Who",
  "Was": "What",
  "Von": "From",
  "Schlüssel": "Key",
  "Noch keine Einträge.": "No entries yet.",
  "Keine Absturzberichte. So soll es sein.": "No crash reports. As it should be.",
  "Passkeys statt Passwörtern. Schlüssel für Programme.": "Passkeys instead of passwords. Keys for programs.",
  "Abgemeldet.": "Signed out.",
  "Abmelden": "Sign out",
  "Passkey hinzugefügt": "Passkey added",
  "Passkey nicht angelegt": "Passkey not created",
  "Angemeldet als ": "Signed in as ",
  ", Rolle ": ", role ",
  "Weiteren Passkey anlegen": "Add another passkey",
  "Name, optional": "Name, optional",
  "Jemanden einladen": "Invite someone",
  "Der Link gilt 24 Stunden und nur einmal. Wer ihn öffnet, legt seinen Passkey an.": "The link is valid for 24 hours and once. Whoever opens it creates their passkey.",
  "Link erstellen": "Create link",
  "Einladung erstellt": "Invitation created",
  "Einladungslink": "Invitation link",
  "Schick ihn direkt an die Person. Er zeigt sich nur jetzt.": "Send it straight to the person. It is shown only now.",
  "Läuft nicht ab": "Never expires",
  "30 Tage": "30 days",
  "90 Tage": "90 days",
  "Ein Jahr": "One year",
  "Neuer API-Schlüssel": "New API key",
  "Für Programme, die die Console ohne Passkey nutzen. Der Schlüssel zeigt sich einmal, danach nur noch sein Anfang.": "For programs that use the console without a passkey. The key is shown once, afterwards only its beginning.",
  "Gültig": "Valid",
  "Darf": "May",
  "Erstellen": "Create",
  "Schlüssel erstellt": "Key created",
  "Dein API-Schlüssel": "Your API key",
  "Als Bearer-Token senden. Er erscheint nie wieder; wer ihn verliert, erstellt einen neuen.": "Send it as a bearer token. It never appears again; whoever loses it makes a new one.",
  "Personen": "People",
  "Passkey für mich": "Passkey for me",
  "Einladen": "Invite",
  "Rolle von ": "Role of ",
  "Rolle geändert": "Role changed",
  " Sitzung": " session",
  " Sitzungen": " sessions",
  "Entfernen": "Remove",
  "Entfernt": "Removed",
  "Alle Passkeys und Sitzungen der Person enden sofort.": "All passkeys and sessions of this person end at once.",
  "Offene Einladungen": "Open invitations",
  "ohne Name": "no name",
  "Zurückziehen": "Withdraw",
  "API-Schlüssel": "API keys",
  "Neuer Schlüssel": "New key",
  "nie benutzt": "never used",
  "Programme mit diesem Schlüssel kommen ab sofort nicht mehr rein.": "Programs with this key are locked out at once.",
  "Widerrufen": "Revoke",
  "Noch keine Schlüssel.": "No keys yet.",
  "Seite": "Page",
  "Konsole ": "Console ",
  "Dateien ": "Files ",
  "Aktion": "Action",
  " neu starten": ": restart",
  " hart beenden": ": kill",
  "Kicken: ": "Kick: ",
  "g dann ": "g then ",
  "Launcher neu laden": "Reload launcher",
  "Launcher neu laden?": "Reload the launcher?",
  "Die Server laufen weiter, nur der Launcher startet frisch. Die Seite verbindet sich danach neu.": "The servers keep running, only the launcher starts fresh. The page reconnects afterwards.",
  "Neu laden": "Reload",
  "Launcher lädt neu": "Launcher reloading",
  "Konto": "Account",
  "Wohin, oder was tun?": "Where to, or what to do?",
  "Suchen": "Search",
  "Keine Verbindung": "No connection",
  "Nochmal versuchen": "Try again",
  "Übersicht nicht geladen": "Overview not loaded",
  "Server": "Server",
  " starten": ": start",
  " stoppen": ": stop",
  " seit ": " for ",
  "bis ": "until ",
  "zuletzt ": "last ",
  ": Heap ": ": heap ",
  "Heap ": "Heap ",
  "fehlgeschlagen": "failed",
  "unbekannt": "unknown",
  " auf ": " on ",
  "Text": "Text",
  "Name": "Name",
  "Gerät": "Device",
  "Sitzungen": "sessions",
  "Kein": "No",
  " hochgeladen": " uploaded",
  " gespeichert": " saved",
  " gekickt": " kicked",
  " kicken?": ": kick?",
  "(du)": "(you)",
  " (du)": " (you)",
  "Abgemeldet": "Signed out",
  "Die Kurve": "The curve",
  "gesperrt": "locked",
  "Season": "Season",
  "schlummert": "slumbering",
  ", schlummert": ", slumbering",
  "Spieler suchen": "Search players",
  "Jeder, den der Server kennt: online, auf der Whitelist, Operatoren, Gebannte und wer in den letzten 30 Tagen da war.": "Everyone the server knows: online, whitelisted, operators, banned, and whoever was here in the last 30 days.",
  "Zur Whitelist hinzufügen": "Add to whitelist",
  "Hinzufügen": "Add",
  "Minecraft-Name": "Minecraft name",
  " ist auf der Whitelist": " is on the whitelist",
  "Gebannt": "Banned",
  "eigener Platz, ": "own place, ",
  " Plätze": " slots",
  "eingeladen von ": "invited by ",
  "Niemand passt zur Suche.": "Nobody matches the search.",
  "Hier ist niemand.": "Nobody here.",
  "Status": "Status",
  "Plätze": "Slots",
  "Zuletzt da": "Last seen",
  "jetzt": "now",
  "Gerade auf ": "Right now on ",
  "Zuletzt da ": "Last seen ",
  "War noch nicht da": "Has not been here yet",
  "Schließen": "Close",
  "Grund: ": "Reason: ",
  "Eigener Platz. ": "Own place. ",
  "Streamer. ": "Streamer. ",
  " Plätzen vergeben.": " slots given.",
  "Eingeladen von ": "Invited by ",
  "Kein Platz in Kronwerke.": "No place in Kronwerke.",
  " als Streamer anlegen?": ": add as streamer?",
  "Bekommt einen eigenen Platz und so viele Plätze für Zuschauer. Leer heißt Standard.": "Gets a place of their own and this many slots for viewers. Empty means the default.",
  "Anlegen": "Add",
  " angelegt": " added",
  "Als Streamer anlegen": "Add as streamer",
  "Plätze verwalten": "Manage slots",
  "Platz frei": "Slot freed",
  "Platz bei ": "Free the slot of ",
  " freigeben": "",
  "Aktionen": "Actions",
  "Von der Whitelist": "Off the whitelist",
  "Auf der Whitelist": "On the whitelist",
  "Von der Whitelist nehmen": "Remove from whitelist",
  "Zur Whitelist": "Whitelist",
  "Op entziehen": "Remove op",
  "Op geben": "Make op",
  "Entbannt": "Unbanned",
  " bannen?": ": ban?",
  "Bannen": "Ban",
  " gebannt": " banned",
  "Entbannen": "Unban",
  "Streamer anlegen": "Add streamer",
  "Der Minecraft-Name. Plätze danach auf der Karte.": "Their Minecraft name. Slots afterwards on the card.",
  "Standard: ": "Default: ",
  " Plätze pro Streamer.": " slots per streamer.",
  "eigener Platz": "own place",
  "Streamer": "Streamer",
  " entfernen": ": remove",
  " entfernen?": ": remove?",
  "Der Platz bei ": "The slot of ",
  " wird frei, der Spieler fliegt von der Whitelist.": " is freed, the player leaves the whitelist.",
  "Spieler für ": "Player for ",
  " eingeladen": " invited",
  "Der eigene Platz und alle Plätze, die ": "The place of their own and every slot ",
  " vergeben hat, fallen weg.": " gave away are gone.",
  " entfernt": " removed",
  "Noch keine Streamer.": "No streamers yet.",
  "Online": "Online",
  "Whitelist": "Whitelist",
  "Operatoren": "Operators",
  "Streamer und Plätze": "Streamers and slots",
  "Spieler einladen": "Invite player"
  };
function T(s) {
  if (s == null) return s;
  return LANG === "de" ? s : (EN[s] ?? s);
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
    throw new Error(T("Der Launcher antwortet nicht."));
  }
  let j;
  try { j = await res.json(); } catch { j = { ok: false, error: "HTTP " + res.status }; }
  if (res.status === 401 && S.session?.user && !path.startsWith("/auth")) {
    S.session.user = null;
    door("login", T("Die Sitzung ist abgelaufen."));
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
    toast(title + T(" hat nicht geklappt"), e.message, true);
    throw e;
  }
}

function confirmDialog({ title, text, ok = T("Weiter"), danger, input }) {
  return new Promise(resolve => {
    const field = input ? h("input", { type: "text", placeholder: input.placeholder || "", value: input.value || "", required: input.required || null }) : null;
    const d = h("dialog", null,
      h("form", { method: "dialog" },
        h("h2", null, title),
        text ? h("p", null, text) : null,
        input ? h("label", { class: "field" }, h("span", null, input.label), field) : null,
        h("div", { class: "actions" },
          h("button", { class: "btn quiet", value: "cancel", type: "submit", formnovalidate: true }, T("Abbrechen")),
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
        secret ? h("button", { class: "btn", type: "button", onclick: () => { navigator.clipboard?.writeText(secret); toast(T("Kopiert")); } }, T("Kopieren")) : null,
        h("button", { class: "btn primary", value: "ok" }, T("Fertig")))));
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
  if (!window.PublicKeyCredential) throw new Error(T("Dieser Browser kann keine Passkeys."));
  const opts = structuredClone(o);
  opts.challenge = b64u.dec(opts.challenge);
  opts.user.id = b64u.dec(opts.user.id);
  opts.excludeCredentials = (opts.excludeCredentials || []).map(c => ({ ...c, id: b64u.dec(c.id) }));
  const cred = await navigator.credentials.create({ publicKey: opts });
  const r = cred.response;
  if (!r.getPublicKey || !r.getPublicKey()) throw new Error(T("Dieser Browser gibt den Schlüssel nicht heraus. Bitte einen aktuellen Browser nehmen."));
  return {
    id: b64u.enc(cred.rawId),
    publicKey: b64u.enc(r.getPublicKey()),
    publicKeyAlgorithm: r.getPublicKeyAlgorithm(),
    authenticatorData: b64u.enc(r.getAuthenticatorData()),
    clientDataJSON: b64u.enc(r.clientDataJSON),
  };
}

async function getPasskey(o) {
  if (!window.PublicKeyCredential) throw new Error(T("Dieser Browser kann keine Passkeys."));
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
  if (e?.name === "NotAllowedError") return T("Abgebrochen oder abgelaufen.");
  if (e?.name === "InvalidStateError") return T("Dieser Passkey ist hier schon angelegt.");
  return e?.message || String(e);
}

function deviceLabel() {
  const ua = navigator.userAgent;
  const os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : T("Gerät");
  const br = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  return os + ", " + br;
}

// ---- the door: sign in, first passkey, invites -------------------------------------------

function crownMark() {
  return svg('<path d="M4 19h16M5 19l-1-11 5 4.5L12 5l3 7.5L20 8l-1 11" stroke="#e5b451" stroke-width="1.6"/>');
}

function door(kind, message) {
  stopStream();
  document.title = S.session?.title || "Console";
  const app = $("#app");
  app.className = "";
  app.removeAttribute("aria-busy");
  const err = h("p", { class: "error", role: "alert" }, message && kind !== "login" ? message : "");
  const note = message && kind === "login" ? h("p", { class: "note" }, message) : null;
  let card;
  if (kind === "setup") {
    const code = h("input", { type: "text", autocomplete: "one-time-code", placeholder: "KW-XXXX-XXXX", required: true, spellcheck: "false" });
    const name = h("input", { type: "text", autocomplete: "nickname", placeholder: T("Name"), required: true, maxlength: 40 });
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
      h("label", { class: "field" }, h("span", null, T("Einrichtungscode aus der Server-Konsole oder setup.code im Konsolen-Ordner")), code),
      h("label", { class: "field" }, h("span", null, T("Dein Name")), name),
      h("button", { class: "btn primary", type: "submit" }, T("Passkey anlegen")), err);
    card = [h("h1", null, T("Erster Passkey")), h("p", null, T("Wer diesen Code hat, hat Zugriff auf den Server. Er gilt nur einmal und verschwindet, sobald dein Passkey angelegt ist.")), form];
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
      h("label", { class: "field" }, h("span", null, T("Dein Name")), name),
      h("button", { class: "btn primary", type: "submit" }, T("Passkey anlegen und beitreten")), err);
    card = [h("h1", null, T("Einladung")), h("p", null, T("Du wurdest eingeladen. Ein Passkey ersetzt das Passwort: dein Gerät bestätigt mit Fingerabdruck, Gesicht oder PIN.")), form];
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
      h("button", { class: "btn primary", type: "submit", autofocus: true }, svg(ICON.key), T("Mit Passkey anmelden")), err);
    card = [h("h1", null, S.session?.title || "Console"), h("p", null, T("Server, Dateien und Spieler an einem Ort.")), form, note,
      S.session?.setup ? h("p", { class: "note" }, T("Noch niemand eingerichtet? "), h("a", { href: "/setup" }, T("Ersten Passkey anlegen"))) : null];
  }
  put(app, h("div", { class: "door" }, h("div", { class: "door-card" }, crownMark(), card)));
  $("input, button", app)?.focus();
}

// ---- the shell ---------------------------------------------------------------------------

const PAGES = [
  { path: "/", name: "Übersicht", key: "o", draw: pageOverview },
  { path: "/konsole", name: "Konsole", key: "k", draw: pageConsole },
  { path: "/spieler", name: "Spieler", key: "s", draw: pagePlayers },
  { path: "/season", name: "Season", key: "e", draw: pageSeason, feature: "season" },
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
  const nav = h("nav", { class: "pages", "aria-label": T("Bereiche") },
    PAGES.filter(p => (!p.scope || can(p.scope)) && (!p.feature || S.session[p.feature])).map(p => h("a", { href: p.path, "data-link": true }, T(p.name), h("kbd", { title: T("g dann ") + p.key }, "g " + p.key))));
  const rail = h("aside", { class: "rail" },
    h("a", { class: "mark", href: "/", "data-link": true }, crownMark(), h("b", null, S.session.title.replace(/ Console$/, "") + " ", h("span", null, "Console"))),
    h("div", { class: "fleet", id: "fleet" }),
    nav,
    h("div", { class: "rail-foot" },
      h("button", { class: "palette-hint", onclick: openPalette }, T("Suchen und Befehle"), h("kbd", null, navigator.platform.includes("Mac") ? "Cmd K" : "Strg K")),
      h("span", { id: "who" }, S.session.user.name + ", " + (T(ROLE_DE[S.session.user.role]) || S.session.user.role)),
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
      h("i"), h("span", null, h("b", null, s.name), h("small", null, T(STATE_DE[s.state]) || s.state)),
      h("em", { title: T("Millisekunden pro Tick") }, s.state === "running" && m >= 0 ? fmt.num(m) + " ms" : ""));
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
  document.title = T(page.name) + ", " + S.session.title;
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
        if (S.session?.user) startStream(); else door("login", T("Die Sitzung ist abgelaufen."));
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
    g.fillText(pts.length ? T("Die Kurve wächst alle zehn Sekunden.") : T("Noch keine Messung."), 6, hgt - 12);
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
    out.push(h("button", { class: cls, onclick: () => power(s.name, "start") }, svg(ICON.play), T("Starten")));
  } else {
    out.push(h("button", { class: cls, onclick: () => power(s.name, "restart") }, svg(ICON.restart), T("Neustart")));
    out.push(h("button", { class: cls + " quiet", onclick: () => power(s.name, "stop") }, svg(ICON.stop), T("Stoppen")));
  }
  return out;
}

async function power(name, action) {
  const s = S.overview.servers.find(x => x.name === name);
  const n = s?.players?.length || 0;
  const words = { start: [T("Starten"), T("startet")], restart: [T("Neustart"), T("startet neu")], stop: [T("Stoppen"), T("stoppt")], kill: [T("Beenden erzwingen"), T("wird hart beendet")] };
  if (action !== "start") {
    const ok = await confirmDialog({
      title: words[action][0] + ": " + name + "?",
      text: (n ? n + (n === 1 ? T(" Spieler ist") : T(" Spieler sind")) + T(" gerade drauf. ") : T("Niemand ist drauf. ")) + (action === "kill" ? T("Ohne Speichern, nur wenn er hängt.") : T("Die Welt wird vorher gespeichert.")),
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
  main.append(header(T("Übersicht"), null,
    can("pack") ? h("a", { class: "btn", href: "/pack", "data-link": true }, "Pack " + (o.pack.version || "?")) : null),
    h("div", { class: "grid-2" }, cards, side));

  const drawCards = () => {
    put(cards, ...S.overview.servers.map(s => {
      const m = s.last?.mspt ?? -1;
      const canvas = h("canvas", { class: "trace", "aria-label": T("Tickzeit der letzten Stunde") });
      const card = h("section", { class: "panel pulse", style: { "--c": colorOf(s.name) }, "data-server": s.name },
        h("div", { class: "pulse-top" },
          h("div", null,
            h("div", { class: "pulse-name" },
              h("h2", null, h("a", { href: "/konsole/" + s.name, "data-link": true }, s.name)),
              h("span", { class: "state", "data-s": s.state }, (T(STATE_DE[s.state]) || s.state) + T(" seit ") + fmt.since(s.since))),
            h("div", { class: "pulse-meta" }, [s.detail, s.port ? "Port " + s.port : null, s.role && s.role !== s.name ? s.role : null].filter(Boolean).join(", "))),
          h("div", { class: "mspt", "data-health": s.state === "running" ? health(m) : "" },
            h("b", null, s.state === "running" && m >= 0 ? fmt.num(m) : "?", h("small", null, "ms")),
            h("span", null, s.state === "running" && s.last?.tps >= 0 ? fmt.num(s.last.tps) + " TPS" : T("pro Tick")))),
        canvas,
        h("div", { class: "pulse-foot" },
          h("span", null, "CPU ", h("b", null, s.last?.cpu >= 0 ? fmt.num(s.last.cpu) + " %" : "?"), " ", spark(S.metrics[s.name] || [], "cpu", resolveColor(colorOf(s.name)))),
          h("span", null, "RAM ", h("b", null, fmt.bytes(s.last?.rss)), " von ", s.memory || "?"),
          h("span", null, h("b", null, s.players.length), s.players.length === 1 ? " Spieler" : T(" Spieler")),
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
          bar("CPU", (cpu >= 0 ? fmt.num(cpu * 100) : "?") + " %" + T(" von ") + fmt.num(c.cpuLimit * 100, 0) + " %", cpuPct),
          bar(T("Arbeitsspeicher"), fmt.bytes(c.memory) + T(" von ") + fmt.bytes(c.memoryMax), memPct),
          bar(T("Festplatte"), c.disk < 0 ? T("wird gezählt") : fmt.bytes(c.disk) + (c.diskMax > 0 ? T(" von ") + fmt.bytes(c.diskMax) : T(" belegt")), diskPct))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, T("Zeitleiste")), h("a", { class: "btn small quiet", href: "/verlauf", "data-link": true }, T("Alles"))),
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
    t = (T(STATE_DE[word]) || word) + (rest.length ? ": " + rest.join(": ") : "");
  }
  if (e.kind === "action") {
    const words = { "command": T("Befehl"), "start": "Start", "stop": T("Stopp"), "restart": T("Neustart"), "kill": T("hart beendet"),
      "config": T("Einstellung"), "write": T("Datei geschrieben"), "delete": T("Datei gelöscht"), "pack update": T("Pack-Update"),
      "launcher reload": T("Launcher neu geladen"), "launcher update": T("Launcher-Update"), "invite": T("Einladung"),
      "drop invite": T("Einladung zurückgezogen"), "new key": T("neuer Schlüssel"), "drop key": T("Schlüssel widerrufen"),
      "remove person": T("Person entfernt"), "role": T("Rolle"), "drop passkey": T("Passkey entfernt") };
    const m = /^(.*?): (.*)$/.exec(t);
    if (m) {
      const key = Object.keys(words).sort((a, b) => b.length - a.length).find(k => m[2] === k || m[2].startsWith(k + " "));
      if (key) t = m[1] + ": " + words[key] + m[2].slice(key.length);
    }
  }
  return t.replace(/ signed in$/, T(" hat sich angemeldet"))
    .replace(/^pack update: stopping (\d+) servers$/, T("Pack-Update: $1 Server stoppen"))
    .replace(/^pack (\S+) to (\S+)$/, T("Pack $1 auf $2"))
    .replace(/^console on port (\d+)(, Cloudflare only)?$/, (m, p, cf) => T("Console auf Port ") + p + (cf ? T(", nur über Cloudflare") : ""))
    .replace(/^Launcher (\S+), java (\S+), servers (.+)$/, T("Launcher $1 gestartet (Java $2), Server: $3"))
    .replace(/^Handing the servers to the next launcher$/, T("Launcher wird neu geladen, die Server laufen weiter"));
}

function timeline(events, id) {
  if (!events.length) return h("p", { class: "empty" }, T("Noch nichts passiert."));
  return h("ul", { class: "timeline", id }, events.map(e => h("li", { "data-kind": e.kind, "data-bad": /crash|fail|abgest/i.test(e.text) || null },
    h("time", { datetime: e.t }, fmt.clock(e.t)),
    h("span", null, e.server ? h("span", { class: "who", style: { "--c": colorOf(e.server) } }, e.server) : null, h("span", { class: "what" }, eventText(e))))));
}

// ---- console ------------------------------------------------------------------------------------

const COMMANDS = ["list", "say ", "msg ", "kick ", "tp ", "gamemode spectator ", "gamemode survival ", "time set day", "weather clear",
  "neoforge tps", "neoforge entity list", "spark tps", "spark health", "spark profiler start", "spark profiler stop", "save-all",
  "whitelist list", "op ", "deop ", "forceload query", "chunky progress"];
const CORE_COMMANDS = ["kw admin list", "kw admin season json", "kw admin goals json", "kw admin obelisk info",
  "kw admin season start", "kw admin season pause", "kw admin active", "kw admin goal reload"];

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
  const newer = h("button", { class: "btn small newer", hidden: true, onclick: () => { log.scrollTop = log.scrollHeight; } }, T("Neue Zeilen"));
  const input = h("input", { type: "text", placeholder: T("Befehl, ohne Schrägstrich. Pfeiltasten für den Verlauf, Tab ergänzt."), autocomplete: "off", spellcheck: "false", "aria-label": T("Befehl") });
  const suggest = h("ul", { class: "suggest", hidden: true, role: "listbox" });
  const seg = h("div", { class: "seg", role: "group", "aria-label": "Server" });
  const search = h("input", { type: "search", placeholder: T("Filtern"), "aria-label": T("Zeilen filtern") });
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
    put(log, h("div", { class: "dim" }, T("Lade...")));
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
    stick = true;
    newer.hidden = true;
    log.scrollTop = log.scrollHeight;
    requestAnimationFrame(() => { log.scrollTop = log.scrollHeight; });
  };

  const drawSeg = () => put(seg, ...[...servers, "alle"].map(n => h("button", {
    type: "button", "aria-pressed": String(n === which), style: n === "alle" ? null : { "--c": colorOf(n) },
    onclick: () => { which = n; history.replaceState(null, "", "/konsole/" + n); drawSeg(); fill(); input.focus(); },
  }, n === "alle" ? null : h("span", { class: "dot" }), n === "alle" ? T("Alle") : n)));

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
      options = v ? [...COMMANDS, ...(S.session.season ? CORE_COMMANDS : [])].filter(c => c.startsWith(v) && c !== v) : [];
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
    h("div", { class: "console-bar" }, seg, search, h("label", { class: "check" }, errBox, T("Nur Warnungen und Fehler")),
      h("span", { style: { flex: "1" } }),
      ...(which !== "alle" ? powerButtons(S.overview.servers.find(s => s.name === which) || {}, true) : [])),
    can("command") || can("players") ? h("div", { class: "prompt" }, h("label", null, ">"), input, suggest) : h("div", { class: "prompt dim" }, T("Nur lesen.")),
    h("div", { style: { position: "relative", minHeight: "0", display: "grid" } }, log, newer));
  main.append(panel);
  fill();
  input.focus();
}

// ---- players ------------------------------------------------------------------------------------

function pagePlayers(main, rest) {
  const tabs = [["alle", "Alle"], ["online", "Online"], ["whitelist", "Whitelist"], ["ops", "Operatoren"], ["gebannt", "Gebannt"]];
  let tab = rest || "alle", q = "", data = null;
  const body = h("div", { class: "stack" });
  const search = h("input", { type: "search", placeholder: T("Spieler suchen"), "aria-label": T("Spieler suchen") });
  const seg = h("div", { class: "seg", role: "group" });
  main.append(header(T("Spieler"), T("Jeder, den der Server kennt: online, auf der Whitelist, Operatoren, Gebannte und wer in den letzten 30 Tagen da war."),
    search, can("players") ? h("button", { class: "btn primary", onclick: () => addToWhitelist() }, T("Zur Whitelist hinzufügen")) : null), seg, body);
  search.addEventListener("input", () => { q = search.value.trim().toLowerCase(); draw(); });

  const target = () => data?.server || S.overview.servers[0].name;
  const cmd = (title, c) => run(title, async () => {
    const a = await api("POST", "/servers/" + target() + "/command", { cmd: c });
    if (/^ERR/.test(a || "")) throw new Error(a.replace(/^ERR\s*/, ""));
    return a && a.length < 140 ? a.replace(/^OK\s*/, "") : "";
  }).then(load, () => {});
  const addToWhitelist = async () => {
    const n = await confirmDialog({ title: T("Zur Whitelist hinzufügen"), ok: T("Hinzufügen"), input: { label: T("Minecraft-Name"), required: true } });
    if (n) cmd(n + T(" ist auf der Whitelist"), "whitelist add " + n.trim());
  };

  const drawSeg = () => {
    const roster = data?.roster && Array.isArray(data.roster.streamers);
    const all = roster ? [...tabs.slice(0, 1), ["streamer", "Streamer und Plätze"], ...tabs.slice(1)] : tabs;
    put(seg, all.map(([k, n]) => h("button", { type: "button", "aria-pressed": String(k === tab), onclick: () => { tab = k; history.replaceState(null, "", "/spieler/" + k); drawSeg(); draw(); } }, T(n),
      data ? h("span", { class: "dim", style: { marginLeft: "0.4rem" } }, String(count(k))) : null)));
  };
  const filters = {
    alle: () => true, online: p => p.online, whitelist: p => p.whitelisted, ops: p => p.op != null, gebannt: p => p.banned != null,
  };
  const count = k => k === "streamer" ? data.roster.streamers.length : data.players.filter(filters[k]).length;

  const head = (n, size = 32) => h("img", { src: "https://mc-heads.net/avatar/" + encodeURIComponent(n) + "/" + size, alt: "", loading: "lazy" });
  const pills = p => [
    p.online ? h("span", { class: "pill c", style: { "--c": colorOf(p.online) } }, p.online) : null,
    p.whitelisted ? h("span", { class: "pill" }, "Whitelist") : null,
    p.op != null ? h("span", { class: "pill warn" }, "Op " + p.op) : null,
    p.banned != null ? h("span", { class: "pill bad" }, T("Gebannt")) : null,
  ];
  const roleOf = p => {
    const st = data?.roster?.streamers || [];
    const own = st.find(s => s.name.toLowerCase() === p.name.toLowerCase());
    const by = st.find(s => s.invited.some(i => i.name.toLowerCase() === p.name.toLowerCase()));
    return own ? (own.granted ? T("eigener Platz, ") : "") + own.used + "/" + own.slots + T(" Plätze") : by ? T("eingeladen von ") + by.name : "";
  };

  const draw = () => {
    if (!data) return;
    if (tab === "streamer") return drawRoster();
    const rows = data.players.filter(filters[tab] || filters.alle).filter(p => !q || p.name.toLowerCase().includes(q))
      .sort((a, b) => (!!b.online - !!a.online) || (b.seen || 0) - (a.seen || 0) || a.name.localeCompare(b.name));
    if (!rows.length) return put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, q ? T("Niemand passt zur Suche.") : T("Hier ist niemand."))));
    put(body, h("section", { class: "panel" }, h("table", { class: "people" },
      h("thead", null, h("tr", null, h("th", null, T("Spieler")), h("th", null, T("Status")), h("th", { class: "hide-s" }, data.roster ? T("Plätze") : ""), h("th", { class: "hide-s right" }, T("Zuletzt da")))),
      h("tbody", null, rows.map(p => h("tr", { class: "click", tabindex: "0", onclick: () => drawer(p), onkeydown: e => { if (e.key === "Enter") drawer(p); } },
        h("td", null, h("span", { class: "player" }, head(p.name), h("b", null, p.name))),
        h("td", null, h("span", { class: "actions" }, pills(p))),
        h("td", { class: "hide-s muted" }, roleOf(p)),
        h("td", { class: "hide-s right dim" }, p.online ? T("jetzt") : p.seen ? fmt.date(p.seen) : "")))))));
  };

  const drawer = p => {
    const st = data?.roster?.streamers || [];
    const own = st.find(s => s.name.toLowerCase() === p.name.toLowerCase());
    const by = st.find(s => s.invited.some(i => i.name.toLowerCase() === p.name.toLowerCase()));
    const d = h("dialog", { class: "drawer" }, h("div", { class: "drawer-body" },
      h("div", { class: "drawer-head" }, h("img", { class: "skin", src: "https://mc-heads.net/body/" + encodeURIComponent(p.name) + "/120", alt: "" }),
        h("div", null, h("h2", null, p.name), h("div", { class: "actions" }, pills(p)),
          h("p", { class: "muted" }, p.online ? T("Gerade auf ") + p.online : p.seen ? T("Zuletzt da ") + fmt.date(p.seen) : T("War noch nicht da")),
          p.uuid ? h("p", { class: "dim mono" }, p.uuid) : null),
        h("button", { class: "btn quiet small close", onclick: () => d.close(), "aria-label": T("Schließen") }, "×")),
      p.banned != null ? h("p", { class: "muted" }, T("Grund: ") + p.banned) : null,
      data.roster ? h("section", { class: "drawer-sec" }, h("h3", null, "Kronwerke"),
        own ? h("p", null, (own.granted ? T("Eigener Platz. ") : T("Streamer. ")) + own.used + T(" von ") + own.slots + T(" Plätzen vergeben."))
          : by ? h("p", null, T("Eingeladen von ") + by.name + ".") : h("p", { class: "muted" }, T("Kein Platz in Kronwerke.")),
        h("div", { class: "actions" },
          !own && !by && can("command") ? h("button", { class: "btn small", onclick: async () => {
            const n = await confirmDialog({ title: p.name + T(" als Streamer anlegen?"), text: T("Bekommt einen eigenen Platz und so viele Plätze für Zuschauer. Leer heißt Standard."), ok: T("Anlegen"), input: { label: T("Plätze"), value: "" } });
            if (n != null) { d.close(); cmd(p.name + T(" angelegt"), "kw admin grant " + p.name + (n.trim() ? " " + parseInt(n, 10) : "")); }
          } }, T("Als Streamer anlegen")) : null,
          own && can("command") ? h("button", { class: "btn small", onclick: () => { d.close(); tab = "streamer"; drawSeg(); draw(); } }, T("Plätze verwalten")) : null,
          by && can("command") ? h("button", { class: "btn small danger", onclick: () => { d.close(); cmd(T("Platz frei"), "kw admin revoke " + by.name + " " + p.name); } }, T("Platz bei ") + by.name + T(" freigeben")) : null)) : null,
      h("section", { class: "drawer-sec" }, h("h3", null, T("Aktionen")), h("div", { class: "actions" },
        p.online && can("players") ? h("button", { class: "btn small", onclick: () => { d.close(); message({ name: p.name, server: p.online }); } }, T("Nachricht")) : null,
        p.online && can("players") ? h("button", { class: "btn small danger", onclick: () => { d.close(); kick({ name: p.name, server: p.online }).then(load); } }, T("Kicken")) : null,
        can("players") ? h("button", { class: "btn small", onclick: () => { d.close(); cmd(p.whitelisted ? T("Von der Whitelist") : T("Auf der Whitelist"), "whitelist " + (p.whitelisted ? "remove " : "add ") + p.name); } }, p.whitelisted ? T("Von der Whitelist nehmen") : T("Zur Whitelist")) : null,
        can("command") ? h("button", { class: "btn small", onclick: () => { d.close(); cmd(p.op != null ? "Deop" : "Op", (p.op != null ? "deop " : "op ") + p.name); } }, p.op != null ? T("Op entziehen") : T("Op geben")) : null,
        can("players") ? h("button", { class: "btn small " + (p.banned != null ? "" : "danger"), onclick: async () => {
          if (p.banned != null) { d.close(); return cmd(T("Entbannt"), "pardon " + p.name); }
          const why = await confirmDialog({ title: p.name + T(" bannen?"), ok: T("Bannen"), danger: true, input: { label: T("Grund"), value: "" } });
          if (why != null) { d.close(); cmd(p.name + T(" gebannt"), "ban " + p.name + (why ? " " + why : "")); }
        } }, p.banned != null ? T("Entbannen") : T("Bannen")) : null))));
    d.addEventListener("close", () => d.remove());
    d.addEventListener("click", e => { if (e.target === d) d.close(); });
    document.body.append(d);
    d.showModal();
  };

  const drawRoster = () => {
    const r = data.roster;
    const list = r.streamers.filter(s => !q || s.name.toLowerCase().includes(q) || s.invited.some(i => i.name.toLowerCase().includes(q)))
      .sort((a, b) => a.granted - b.granted || a.name.localeCompare(b.name));
    const add = can("command") ? h("button", { class: "btn primary", onclick: async () => {
      const n = await confirmDialog({ title: T("Streamer anlegen"), text: T("Der Minecraft-Name. Plätze danach auf der Karte."), ok: T("Anlegen"), input: { label: T("Minecraft-Name"), required: true } });
      if (n) cmd(n + T(" angelegt"), "kw admin grant " + n.trim());
    } }, T("Streamer anlegen")) : null;
    put(body,
      h("div", { class: "actions" }, h("span", { class: "muted" }, T("Standard: ") + r.defaultSlots + T(" Plätze pro Streamer.")), h("span", { style: { flex: "1" } }), add),
      list.length ? h("div", { class: "roster" }, list.map(s => {
        const pct = s.slots > 0 ? s.used / s.slots : 0;
        const stepper = (label, value, onSet) => h("span", { class: "stepper" }, h("span", { class: "muted" }, label),
          can("command") ? h("button", { class: "btn small quiet", "aria-label": label + " -", onclick: () => onSet(value - 1) }, "-") : null,
          h("b", null, String(value)),
          can("command") ? h("button", { class: "btn small quiet", "aria-label": label + " +", onclick: () => onSet(value + 1) }, "+") : null);
        const base = s.override >= 0 ? s.override : r.defaultSlots;
        return h("section", { class: "panel streamer" },
          h("header", null, h("span", { class: "player" }, head(s.name), h("span", null, h("b", null, s.name), h("div", { class: "dim" }, s.granted ? T("eigener Platz") : T("Streamer")))),
            h("b", { class: "num", style: { fontSize: "1.25rem" } }, s.used + "/" + s.slots)),
          h("div", { class: "body stack" },
            h("div", { class: "meter" }, h("span", { style: { width: Math.min(100, pct * 100) + "%" }, "data-health": pct >= 1 ? "warn" : "" })),
            h("div", { class: "chips" }, s.invited.map(i => h("span", { class: "chip" }, head(i.name, 16), i.name,
              can("command") ? h("button", { "aria-label": i.name + T(" entfernen"), onclick: async () => {
                if (await confirmDialog({ title: i.name + T(" entfernen?"), text: T("Der Platz bei ") + s.name + T(" wird frei, der Spieler fliegt von der Whitelist."), ok: T("Entfernen"), danger: true })) cmd(T("Platz frei"), "kw admin revoke " + s.name + " " + i.name);
              } }, "×") : null)),
              can("command") && s.used < s.slots ? h("button", { class: "chip add", onclick: async () => {
                const n = await confirmDialog({ title: T("Spieler für ") + s.name, ok: T("Einladen"), input: { label: T("Minecraft-Name"), required: true } });
                if (n) cmd(n.trim() + T(" eingeladen"), "kw admin invite " + s.name + " " + n.trim());
              } }, "+ " + T("Spieler einladen")) : null),
            h("div", { class: "actions" },
              stepper(T("Plätze"), base, v => v >= 0 && cmd(s.name + ": " + v + T(" Plätze"), "kw admin slots " + s.name + " " + v)),
              stepper("Bonus", s.bonus, v => cmd(s.name + ": Bonus " + v, "kw admin bonus " + s.name + " " + (v - s.bonus))),
              h("span", { style: { flex: "1" } }),
              s.granted && can("command") ? h("button", { class: "btn small danger quiet", onclick: async () => {
                if (await confirmDialog({ title: s.name + T(" entfernen?"), text: T("Der eigene Platz und alle Plätze, die ") + s.name + T(" vergeben hat, fallen weg."), ok: T("Entfernen"), danger: true })) cmd(s.name + T(" entfernt"), "kw admin ungrant " + s.name);
              } }, T("Entfernen")) : null)));
      })) : h("section", { class: "panel" }, h("p", { class: "empty" }, T("Noch keine Streamer."))));
  };

  const load = async () => {
    try { data = await api("GET", "/people"); } catch (e) { put(body, h("p", { class: "empty" }, e.message)); return; }
    if (tab === "streamer" && !data.roster) tab = "alle";
    drawSeg();
    draw();
  };
  put(body, h("p", { class: "dim" }, T("Lade...")));
  load();
  let last = 0;
  on("overview", () => { if (Date.now() - last > 20000 && !$("dialog[open]")) { last = Date.now(); load(); } });
}

async function message(r) {
  const text = await confirmDialog({ title: T("Nachricht an ") + r.name, ok: T("Senden"), input: { label: T("Text"), required: true } });
  if (!text) return;
  await run(T("Nachricht gesendet"), () => api("POST", "/servers/" + r.server + "/command", { cmd: "tell " + r.name + " " + text }));
}

async function kick(r) {
  if (!r) return;
  const reason = await confirmDialog({ title: r.name + T(" kicken?"), text: T("Der Grund steht auf seinem Bildschirm."), ok: T("Kicken"), danger: true, input: { label: T("Grund"), value: T("Kurze Wartung, gleich wieder da.") } });
  if (reason == null) return;
  await run(r.name + T(" gekickt"), () => api("POST", "/servers/" + r.server + "/command", { cmd: "kick " + r.name + " " + reason }));
}

// ---- season --------------------------------------------------------------------------------------

function pageSeason(main) {
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, T("Lade...")));
  const acts = h("span", { class: "actions" });
  main.append(header(T("Season"), T("Was Kronwerke Core über die Season und die Ziele des Obelisken sagt."), acts), body);
  const load = async () => {
    try {
      const d = await api("GET", "/season");
      if (!d.season) {
        put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, d.server + T(" läuft nicht, die Season ist gerade nicht lesbar."))));
        return;
      }
      const s = d.season;
      const goals = Array.isArray(d.goals) ? d.goals : [];
      if (can("command")) {
        put(acts, s.running
          ? h("button", { class: "btn quiet", onclick: () => core("kw admin season pause", T("Season pausieren?"), T("Zurück in die Vorbereitung: Abgaben zählen nicht mehr, bis die Season wieder läuft.")) }, T("Pausieren"))
          : h("button", { class: "btn primary", onclick: () => core("kw admin season start", T("Season starten?"), T("Ab jetzt zählen Abgaben und Fortschritt.")) }, T("Season starten")));
      }
      put(body, 
        h("section", { class: "panel" }, h("div", { class: "body", style: { display: "flex", flexWrap: "wrap", gap: "1rem 2.5rem" } },
          h("div", null, h("div", { class: "muted" }, T("Season")), h("b", { style: { fontSize: "1.25rem" } }, s.running ? T("läuft") : T("in Vorbereitung"))),
          h("div", null, h("div", { class: "muted" }, T("Nummer")), h("b", { style: { fontSize: "1.25rem" } }, s.number)),
          h("div", null, h("div", { class: "muted" }, "Obelisk"), h("b", { style: { fontSize: "1.25rem" } }, T("Stufe ") + s.tier + (s.slumbering ? ", schlummert" : ""))),
          s.startedAt ? h("div", null, h("div", { class: "muted" }, T("Gestartet")), h("b", { style: { fontSize: "1.25rem" } }, fmt.date(s.startedAt))) : null)),
        h("div", { class: "stages" }, goals.map(g => h("section", { class: "panel stage", "data-state": g.state },
          h("header", null, h("h2", null, g.title), h("p", null, g.state === "active" ? fmt.num(g.percent, 0) + " %" : g.state === "locked" ? "gesperrt" : g.state)),
          h("div", { class: "body" }, h("div", { class: "pillars" }, (g.pillars || []).map(p => h("div", null, h("h3", null, p.title),
            h("div", { class: "bars" }, (p.items || []).map(it => {
              const pct = it.target > 0 ? it.have / it.target : 0;
              return h("div", { class: "bar" },
                h("div", { class: "bar-top" }, h("span", null, it.name), h("b", null, it.have + " / " + (it.target || "?"))),
                h("div", { class: "meter" }, h("span", { style: { width: Math.min(100, pct * 100).toFixed(1) + "%", background: "var(--gold)" } })));
            }))))),
            g.recent?.length ? h("p", { class: "muted", style: { marginTop: "1rem" } }, T("Zuletzt: ") + g.recent.slice(0, 3).map(r => r.name.trim() + " " + r.amount + " " + r.itemName).join(", ")) : null)))));
    } catch (e) {
      put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, e.message)));
    }
  };
  load();
}

async function core(cmd, title, text) {
  if (!(await confirmDialog({ title, text, ok: T("Ja") }))) return;
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
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, T("Lade...")));
  main.append(header(T("Pack und Mods"), T("Was auf dem Server läuft und was im Repository wartet.")), body);
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
        title: T("Pack auf ") + (remote || T("den neuesten Stand")) + " bringen?",
        text: T("Alle Server stoppen, packwiz holt Mods und Configs, dann starten sie wieder. ") + (players ? players + " Spieler fliegen dabei raus." : T("Gerade ist niemand online.")),
        ok: T("Aktualisieren"), danger: players > 0,
      });
      if (ok) { await run(T("Pack-Update läuft"), () => api("POST", "/pack/update", {})); go("/konsole/" + S.overview.servers[0].name); }
    };
    const mods = p.mods || [];
    const q = h("input", { type: "search", placeholder: mods.length + T(" Mods durchsuchen"), "aria-label": T("Mods durchsuchen") });
    const tbody = h("tbody");
    const drawMods = () => put(tbody, ...mods.filter(m => m.file.toLowerCase().includes(q.value.toLowerCase())).map(m =>
      h("tr", null, h("td", null, m.file.startsWith("kronwerke-core") ? h("b", null, m.file) : m.file), h("td", { class: "right dim" }, fmt.bytes(m.size)))));
    q.addEventListener("input", drawMods);
    drawMods();
    put(body, 
      h("section", { class: "panel" }, h("div", { class: "body", style: { display: "flex", flexWrap: "wrap", gap: "1rem 2.5rem", alignItems: "center" } },
        h("div", null, h("div", { class: "muted" }, T("Auf dem Server")), h("b", { style: { fontSize: "1.5rem" } }, local || "?")),
        h("div", null, h("div", { class: "muted" }, T("Im Repository")), h("b", { style: { fontSize: "1.5rem" } }, remote || (p.remote?.error ? T("nicht erreichbar") : "?"))),
        h("div", null, h("div", { class: "muted" }, "NeoForge"), h("b", null, p.local?.neoforge || "?")),
        h("span", { style: { flex: "1" } }),
        p.updating ? h("span", { class: "pill warn" }, T("Update läuft")) : behind ? h("span", { class: "pill warn" }, sections.length + (sections.length === 1 ? " Version" : T(" Versionen")) + " dahinter") : h("span", { class: "pill ok" }, T("Aktuell")),
        h("button", { class: "btn quiet", onclick: () => load(true) }, T("Neu prüfen")),
        can("pack") ? h("button", { class: "btn " + (behind ? "primary" : ""), onclick: update, disabled: p.updating || null }, T("Pack aktualisieren")) : null)),
      sections.length ? h("section", { class: "panel" }, h("header", null, h("h2", null, T("Was dazukommt"))),
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
  const body = h("div", { class: "stack" }, h("p", { class: "dim" }, T("Lade...")));
  const seg = h("div", { class: "seg" }, servers.map(n => h("button", { type: "button", "aria-pressed": String(n === server), style: { "--c": colorOf(n) }, onclick: () => go("/dateien/" + n) }, h("span", { class: "dot" }), n)));
  const crumbs = h("nav", { class: "crumbs", "aria-label": T("Pfad") },
    h("a", { href: at(""), "data-link": true }, server),
    path.split("/").filter(Boolean).flatMap((p, i, arr) => [h("span", null, "/"), h("a", { href: at(arr.slice(0, i + 1).join("/")), "data-link": true }, p)]));
  main.append(header(T("Dateien"), T("Configs, KubeJS und Mods dürfen geändert werden, die Welt nur gelesen. Schlüssel bleiben unsichtbar."), seg), body);

  api("GET", "/servers/" + server + "/files?path=" + encodeURIComponent(path)).then(d => {
    if (d.dir) {
      const up = h("input", { type: "file", multiple: true, hidden: true });
      up.addEventListener("change", async () => {
        for (const f of up.files) {
          const buf = await f.arrayBuffer();
          let s = "";
          const b = new Uint8Array(buf);
          for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
          await run(f.name + T(" hochgeladen"), () => api("PUT", "/servers/" + server + "/files", { path: (path ? path + "/" : "") + f.name, data: btoa(s) }));
        }
        route();
      });
      const entries = d.entries.sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name));
      put(body, h("section", { class: "panel files" },
        h("header", null, crumbs, h("div", { class: "actions" }, up, h("button", { class: "btn small", onclick: () => up.click() }, svg(ICON.upload), T("Hochladen")))),
        entries.length ? h("table", null, h("tbody", null, entries.map(e => h("tr", null,
          h("td", null, h("a", { href: at((path ? path + "/" : "") + e.name), "data-link": true }, svg(e.dir ? ICON.folder : ICON.file), e.name)),
          h("td", { class: "right dim hide-s" }, e.dir ? "" : fmt.bytes(e.size)),
          h("td", { class: "right dim hide-s" }, fmt.date(e.modified)))))) : h("p", { class: "empty" }, T("Der Ordner ist leer."))));
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
      await run(name + T(" gespeichert"), () => api("PUT", "/servers/" + server + "/files", { path, data: btoa(s) }));
    };
    const del = async () => {
      if (!(await confirmDialog({ title: name + T(" löschen?"), text: T("Das lässt sich nicht rückgängig machen."), ok: T("Löschen"), danger: true }))) return;
      await run(name + T(" gelöscht"), () => api("DELETE", "/servers/" + server + "/files?path=" + encodeURIComponent(path)));
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
        h("span", { class: "dim" }, fmt.bytes(d.size) + (d.writable ? "" : T(", nur lesen"))),
        h("button", { class: "btn small", onclick: download }, svg(ICON.download), T("Herunterladen")),
        d.writable ? h("button", { class: "btn small danger", onclick: del }, T("Löschen")) : null,
        d.writable && area ? h("button", { class: "btn small primary", onclick: save }, T("Speichern")) : null)),
      area || h("p", { class: "empty" }, T("Binärdatei, nur zum Herunterladen."))));
  }).catch(e => put(body, h("section", { class: "panel" }, h("header", null, crumbs), h("p", { class: "empty" }, e.message))));
}

// ---- resources ---------------------------------------------------------------------------------------

function pageResources(main) {
  const body = h("div", { class: "stack" });
  main.append(header(T("Ressourcen"), T("CPU-Anteile wirken sofort, Arbeitsspeicher ab dem nächsten Start des Servers.")), body);
  const setServer = (s, key, value, label) => run(label, () => api("POST", "/servers/" + s + "/config", { key, value: String(value) })).then(refreshOverview);
  const setLauncher = (key, value) => run(T("Gespeichert"), () => api("POST", "/launcher/config", { key, value: String(value) })).then(refreshOverview);
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
        h("header", null, h("h2", null, "CPU"), h("p", null, fmt.num(c.cpuLimit, 1) + T(" Kerne erlaubt, ") + c.cpus + T(" sichtbar"))),
        h("div", { class: "body stack" },
          h("div", { class: "cpus", "aria-label": T("Welcher Server welche CPU nutzt") }, cells),
          can("config") ? h("div", { class: "actions" },
            h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: c.pin || null, onchange: e => setLauncher("cpu.pin", e.target.checked) }), T("Server auf ihre Anteile begrenzen")),
            h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: c.balance || null, disabled: !c.pin || null, onchange: e => setLauncher("cpu.balance", e.target.checked) }), T("Automatisch umverteilen, wenn einer hängt"))) : null,
          h("div", { class: "stack" }, o.servers.map(s => {
            const out = h("output", { class: "num" }, s.share);
            const range = h("input", { type: "range", min: 1, max: 16, value: s.share, style: { "--c": colorOf(s.name) }, disabled: !can("config") || null, "aria-label": T("CPU-Anteil ") + s.name });
            range.addEventListener("input", () => { out.textContent = range.value; });
            range.addEventListener("change", () => setServer(s.name, "cpu.share", range.value, s.name + T(" bekommt Anteil ") + range.value));
            return h("div", { class: "share" }, h("span", { class: "pill c", style: { "--c": colorOf(s.name) } }, s.name), range, out);
          })))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, T("Arbeitsspeicher")), h("p", null, fmt.num(sumHeap, 0) + T(" GB Heap vergeben, ") + (c.memoryLimitGb || "?") + T(" GB im Container, pro Server 3 GB Puffer"))),
        h("table", null, h("thead", null, h("tr", null, h("th", null, "Server"), h("th", null, "Heap"), h("th", { class: "hide-s" }, T("Belegt")), h("th", null, "Autostart"), h("th", null, T("Nach Absturz neu")))),
          h("tbody", null, o.servers.map(s => {
            const mem = h("input", { type: "text", value: s.memory, size: 6, disabled: !can("config") || null, "aria-label": T("Heap ") + s.name });
            mem.addEventListener("change", () => setServer(s.name, "memory", mem.value.trim().toUpperCase(), s.name + T(": Heap ") + mem.value));
            return h("tr", null,
              h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(s.name) } }, s.name)),
              h("td", null, mem),
              h("td", { class: "hide-s dim" }, fmt.bytes(s.last?.rss)),
              h("td", null, h("input", { type: "checkbox", class: "switch", checked: s.autostart || null, disabled: !can("config") || null, onchange: e => setServer(s.name, "autostart", e.target.checked, T("Gespeichert")) })),
              h("td", null, h("input", { type: "checkbox", class: "switch", checked: s.restartOnCrash || null, disabled: !can("config") || null, onchange: e => setServer(s.name, "restart.on.crash", e.target.checked, T("Gespeichert")) })));
          })))),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, T("Langsamste Dimensionen")), h("p", null, T("Aus neoforge tps, alle zehn Sekunden"))),
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
  const tabs = [["zeitleiste", T("Zeitleiste")], ["protokoll", T("Wer hat was getan")], ["abstuerze", T("Abstürze")]];
  const tab = tabs.some(t => t[0] === rest) ? rest : "zeitleiste";
  const body = h("div", { class: "stack" });
  main.append(header(T("Verlauf"), null, h("div", { class: "seg" }, tabs.map(([k, n]) => h("button", { type: "button", "aria-pressed": String(k === tab), onclick: () => go("/verlauf/" + k, true) }, n)))), body);
  if (tab === "zeitleiste") {
    const draw = () => put(body, h("section", { class: "panel" }, timeline([...S.overview.events].reverse(), "tl-all")));
    draw();
    on("event", draw);
  } else if (tab === "protokoll") {
    api("GET", "/audit?n=300").then(rows => put(body, h("section", { class: "panel" }, rows.length ? h("table", null,
      h("thead", null, h("tr", null, h("th", null, T("Wann")), h("th", null, T("Wer")), h("th", null, T("Was")), h("th", { class: "hide-s" }, T("Von")))),
      h("tbody", null, rows.map(r => h("tr", null,
        h("td", { class: "dim" }, fmt.date(r.t)),
        h("td", null, r.who || T("unbekannt"), r.via === "key" ? h("span", { class: "pill", style: { marginLeft: "0.4rem" } }, T("Schlüssel")) : null),
        h("td", null, r.ok ? "" : h("span", { class: "pill bad", style: { marginRight: "0.4rem" } }, T("fehlgeschlagen")), r.action, r.server ? T(" auf ") + r.server : "", r.detail ? h("div", { class: "dim" }, r.detail) : null),
        h("td", { class: "dim hide-s" }, r.ip))))) : h("p", { class: "empty" }, T("Noch keine Einträge.")))));
  } else {
    Promise.all(S.overview.servers.map(s => api("GET", "/servers/" + s.name + "/crashes").then(list => list.map(c => ({ ...c, server: s.name }))))).then(all => {
      const rows = all.flat().sort((a, b) => b.modified - a.modified);
      put(body, h("section", { class: "panel" }, rows.length ? h("table", null, h("tbody", null, rows.map(c => h("tr", null,
        h("td", { class: "dim" }, fmt.date(c.modified)),
        h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(c.server) } }, c.server)),
        h("td", null, c.headline || c.name),
        h("td", { class: "right" }, can("files") ? h("a", { class: "btn small", href: "/dateien/" + c.server + "/crash-reports/" + c.name, "data-link": true }, T("Lesen")) : null)))))
        : h("p", { class: "empty" }, T("Keine Absturzberichte. So soll es sein."))));
    });
  }
}

// ---- access ------------------------------------------------------------------------------------------

function pageAccess(main) {
  const body = h("div", { class: "stack" });
  main.append(header(T("Zugang"), T("Passkeys statt Passwörtern. Schlüssel für Programme."),
    h("button", { class: "btn quiet", onclick: async () => { await api("POST", "/auth/logout", {}); S.session.user = null; door("login", T("Abgemeldet.")); } }, T("Abmelden"))), body);
  const addPasskey = async () => {
    try {
      const o = await api("POST", "/auth/register/options", { purpose: "add" });
      const cred = await createPasskey(o.options);
      await api("POST", "/auth/register", { id: o.id, credential: cred, label: deviceLabel() });
      toast(T("Passkey hinzugefügt"), deviceLabel());
      route();
    } catch (e) { toast(T("Passkey nicht angelegt"), passkeyError(e), true); }
  };
  api("GET", "/access").then(a => {
    if (a.me) {
      put(body, h("section", { class: "panel" }, h("div", { class: "body" }, h("p", null, T("Angemeldet als "), h("b", null, a.me.name), T(", Rolle "), T(ROLE_DE[a.me.role]) || a.me.role, "."),
        h("button", { class: "btn", onclick: addPasskey }, svg(ICON.key), T("Weiteren Passkey anlegen")))));
      return;
    }
    const me = S.session.user.id;
    const invite = async () => {
      const role = h("select", null, a.roles.filter(r => r !== "owner").map(r => h("option", { value: r }, T(ROLE_DE[r]))), h("option", { value: "owner" }, T(ROLE_DE.owner)));
      const name = h("input", { type: "text", placeholder: T("Name, optional") });
      const d = h("dialog", null, h("form", { method: "dialog" }, h("h2", null, T("Jemanden einladen")),
        h("p", null, T("Der Link gilt 24 Stunden und nur einmal. Wer ihn öffnet, legt seinen Passkey an.")),
        h("label", { class: "field" }, h("span", null, T("Rolle")), role), h("label", { class: "field" }, h("span", null, T("Name")), name),
        h("div", { class: "actions" }, h("button", { class: "btn quiet", value: "cancel" }, T("Abbrechen")), h("button", { class: "btn primary", value: "ok" }, T("Link erstellen")))));
      document.body.append(d);
      d.addEventListener("close", async () => {
        d.remove();
        if (d.returnValue !== "ok") return;
        const r = await run(T("Einladung erstellt"), () => api("POST", "/access/invites", { role: role.value, name: name.value }));
        infoDialog(T("Einladungslink"), T("Schick ihn direkt an die Person. Er zeigt sich nur jetzt."), r.link);
        route();
      });
      d.showModal();
    };
    const newKey = async () => {
      const name = h("input", { type: "text", placeholder: T("Name"), required: true, maxlength: 60 });
      const days = h("select", null, h("option", { value: "0" }, T("Läuft nicht ab")), h("option", { value: "30" }, T("30 Tage")), h("option", { value: "90" }, T("90 Tage")), h("option", { value: "365" }, T("Ein Jahr")));
      const boxes = a.scopes.map(s => h("label", { class: "check" }, h("input", { type: "checkbox", value: s, checked: s !== "config" || null }), T(SCOPE_DE[s]) || s));
      const d = h("dialog", null, h("form", { method: "dialog" }, h("h2", null, T("Neuer API-Schlüssel")),
        h("p", null, T("Für Programme, die die Console ohne Passkey nutzen. Der Schlüssel zeigt sich einmal, danach nur noch sein Anfang.")),
        h("label", { class: "field" }, h("span", null, T("Name")), name), h("label", { class: "field" }, h("span", null, T("Gültig")), days),
        h("div", { class: "field" }, h("span", null, T("Darf")), h("div", { style: { display: "flex", flexWrap: "wrap", gap: "0.5rem 1rem" } }, boxes)),
        h("div", { class: "actions" }, h("button", { class: "btn quiet", value: "cancel", formnovalidate: true }, T("Abbrechen")), h("button", { class: "btn primary", value: "ok" }, T("Erstellen")))));
      document.body.append(d);
      d.addEventListener("close", async () => {
        d.remove();
        if (d.returnValue !== "ok") return;
        const scopes = boxes.map(b => $("input", b)).filter(i => i.checked).map(i => i.value);
        const r = await run(T("Schlüssel erstellt"), () => api("POST", "/access/keys", { name: name.value, scopes, days: Number(days.value) }));
        infoDialog(T("Dein API-Schlüssel"), T("Als Bearer-Token senden. Er erscheint nie wieder; wer ihn verliert, erstellt einen neuen."), r.token);
        route();
      });
      d.showModal();
    };
    put(body, 
      h("section", { class: "panel" }, h("header", null, h("h2", null, T("Personen")), h("div", { class: "actions" },
        h("button", { class: "btn small", onclick: addPasskey }, svg(ICON.key), T("Passkey für mich")),
        h("button", { class: "btn small primary", onclick: invite }, T("Einladen")))),
        h("table", null, h("tbody", null, a.users.map(u => h("tr", null,
          h("td", null, h("b", null, u.name), u.id === me ? h("span", { class: "dim" }, T(" (du)")) : null,
            h("div", { class: "dim" }, u.passkeys.map(p => p.label).join(", "))),
          h("td", null, u.id === me ? T(ROLE_DE[u.role]) : h("select", { "aria-label": T("Rolle von ") + u.name, onchange: e => run(T("Rolle geändert"), () => api("POST", "/access/users/" + u.id, { role: e.target.value })) },
            a.roles.map(r => h("option", { value: r, selected: r === u.role || null }, T(ROLE_DE[r]))))),
          h("td", { class: "dim hide-s" }, u.sessions + (u.sessions === 1 ? " Sitzung" : T(" Sitzungen"))),
          h("td", { class: "right" }, u.id === me ? (u.passkeys.length > 1 ? u.passkeys.map(p => h("button", { class: "btn small quiet", onclick: async () => {
            if (await confirmDialog({ title: "Passkey " + p.label + " entfernen?", ok: T("Entfernen"), danger: true })) { await run(T("Entfernt"), () => api("DELETE", "/access/passkeys/" + encodeURIComponent(p.id), { user: u.id })); route(); }
          } }, p.label + " entfernen")) : null) : h("button", { class: "btn small danger", onclick: async () => {
            if (await confirmDialog({ title: u.name + " entfernen?", text: T("Alle Passkeys und Sitzungen der Person enden sofort."), ok: T("Entfernen"), danger: true })) { await run(u.name + " entfernt", () => api("DELETE", "/access/users/" + u.id, {})); route(); }
          } }, T("Entfernen")))))))),
      a.invites.length ? h("section", { class: "panel" }, h("header", null, h("h2", null, T("Offene Einladungen"))),
        h("table", null, h("tbody", null, a.invites.map(i => h("tr", null, h("td", null, i.name || T("ohne Name")), h("td", null, T(ROLE_DE[i.role])), h("td", { class: "dim" }, T("bis ") + fmt.date(i.expires * 1000)),
          h("td", { class: "right" }, h("button", { class: "btn small quiet", onclick: async () => { await run(T("Einladung zurückgezogen"), () => api("DELETE", "/access/invites/" + i.id, {})); route(); } }, T("Zurückziehen")))))))) : null,
      h("section", { class: "panel" }, h("header", null, h("h2", null, T("API-Schlüssel")), h("button", { class: "btn small", onclick: newKey }, svg(ICON.key), T("Neuer Schlüssel"))),
        a.keys.length ? h("table", null, h("tbody", null, a.keys.map(k => h("tr", null,
          h("td", null, h("b", null, k.name), h("div", { class: "dim" }, k.prefix + "...")),
          h("td", { class: "hide-s" }, (k.scopes || []).map(s => h("span", { class: "pill", style: { marginRight: "0.3rem" } }, T(SCOPE_DE[s]) || s))),
          h("td", { class: "dim" }, k.used ? T("zuletzt ") + fmt.date(k.used * 1000) : T("nie benutzt"), k.expires ? h("div", null, T("bis ") + fmt.date(k.expires * 1000)) : null),
          h("td", { class: "right" }, h("button", { class: "btn small danger", onclick: async () => {
            if (await confirmDialog({ title: k.name + " widerrufen?", text: T("Programme mit diesem Schlüssel kommen ab sofort nicht mehr rein."), ok: T("Widerrufen"), danger: true })) { await run(T("Widerrufen"), () => api("DELETE", "/access/keys/" + k.id, {})); route(); }
          } }, T("Widerrufen"))))))) : h("p", { class: "empty" }, T("Noch keine Schlüssel."))));
  }).catch(e => put(body, h("p", { class: "empty" }, e.message)));
}

// ---- command palette ---------------------------------------------------------------------------------

function paletteItems() {
  const items = PAGES.filter(p => (!p.scope || can(p.scope)) && (!p.feature || S.session[p.feature])).map(p => ({ name: T(p.name), hint: T("Seite"), act: () => go(p.path) }));
  for (const s of S.overview?.servers || []) {
    items.push({ name: T("Konsole ") + s.name, hint: "Server", act: () => go("/konsole/" + s.name) });
    if (can("files")) items.push({ name: T("Dateien ") + s.name, hint: "Server", act: () => go("/dateien/" + s.name) });
    if (can("power")) {
      if (s.state === "stopped" || s.state === "crashed") items.push({ name: s.name + T(" starten"), hint: T("Aktion"), act: () => power(s.name, "start") });
      else {
        items.push({ name: s.name + T(" neu starten"), hint: T("Aktion"), act: () => power(s.name, "restart") });
        items.push({ name: s.name + T(" stoppen"), hint: T("Aktion"), act: () => power(s.name, "stop") });
        items.push({ name: s.name + T(" hart beenden"), hint: T("Aktion"), act: () => power(s.name, "kill") });
      }
    }
    for (const p of s.players) if (can("players")) items.push({ name: T("Kicken: ") + p, hint: s.name, act: () => kick({ name: p, server: s.name }) });
  }
  if (can("pack")) items.push({ name: T("Pack aktualisieren"), hint: T("Aktion"), act: () => go("/pack") });
  if (can("power")) items.push({ name: T("Launcher neu laden"), hint: T("Aktion"), act: async () => {
    if (await confirmDialog({ title: T("Launcher neu laden?"), text: T("Die Server laufen weiter, nur der Launcher startet frisch. Die Seite verbindet sich danach neu."), ok: T("Neu laden") })) {
      await run(T("Launcher lädt neu"), () => api("POST", "/launcher/reload", {}));
    }
  } });
  items.push({ name: T("Abmelden"), hint: T("Konto"), act: async () => { await api("POST", "/auth/logout", {}); S.session.user = null; door("login", T("Abgemeldet.")); } });
  return items;
}

function openPalette() {
  if ($("dialog.palette")) return;
  const input = h("input", { type: "text", placeholder: T("Wohin, oder was tun?"), "aria-label": T("Suchen") });
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
    put($("#app"), h("div", { class: "door" }, h("div", { class: "door-card" }, crownMark(), h("h1", null, T("Keine Verbindung")), h("p", null, e.message),
      h("button", { class: "btn", onclick: () => location.reload() }, T("Nochmal versuchen")))));
    return;
  }
  LANG = S.session.language === "de" ? "de" : "en";
  document.documentElement.lang = LANG;
  if (/^#[0-9a-fA-F]{6}$/.test(S.session.accent || "")) document.documentElement.style.setProperty("--gold", S.session.accent);
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
    toast(T("Übersicht nicht geladen"), e.message, true);
    return;
  }
  shell();
  route();
  startStream();
}

start();
