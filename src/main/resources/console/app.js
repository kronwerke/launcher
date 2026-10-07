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

/** A server's colour: its own, or the palette's by its place in the start order. */
const PALETTE = ["var(--gold)", "var(--blue)", "var(--violet)", "var(--teal)"];
function colorOf(name) {
  const all = S.overview?.servers || [];
  const own = all.find(s => s.name === name)?.color;
  if (own) return own;
  return PALETTE[Math.max(0, all.findIndex(s => s.name === name)) % PALETTE.length];
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
  played(ms) {
    const m = Math.round((ms || 0) / 60000);
    if (m < 60) return m + " min";
    const hrs = Math.floor(m / 60);
    return hrs < 100 ? hrs + " h " + (m % 60) + " min" : hrs + " h";
  },
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
  "Spieler einladen": "Invite player",
  "Mods und Plugins": "Mods and plugins",
  "Was auf den Servern läuft, mit Daten von Modrinth und CurseForge. Neues finden, installieren, aktualisieren, entfernen.": "What runs on the servers, with data from Modrinth and CurseForge. Find, install, update and remove.",
  " bringen?": "?",
  "Pack auf dem Server": "Pack on the server",
  " dahinter": " behind",
  "Wirkt nach einem Neustart von ": "Takes effect after a restart of ",
  "Frage Modrinth und CurseForge...": "Asking Modrinth and CurseForge...",
  "Plugins durchsuchen": "Search plugins",
  "Updates": "Updates",
  "Unbekannt": "Unknown",
  "Update: ": "Update: ",
  " aktualisiert": " updated",
  "Dieser Server bekommt Mods aus dem Pack; beim nächsten Pack-Update kann sie wiederkommen. ": "This server gets its mods from the pack; the next pack update may bring it back. ",
  "Die Datei kommt in den Ordner .removed, wirksam nach einem Neustart.": "The file moves to the .removed folder and is gone after a restart.",
  "Neustart nötig": "Restart needed",
  "Nichts passt.": "Nothing matches.",
  "ohne Loader": "no loader",
  ", CurseForge aus": ", CurseForge off",
  "Neu abgleichen": "Match again",
  "Mit Abhängigkeiten": "With dependencies",
  "Modrinth durchsuchen": "Search Modrinth",
  "von ": "by ",
  " Downloads": " downloads",
  " installiert": " installed",
  "Installieren": "Install",
  "Nichts gefunden.": "Nothing found.",
  "Installiert": "Installed",
  "Finden": "Find",
  "Neuer Server": "New server",
  "Zurück": "Back",
  "Einen Moment...": "One moment...",
  "Die Jar wird hochgeladen, sobald der Server angelegt ist. Sie muss sich mit java -jar starten lassen.": "The jar is uploaded once the server exists. It has to start with java -jar.",
  "Jar auswählen": "Choose a jar",
  "Version suchen": "Search versions",
  "Auch Vorabversionen": "Pre-releases too",
  "Server anlegen": "Create server",
  "Kleinbuchstaben, Ziffern, - und _. Wird der Ordner servers/<name>.": "Lower case letters, digits, - and _. Becomes the folder servers/<name>.",
  "Port": "Port",
  "Ein freier Port, den dein Anbieter nach außen gibt (beim Panel eine Allocation). Belegt: ": "A free port your host opens to the outside (an allocation on a panel). Taken: ",
  "Heap des Servers. Der Launcher prüft, ob alles zusammen passt.": "The server's heap. The launcher checks that everything fits together.",
  "Ich akzeptiere die ": "I accept the ",
  "Server angelegt": "Server created",
  "Jar wird hochgeladen": "uploading the jar",
  "startet gleich": "starting shortly",
  "Jar hochgeladen": "Jar uploaded",
  "Software für ": "Software for ",
  "Willkommen": "Welcome",
  "Los geht's": "Let's go",
  "Mein Netzwerk": "My network",
  "Ein paar Dinge, damit die Console nach dir aussieht. Alles lässt sich später ändern.": "A few things so the console looks like yours. Everything can be changed later.",
  "Name, steht oben in der Console": "Name, shown at the top of the console",
  "Sprache": "Language",
  "Farbe": "Colour",
  "Software, Start und Einstellungen dieses Servers.": "Software, start and settings of this server.",
  "Software": "Software",
  "Art": "Kind",
  "Programm": "Program",
  "Datei": "File",
  "aus dem Pack": "from the pack",
  "Software installieren oder wechseln": "Install or change software",
  "Start": "Start",
  "Ordner": "Folder",
  "aus server.properties": "from server.properties",
  "Heap für ": "Heap for ",
  "Zum Beispiel 8G": "For example 8G",
  "Ändern": "Change",
  "JVM-Flags": "JVM flags",
  "JVM-Flags für ": "JVM flags for ",
  "Zusätzlich zu denen des Launchers": "In addition to the launcher's",
  "Mit dem Launcher starten": "Start with the launcher",
  "Nach einem Absturz neu starten": "Restart after a crash",
  "Server entfernen": "Remove server",
  "Stoppt ihn und nimmt ihn aus dem Launcher. Sein Ordner mit Welt und Dateien bleibt.": "Stops it and takes it out of the launcher. Its folder with world and files stays.",
  "Tippe den Namen zur Bestätigung.": "Type the name to confirm.",
  "Name stimmt nicht": "The name does not match",
  "Version": "Version",
  "Details": "Details",
  "Einstellungen ": "Settings ",
  "Plugins, schnell, die übliche Wahl": "Plugins, fast, the usual choice",
  "Paper mit mehr Einstellungen": "Paper with more settings",
  "Paper auf vielen Threads, für große Server": "Paper on many threads, for big servers",
  "Leichte Mods": "Light mods",
  "Große Mods und Modpacks": "Big mods and modpacks",
  "Minecraft, wie Mojang es liefert": "Minecraft as Mojang ships it",
  "Ein Proxy vor mehreren Servern": "A proxy in front of several servers",
  "Jede Jar, die du hochlädst": "Any jar you upload",
  "Eigene Jar": "Custom jar",
  " ist abgestürzt": " crashed",
  "Spieler öffnen": "Open player",
  "letzte ": "last ",
  " Spieler (1)": " player",
  "erledigt": "done",
  "Starte ": "Start ",
  "alle Server": "all servers",
  "Stoppe ": "Stop ",
  "Starte neu: ": "Restart: ",
  "Beende hart: ": "Kill: ",
  "Sage: ": "Say: ",
  "Kicke ": "Kick ",
  "Banne ": "Ban ",
  "Entbanne ": "Unban ",
  "Whitelist: ": "Whitelist: ",
  "Von der Whitelist: ": "Off the whitelist: ",
  "Streamer anlegen: ": "Add streamer: ",
  "Streamer entfernen: ": "Remove streamer: ",
  " auf einen Platz von ": " onto a slot of ",
  "Platz frei: ": "Free slot: ",
  " bei ": " of ",
  "Pack aktualisieren (alle Server starten neu)": "Update pack (every server restarts)",
  "Warte ": "Wait ",
  "gewartet": "waited",
  "Gehe zu ": "Go to ",
  "Keine Seite ": "No page ",
  "da": "there",
  "Öffne ": "Open ",
  "Assistent offen": "wizard open",
  "Keine Befehle erlaubt": "No commands allowed",
  "Kein Server ": "No server ",
  "Es fehlt: Text": "Missing: text",
  "Es fehlt: ": "Missing: ",
  " ist keine Zahl": " is not a number",
  " ist kein Minecraft-Name": " is not a Minecraft name",
  "Suchen, oder einen Befehl: restart mining && say Hallo": "Search, or a command: restart mining && say Hello",
  "Suchen oder Befehl": "Search or command",
  "Tab ergänzt, Enter führt aus, && verkettet, ; macht trotz Fehler weiter. help zeigt alle Befehle.": "Tab completes, Enter runs, && chains, ; carries on after errors. help lists every command.",
  "unvollständig": "incomplete",
  "Enter führt aus": "Enter runs",
  "Ausführen: ": "Run: ",
  "Fehler": "Error",
  "Zahl": "Number",
  "Code vom angemeldeten Gerät": "Code from the signed in device",
  "Passkey für dieses Gerät anlegen": "Create a passkey for this device",
  "Zurück zur Anmeldung": "Back to sign in",
  "Gerät koppeln": "Pair a device",
  "Auf einem Gerät, auf dem du angemeldet bist: Zugang, dann Gerät koppeln. Den Code hier eingeben, dann bekommt dieses Gerät einen eigenen Passkey.": "On a device where you are signed in: Access, then Pair a device. Enter the code here and this device gets a passkey of its own.",
  "Kein Passkey auf diesem Gerät? Wähl im Fenster das Handy (QR-Code), oder ": "No passkey on this device? Pick your phone in the window (QR code), or ",
  "koppel es mit einem Code": "pair it with a code",
  "ohne Platz": "no place",
  "Über Discord verlinkt": "Linked on Discord",
  "neu": "new",
  "hat Plätze": "has slots",
  "Plätze geben": "Give slots",
  "Kein Code": "No code",
  "Auf dem anderen Gerät ": "On the other device open ",
  " öffnen, unten auf koppel es mit einem Code tippen und diesen Code eingeben:": ", tap pair it with a code at the bottom and enter this code:",
  "Gilt einmal, noch ": "Works once, for another ",
  "alle": "all",
  " ist da": " joined",
  " ist weg": " left",
  ", weiter nach ": ", moving on to ",
  "Welche Server zusammengehören": "Which servers belong together",
  "Mod am Bus": "Mod on the bus",
  "über die Konsole": "through the console",
  "ohne RCON": "no RCON",
  "Chat aller Server": "Chat of every server",
  "Server filtern": "Filter by server",
  "Netzwerk": "Network",
  "Welche Server Chat, Beitritte, Tabliste und Listen teilen, und was gerade gesagt wird.": "Which servers share chat, joins, the tab list and lists, and what is being said right now.",
  "Chat": "Chat",
  "Karte": "Map",
  "Linien verbinden, was geteilt wird": "Lines connect what is shared",
  "Was geteilt wird": "What is shared",
  "Wirkt sofort. Tabliste, Umkreis und Spielerdaten brauchen eine Mod am Bus.": "Applies at once. Tab list, radius and player data need a mod on the bus.",
  "Nachricht an die Spieler": "Message to the players",
  "An welchen Server": "To which server",
  "Gesendet": "Sent",
  "Alle Server": "All servers",
  "Noch nichts gesagt. Chat und Beitritte erscheinen hier, sobald sie passieren.": "Nothing said yet. Chat and joins show up here as they happen.",
  "Beitritte": "Joins",
  "Tabliste": "Tab list",
  "Listen": "Lists",
  "Spielerdaten": "Player data",
  "Anbindung": "Connection",
  "allein": "alone",
  "Netzwerk von ": "Network of ",
  "Wirkt, sobald eine Mod am Bus ist": "Works once a mod is on the bus",
  "Umkreis in Blöcken": "Radius in blocks",
  "Blöcke": "blocks",
  "Kein Minecraft-Server da.": "No Minecraft server here.",
  "Port des Busses": "Bus port",
  "Bus offen": "Bus open",
  "Bus aus": "Bus off",
  "Nur im Container erreichbar, Mods melden sich mit dem Schlüssel aus bus.key.": "Reachable only inside the container; mods sign in with the key from bus.key.",
  "Ohne Bus gehen Chat und Beitritte über die Konsolen der Server.": "Without the bus, chat and joins travel through the servers' consoles.",
  "leer: keiner": "empty: none",
  "Gleiches Pack wie ": "Same pack as ",
  ": Mods und Configs werden verlinkt, ein Update gilt für beide": ": mods and configs are linked, one update covers both",
  "Server mit dem gleichen Netzwerk teilen Chat, Beitritte und Listen. Was genau, stellst du unter Netzwerk ein.": "Servers with the same network share chat, joins and lists. What exactly is set on the Network page.",
  "Voice-Port": "Voice port",
  "UDP-Port für Simple Voice Chat, falls die Mod drin ist.": "UDP port for Simple Voice Chat, if the mod is in.",
  "An alle Server: ": "To every server: ",
  "Umkreis": "Radius",
  "Auch in den gepackten Logs der letzten Tage": "Also in the packed logs of earlier days",
  "Alte Logs": "Old logs",
  " gespielt": " played",
  "Wonach suchen? Mindestens zwei Zeichen": "What to look for? At least two characters",
  "Suchtext": "Search text",
  "Suche...": "Searching...",
  " Treffer in ": " hits in ",
  " Dateien": " files",
  ", die neuesten zuerst": ", newest first",
  "In den Logs suchen": "Search the logs",
  "Spielzeit": "Playtime",
  " insgesamt": " in all",
  ", gerade seit ": ", right now since ",
  "Seit der Launcher mitzählt noch keine Runde.": "No session since the launcher started counting.",
  "alle ": "every ",
  " Stunden": " hours",
  "täglich": "daily",
  "werktags": "weekdays",
  " um ": " at ",
  "Nächtlicher Neustart": "Nightly restart",
  "Zu einer Uhrzeit": "At a time",
  "Alle paar Stunden": "Every few hours",
  "Wochentage": "Weekdays",
  "Der Befehl ohne / oder die Nachricht an die Spieler.": "The command without / or the message to the players.",
  "Vorwarnung": "Warnings",
  "Minuten vorher, mit Komma. Die Spieler sehen: Neustart in 5 Minuten.": "Minutes before, separated by commas. Players see: Restart in 5 minutes.",
  "Uhrzeit": "Time",
  "Tage": "Days",
  "Abstand": "Interval",
  "Ab Mitternacht gezählt: 6 h heißt 0, 6, 12 und 18 Uhr.": "Counted from midnight: 6 h means 0, 6, 12 and 18 o'clock.",
  "Aufgabe ändern": "Change task",
  "Neue Aufgabe": "New task",
  "Wo": "Where",
  "Automatik": "Automation",
  "Was nach der Uhr passiert, und wer Bescheid bekommt, wenn etwas schiefgeht.": "What happens by the clock, and who hears about it when something goes wrong.",
  "Zeitplan": "Schedule",
  "Meldungen": "Alerts",
  "An einen Discord-Webhook oder jede andere https-Adresse.": "To a Discord webhook or any other https address.",
  "Zeitzone ": "Time zone ",
  ", jetzt ": ", now ",
  "Aufgabe": "Task",
  "Nächstes Mal": "Next",
  "An": "On",
  " auf allen Servern": " on every server",
  ", warnt ": ", warns ",
  "An oder aus": "On or off",
  " jetzt ausführen?": " now?",
  " wäre das nächste Mal.": " would be the next time.",
  "Ausführen": "Run",
  "Läuft": "Running",
  "Jetzt": "Now",
  "Noch nichts geplant.": "Nothing planned yet.",
  "Neustart jede Nacht um 5": "Restart every night at 5",
  "Backup alle 6 Stunden": "Backup every 6 hours",
  "gesetzt (": "set (",
  "), leer lassen zum Behalten": "), leave empty to keep it",
  "Grenze in ms": "Limit in ms",
  "Die Adresse bleibt im Launcher und wird nie angezeigt.": "The address stays in the launcher and is never shown.",
  "Tick-Zeit-Grenze": "Tick time limit",
  "ms im Schnitt über eine Minute (50 ms ist die Grenze für volle 20 TPS)": "ms on average over a minute (50 ms is the limit for a full 20 TPS)",
  "Test gesendet": "Test sent",
  "Test senden": "Send a test",
  "Webhook entfernen?": "Remove the webhook?",
  "Webhook entfernen": "Remove webhook",
  "Wartungsarbeiten, bis gleich.": "Maintenance, back soon.",
  "Nachricht an alle, die rausmüssen": "Message to everyone who has to leave",
  "Wartung für ": "Maintenance for ",
  "Alle außer Operatoren werden sofort rausgeschickt und kommen nicht mehr rein.": "Everyone but operators is sent away at once and cannot join.",
  "Wartung an": "Maintenance on",
  "Wartung aus": "Maintenance off",
  "Rausgeschickt": "Sent away",
  "Wartung": "Maintenance",
  "Nur Operatoren kommen rein, alle anderen sehen deine Nachricht.": "Only operators get in; everyone else sees your message.",
  "Wartung läuft": "Maintenance is on",
  "Wartung einschalten": "Turn on maintenance",
  "Backup jetzt": "Back up now",
  "Backup läuft...": "Backing up...",
  "Laden": "Download",
  "Welt von ": "Bring back the world of ",
  " zurückholen?": "?",
  "Der Server stoppt, die jetzige Welt wird beiseitegelegt (nicht gelöscht), das Backup wird ausgepackt und der Server startet wieder.": "The server stops, the current world is moved aside (not deleted), the backup is unpacked and the server starts again.",
  "Zurückholen": "Restore",
  "Zurückgeholt": "Restored",
  "Alte Welt liegt in": "The old world is in",
  "Backup löschen": "Delete backup",
  "Backup löschen?": "Delete the backup?",
  "Gelöscht": "Deleted",
  "Noch kein Backup. Mit dem Zeitplan geht es auch jede Nacht.": "No backup yet. The schedule can make one every night.",
  "Backup gestartet": "Backup started",
  "Die Welt als Zip, die neuesten bleiben (backup.keep).": "The world as a zip; the newest stay (backup.keep).",
  "Dauer": "Duration",
  "Misst ": "Measuring for ",
  " Sekunden, danach erscheint hier der Link.": " seconds; the link shows up here after that.",
  "Fertig: ": "Done: ",
  "Profiler starten": "Start the profiler",
  "Profiler (spark)": "Profiler (spark)",
  "Misst, was den Server bremst, und liefert einen Bericht als Link.": "Measures what slows the server down and gives a report as a link.",
  "Dimension": "Dimension",
  "Radius in Blöcken": "Radius in blocks",
  "Welt vorgenerieren (Chunky)": "Pregenerate the world (Chunky)",
  "Erzeugt Chunks im Voraus, damit Spieler später nicht warten. Kostet CPU, am besten wenn wenig los ist.": "Generates chunks ahead so players do not wait later. Costs CPU; best when few are online.",
  "Blöcke um den Spawn": "blocks around spawn",
  "Pause": "Pause",
  "Stand": "Progress",
  "Werkzeuge": "Tools",
  "Einstellung suchen": "Find a setting",
  "  Änderungen wirken beim nächsten Start": "  Changes apply on the next start",
  "Backup: ": "Backup: ",
  "alle Minecraft-Server": "every Minecraft server",
  "Wartung an: ": "Maintenance on: ",
  "rausgeschickt: ": "sent away: ",
  "an": "on",
  "Wartung aus: ": "Maintenance off: ",
  "aus": "off",
  "Backup": "Backup",
  "Mo": "Mon",
  "Di": "Tue",
  "Mi": "Wed",
  "Do": "Thu",
  "Fr": "Fri",
  "Sa": "Sat",
  "So": "Sun",
  "Absturz": "Crash",
  "Aufgegeben (drei Abstürze)": "Gave up (three crashes)",
  "Tick-Zeit dauerhaft hoch": "Tick time stays high",
  "Backup fertig": "Backup done",
  "Backup fehlgeschlagen": "Backup failed",
  "Server läuft": "Server running",
  "Server gestoppt": "Server stopped",
  "Text in der Serverliste": "Text in the server list",
  "Höchstens so viele Spieler": "At most this many players",
  "Sichtweite in Chunks": "View distance in chunks",
  "Simulationsweite in Chunks": "Simulation distance in chunks",
  "peaceful, easy, normal oder hard": "peaceful, easy, normal or hard",
  "Spielmodus neuer Spieler": "Game mode of new players",
  "Spieler können sich schaden": "Players can hurt each other",
  "Nur Spieler auf der Whitelist": "Only players on the whitelist",
  "Wer von der Whitelist fliegt, fliegt raus": "Whoever leaves the whitelist is kicked",
  "Radius um den Spawn, den nur Operatoren ändern": "Radius around spawn only operators change",
  "Konten bei Mojang prüfen": "Check accounts with Mojang",
  "Fliegen nicht als Cheat werten": "Do not treat flying as cheating",
  "Nach so vielen ms hängt der Server als abgestürzt (-1: nie)": "After this many ms the server counts as hung (-1: never)",
  "Seed einer neuen Welt": "Seed of a new world",
  "Ordner der Welt": "Folder of the world",
  "Hardcore": "Hardcore",
  "Nether erlaubt": "Nether allowed",
  "Monster erscheinen": "Monsters spawn",
  "Befehlsblöcke": "Command blocks",
  "Minuten bis zum Kick wegen Abwesenheit (0: nie)": "Minutes until an idle kick (0: never)",
  "Ab so vielen Bytes komprimieren": "Compress from this many bytes",
  "Vom Launcher gesetzt (port in seiner Datei)": "Set by the launcher (port in its file)",
  "Vom Launcher gesetzt": "Set by the launcher",
  "Vom Launcher gesetzt (transfers)": "Set by the launcher (transfers)"
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
  if (S.session?.csrf) headers["X-Csrf-Token"] = S.session.csrf;
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
  return h("img", { class: "logo", src: "/brand/logo", alt: "" });
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
    const code = h("input", { type: "text", autocomplete: "one-time-code", placeholder: "XXXX-XXXX", required: true, spellcheck: "false" });
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
  } else if (kind === "pair") {
    const code = h("input", { type: "text", autocomplete: "one-time-code", placeholder: "XXXX-XXXX", required: true, spellcheck: "false", style: { fontSize: "1.4rem", letterSpacing: "0.08em", textAlign: "center" } });
    const form = h("form", { onsubmit: async e => {
      e.preventDefault();
      err.textContent = "";
      try {
        const o = await api("POST", "/auth/register/options", { purpose: "pair", secret: code.value });
        const cred = await createPasskey(o.options);
        S.session = await api("POST", "/auth/register", { id: o.id, credential: cred, label: deviceLabel() });
        history.replaceState(null, "", "/");
        start();
      } catch (x) { err.textContent = passkeyError(x); }
    } },
      h("label", { class: "field" }, h("span", null, T("Code vom angemeldeten Gerät")), code),
      h("button", { class: "btn primary", type: "submit" }, svg(ICON.key), T("Passkey für dieses Gerät anlegen")), err,
      h("p", { class: "note" }, h("a", { href: "/" }, T("Zurück zur Anmeldung"))));
    card = [h("h1", null, T("Gerät koppeln")), h("p", null, T("Auf einem Gerät, auf dem du angemeldet bist: Zugang, dann Gerät koppeln. Den Code hier eingeben, dann bekommt dieses Gerät einen eigenen Passkey.")), form];
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
      h("p", { class: "note" }, T("Kein Passkey auf diesem Gerät? Wähl im Fenster das Handy (QR-Code), oder "), h("a", { href: "/pair" }, T("koppel es mit einem Code")), "."),
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
  { path: "/pack", name: "Mods und Plugins", key: "m", draw: pagePack },
  { path: "/dateien", name: "Dateien", key: "d", draw: pageFiles, scope: "files" },
  { path: "/netzwerk", name: "Netzwerk", key: "n", draw: pageNetwork },
  { path: "/ressourcen", name: "Ressourcen", key: "r", draw: pageResources },
  { path: "/automatik", name: "Automatik", key: "a", draw: pageAutomation },
  { path: "/server", name: "Server", key: "x", draw: pageServer, hidden: true },
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
    PAGES.filter(p => !p.hidden && (!p.scope || can(p.scope)) && (!p.feature || S.session[p.feature])).map(p => h("a", { href: p.path, "data-link": true }, T(p.name), h("kbd", { title: T("g dann ") + p.key }, "g " + p.key))));
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
  const href = a.getAttribute("href");
  if (href.includes("?")) { history.pushState(null, "", href); route(); } else go(href);
});
window.addEventListener("popstate", () => { if (S.session?.user) route(); });

function header(title, text, ...actions) {
  return h("div", { class: "head" }, h("div", null, h("h1", null, title), text ? h("p", null, text) : null), h("div", { class: "actions" }, actions));
}

// ---- live ---------------------------------------------------------------------------------

const listeners = { line: new Set(), event: new Set(), overview: new Set(), chat: new Set() };
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
    if (d.kind === "state") {
      refreshOverview();
      if (/^crashed/.test(d.text || "")) toast(d.server + T(" ist abgestürzt"), (d.text || "").replace(/^crashed:?\s*/, ""), true);
    }
  });
  es.addEventListener("chat", e => {
    const d = JSON.parse(e.data);
    for (const f of listeners.chat) f(d);
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
  const label = span >= 3540 ? T("letzte Stunde") : T("letzte ") + Math.round(span / 60) + " min";
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
  canvas._trace = { pts, x, y, w, hgt, c };
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
    o.pack.version ? h("a", { class: "btn", href: "/pack", "data-link": true }, "Pack " + o.pack.version) : null,
    can("config") && can("power") ? h("button", { class: "btn primary", onclick: newServerWizard }, T("Neuer Server")) : null),
    h("div", { class: "grid-2" }, cards, side));

  const drawCards = () => {
    put(cards, ...S.overview.servers.map(s => {
      const m = s.last?.mspt ?? -1;
      const canvas = h("canvas", { class: "trace", "aria-label": T("Tickzeit der letzten Stunde") });
      const tip = h("div", { class: "tip", hidden: true });
      canvas.addEventListener("mousemove", e => {
        const tr = canvas._trace;
        if (!tr || !tr.pts.length) return;
        const rect = canvas.getBoundingClientRect(), mx = e.clientX - rect.left;
        let best = tr.pts[0];
        for (const p of tr.pts) if (Math.abs(tr.x(p.t) - mx) < Math.abs(tr.x(best.t) - mx)) best = p;
        tip.hidden = false;
        put(tip, h("b", null, fmt.num(best.mspt) + " ms"), " " + fmt.clock(best.t * 1000) + (best.players >= 0 ? ", " + best.players + T(" Spieler") : "") + (best.cpu >= 0 ? ", CPU " + fmt.num(best.cpu) + " %" : ""));
        tip.style.left = Math.min(rect.width - 230, Math.max(0, tr.x(best.t) - 60)) + "px";
        tip.style.top = Math.max(0, tr.y(best.mspt) - 34) + "px";
      });
      canvas.addEventListener("mouseleave", () => { tip.hidden = true; });
      const card = h("section", { class: "panel pulse", style: { "--c": colorOf(s.name) }, "data-server": s.name },
        h("div", { class: "pulse-top" },
          h("div", null,
            h("div", { class: "pulse-name" },
              h("h2", null, h("a", { href: "/server/" + s.name, "data-link": true }, s.name)),
              h("span", { class: "state", "data-s": s.state }, (T(STATE_DE[s.state]) || s.state) + T(" seit ") + fmt.since(s.since))),
            h("div", { class: "pulse-meta" }, [s.detail, s.port ? "Port " + s.port : null, s.role && s.role !== s.name ? s.role : null].filter(Boolean).join(", "))),
          h("div", { class: "mspt", "data-health": s.state === "running" ? health(m) : "" },
            h("b", null, s.state === "running" && m >= 0 ? fmt.num(m) : "-", h("small", null, "ms")),
            h("span", null, s.state === "running" && s.last?.tps >= 0 ? fmt.num(s.last.tps) + " TPS" : T("pro Tick")))),
        h("div", { class: "trace-wrap" }, canvas, tip),
        h("div", { class: "pulse-foot" },
          h("span", null, "CPU ", h("b", null, s.last?.cpu >= 0 ? fmt.num(s.last.cpu) + " %" : "?"), " ", spark(S.metrics[s.name] || [], "cpu", resolveColor(colorOf(s.name)))),
          h("span", null, "RAM ", h("b", null, fmt.bytes(s.last?.rss)), T(" von "), s.memory || "?"),
          h("span", null, h("b", null, s.players.length), s.players.length === 1 ? (LANG === "de" ? " Spieler" : " player") : T(" Spieler")),
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
    const m = /^(.*?<)([A-Za-z0-9_]{3,16})(>.*)$/.exec(text);
    if (m && !cls) d.append(m[1], h("a", { href: "/spieler/alle?q=" + m[2], class: "who", "data-link": true, title: T("Spieler öffnen") }, m[2]), m[3]);
    else d.append(text);
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
    stick = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
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
      h("button", { class: "btn small quiet", title: T("Auch in den gepackten Logs der letzten Tage"), onclick: () => logSearch(which === "alle" ? S.overview.servers[0].name : which, search.value) }, T("Alte Logs")),
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
  let tab = rest || "alle", q = new URLSearchParams(location.search).get("q") || "", data = null;
  const body = h("div", { class: "stack" });
  const search = h("input", { type: "search", value: q, placeholder: T("Spieler suchen"), "aria-label": T("Spieler suchen") });
  q = q.toLowerCase();
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
        h("td", null, h("span", { class: "player" }, head(p.name), h("span", null, h("b", null, p.name), p.discord ? h("div", { class: "dim small" }, "Discord: " + (p.discord.name || p.discord.id)) : null))),
        h("td", null, h("span", { class: "actions" }, pills(p))),
        h("td", { class: "hide-s muted" }, roleOf(p)),
        h("td", { class: "hide-s right dim" }, p.online ? T("jetzt") : p.seen ? fmt.date(p.seen) : "",
          data.playtime?.[p.name.toLowerCase()] ? h("div", { class: "small" }, fmt.played(data.playtime[p.name.toLowerCase()]) + T(" gespielt")) : null)))))));
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
      playtimeSection(p.name),
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
    const inRoster = n => r.streamers.some(s => s.name.toLowerCase() === n.toLowerCase());
    const linked = (data.linked || []).filter(l => !q || (l.player + " " + (l.discord_name || "")).toLowerCase().includes(q));
    const kindPill = k => k === "streamer" ? h("span", { class: "pill c", style: { "--c": "var(--violet)" } }, "Streamer") : k === "season1" ? h("span", { class: "pill c", style: { "--c": "var(--teal)" } }, "Season 1") : h("span", { class: "pill" }, T("ohne Platz"));
    const fresh = at => at && Date.now() - new Date(at).getTime() < 48 * 3600 * 1000;
    put(body,
      h("div", { class: "actions" }, h("span", { class: "muted" }, T("Standard: ") + r.defaultSlots + T(" Plätze pro Streamer.")), h("span", { style: { flex: "1" } }), add),
      linked.length ? h("details", { class: "panel linked", open: linked.some(l => fresh(l.at)) || null },
        h("summary", null, T("Über Discord verlinkt"), h("span", { class: "dim" }, " " + linked.length), linked.some(l => fresh(l.at)) ? h("span", { class: "pill warn", style: { marginLeft: "0.6rem" } }, T("neu")) : null),
        h("table", null, h("tbody", null, linked.map(l => h("tr", null,
          h("td", null, h("span", { class: "player" }, head(l.player), h("span", null, h("b", null, l.player), h("div", { class: "dim small" }, "Discord: " + (l.discord_name || l.discord_id))))),
          h("td", null, kindPill(l.kind), fresh(l.at) ? h("span", { class: "pill warn", style: { marginLeft: "0.4rem" } }, T("neu")) : null),
          h("td", { class: "dim hide-s" }, l.at ? fmt.date(l.at) : ""),
          h("td", { class: "right" }, inRoster(l.player) ? h("span", { class: "dim" }, T("hat Plätze")) : can("command") ? h("button", { class: "btn small", onclick: async () => {
            const n = await confirmDialog({ title: l.player + T(" als Streamer anlegen?"), text: T("Bekommt einen eigenen Platz und so viele Plätze für Zuschauer. Leer heißt Standard."), ok: T("Anlegen"), input: { label: T("Plätze"), value: "" } });
            if (n != null) cmd(l.player + T(" angelegt"), "kw admin grant " + l.player + (n.trim() ? " " + parseInt(n, 10) : ""));
          } }, T("Plätze geben")) : null)))))) : null,
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
    try { data.playtime = Object.fromEntries((await api("GET", "/sessions")).map(x => [x.name.toLowerCase(), x.total])); } catch { data.playtime = {}; }
    if (tab === "streamer" && !data.roster) tab = "alle";
    drawSeg();
    draw();
  };
  put(body, h("p", { class: "dim" }, T("Lade...")));
  load();
  let last = 0;
  on("overview", () => { if (Date.now() - last > 20000 && !$("dialog[open]")) { last = Date.now(); load(); } });
}

/** Searches a server's logs, the packed ones of earlier days included. */
function logSearch(server, initial) {
  const q = h("input", { type: "text", value: initial || "", placeholder: T("Wonach suchen? Mindestens zwei Zeichen"), "aria-label": T("Suchtext") });
  const which = h("select", { "aria-label": "Server" }, S.overview.servers.filter(x => x.type !== "command").map(x => h("option", { value: x.name, selected: x.name === server || null }, x.name)));
  const out = h("div", { class: "log logsearch" });
  const info = h("p", { class: "dim small" });
  const go = async () => {
    if (q.value.trim().length < 2) return;
    put(out, h("div", { class: "dim" }, T("Suche...")));
    try {
      const r = await api("GET", "/servers/" + which.value + "/logsearch?q=" + encodeURIComponent(q.value.trim()));
      info.textContent = r.hits.length + T(" Treffer in ") + r.files + T(" Dateien") + (r.more ? T(", die neuesten zuerst") : "");
      let file = "";
      put(out, r.hits.length ? r.hits.flatMap(x => {
        const head = x.file !== file ? h("div", { class: "file-sep" }, x.file) : null;
        file = x.file;
        return [head, h("div", { class: lineClass(x.text) }, h("span", { class: "dim" }, x.line + "  "), x.text)];
      }) : h("div", { class: "dim" }, T("Nichts gefunden.")));
    } catch (e) { put(out, h("div", { class: "error" }, e.message)); }
  };
  q.addEventListener("keydown", e => { if (e.key === "Enter") go(); });
  const d = h("dialog", { class: "wide" }, h("div", { class: "wiz" },
    h("header", null, h("h2", null, T("In den Logs suchen")), h("button", { class: "btn quiet small", onclick: () => d.close(), "aria-label": T("Schließen") }, "×")),
    h("div", { class: "actions" }, which, q, h("button", { class: "btn primary", onclick: go }, T("Suchen"))), info, out));
  d.addEventListener("close", () => d.remove());
  document.body.append(d);
  d.showModal();
  q.focus();
  if (q.value) go();
}

/** Playtime and the last sessions of a player, filled in when it arrives. */
function playtimeSection(name) {
  const sec = h("section", { class: "drawer-sec" }, h("h3", null, T("Spielzeit")), h("p", { class: "dim" }, T("Lade...")));
  api("GET", "/sessions/" + encodeURIComponent(name)).then(d => {
    const list = (d.sessions || []).slice(-8).reverse();
    put(sec, h("h3", null, T("Spielzeit")),
      h("p", null, h("b", null, fmt.played(d.total)), T(" insgesamt"), d.online ? T(", gerade seit ") + fmt.clock(d.since) + T(" auf ") + d.online : ""),
      list.length ? h("ol", { class: "sessions" }, list.map(x => h("li", null,
        h("span", { class: "pill c", style: { "--c": colorOf(x.server) } }, x.server),
        h("span", { class: "num" }, fmt.date(x.from)),
        h("span", { class: "dim num" }, fmt.played(x.to - x.from))))) : h("p", { class: "dim" }, T("Seit der Launcher mitzählt noch keine Runde.")));
  }).catch(() => put(sec, h("h3", null, T("Spielzeit")), h("p", { class: "dim" }, "-")));
  return sec;
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

function pagePack(main, rest) {
  const servers = S.overview.servers.filter(s => s.type !== "command").map(s => s.name);
  const parts = rest.split("/").filter(Boolean);
  let server = servers.includes(parts[0]) ? parts[0] : servers[0];
  let tab = parts[1] === "finden" ? "finden" : "installiert";
  const packBox = h("div", { class: "stack" });
  const body = h("div", { class: "stack" });
  const seg = h("div", { class: "seg" });
  const tabs = h("div", { class: "seg" });
  main.append(header(T("Mods und Plugins"), T("Was auf den Servern läuft, mit Daten von Modrinth und CurseForge. Neues finden, installieren, aktualisieren, entfernen.")),
    packBox, h("div", { class: "actions", style: { marginBottom: "1rem" } }, seg, tabs), body);
  const nav = () => {
    put(seg, servers.map(n => h("button", { type: "button", "aria-pressed": String(n === server), style: { "--c": colorOf(n) }, onclick: () => { server = n; sync(); } }, h("span", { class: "dot" }), n)));
    put(tabs, [["installiert", "Installiert"], ["finden", "Finden"]].map(([k, n]) => h("button", { type: "button", "aria-pressed": String(k === tab), onclick: () => { tab = k; sync(); } }, T(n))));
  };
  const sync = () => { history.replaceState(null, "", "/pack/" + server + (tab === "finden" ? "/finden" : "")); nav(); draw(); };
  let data = null;

  // the pack, when there is one
  const loadPack = async fresh => {
    const p = await api("GET", "/pack" + (fresh ? "?fresh=1" : ""));
    if (!p.url) return put(packBox);
    const local = p.local?.version, remote = p.remote?.version;
    const behind = remote && local && newer(remote, local);
    const sections = (p.remote?.changelog || "").split(/^## /m).slice(1).map(s => {
      const [head, ...r] = s.split("\n");
      return { v: head.trim(), text: r.join("\n").trim() };
    }).filter(s => local && newer(s.v, local));
    const players = S.overview.servers.reduce((n, s) => n + s.players.length, 0);
    const update = async () => {
      const ok = await confirmDialog({
        title: T("Pack auf ") + (remote || T("den neuesten Stand")) + T(" bringen?"),
        text: T("Alle Server stoppen, packwiz holt Mods und Configs, dann starten sie wieder. ") + (players ? players + T(" Spieler fliegen dabei raus.") : T("Gerade ist niemand online.")),
        ok: T("Aktualisieren"), danger: players > 0,
      });
      if (ok) { await run(T("Pack-Update läuft"), () => api("POST", "/pack/update", {})); go("/konsole/" + S.overview.servers[0].name); }
    };
    put(packBox,
      h("section", { class: "panel" }, h("div", { class: "body", style: { display: "flex", flexWrap: "wrap", gap: "1rem 2.5rem", alignItems: "center" } },
        h("div", null, h("div", { class: "muted" }, T("Pack auf dem Server")), h("b", { style: { fontSize: "1.5rem" } }, local || "?")),
        h("div", null, h("div", { class: "muted" }, T("Im Repository")), h("b", { style: { fontSize: "1.5rem" } }, remote || (p.remote?.error ? T("nicht erreichbar") : "?"))),
        h("div", null, h("div", { class: "muted" }, "NeoForge"), h("b", null, p.local?.neoforge || "?")),
        h("span", { style: { flex: "1" } }),
        p.updating ? h("span", { class: "pill warn" }, T("Update läuft")) : behind ? h("span", { class: "pill warn" }, sections.length + (sections.length === 1 ? T(" Version") : T(" Versionen")) + T(" dahinter")) : h("span", { class: "pill ok" }, T("Aktuell")),
        h("button", { class: "btn quiet", onclick: () => loadPack(true) }, T("Neu prüfen")),
        can("pack") ? h("button", { class: "btn " + (behind ? "primary" : ""), onclick: update, disabled: p.updating || null }, T("Pack aktualisieren")) : null)),
      sections.length ? h("details", { class: "panel changes" }, h("summary", null, T("Was dazukommt"), h("span", { class: "dim" }, " " + sections.map(s => s.v).join(", "))),
        h("div", { class: "body stack" }, sections.map(s => h("div", null, h("b", null, s.v), h("div", { class: "muted", style: { whiteSpace: "pre-wrap", marginTop: "0.3rem" } }, s.text))))) : null);
  };

  const icon = (url, title) => url ? h("img", { class: "modicon", src: url, alt: "", loading: "lazy" }) : h("span", { class: "modicon blank", "aria-hidden": "true" }, (title || "?").slice(0, 1).toUpperCase());
  const source = m => m.source === "modrinth" ? h("span", { class: "pill src mr" }, "Modrinth") : m.source === "curseforge" ? h("span", { class: "pill src cf" }, "CurseForge") : h("span", { class: "pill" }, T("unbekannt"));
  const restartHint = r => r?.restart ? T("Wirkt nach einem Neustart von ") + server + "." : "";

  const draw = () => tab === "finden" ? drawFind() : drawInstalled();

  let q = "", filter = "alle";
  const drawInstalled = async (fresh) => {
    if (!data || data.server !== server || fresh) {
      put(body, h("section", { class: "panel" }, h("p", { class: "empty" }, T("Frage Modrinth und CurseForge..."))));
      try { data = { server, ...(await api("GET", "/servers/" + server + "/mods" + (fresh ? "?fresh=1" : ""))) }; } catch (e) { return put(body, h("p", { class: "empty" }, e.message)); }
    }
    const mods = data.mods || [];
    const updates = mods.filter(m => m.latest);
    const unknown = mods.filter(m => !m.source);
    const search = h("input", { type: "search", value: q, placeholder: (data.kind === "plugin" ? T("Plugins durchsuchen") : T("Mods durchsuchen")) + " (" + mods.length + ")" });
    search.addEventListener("input", () => { q = search.value.toLowerCase(); drawList(); });
    const fseg = h("div", { class: "seg" }, [["alle", T("Alle"), mods.length], ["updates", T("Updates"), updates.length], ["unbekannt", T("Unbekannt"), unknown.length]].map(([k, n, c]) =>
      h("button", { type: "button", "aria-pressed": String(k === filter), onclick: () => { filter = k; drawInstalled(); } }, n, h("span", { class: "dim", style: { marginLeft: "0.35rem" } }, String(c)))));
    const list = h("div", { class: "modlist" });
    const drawList = () => {
      const shown = mods.filter(m => filter === "alle" || (filter === "updates" ? m.latest : !m.source))
        .filter(m => !q || [m.title, m.name, m.file].some(x => (x || "").toLowerCase().includes(q)));
      put(list, shown.length ? shown.map(m => h("div", { class: "mod" },
        icon(m.icon, m.title || m.name || m.file),
        h("div", { class: "mod-main" },
          h("div", { class: "mod-title" }, m.url ? h("a", { href: m.url, target: "_blank", rel: "noopener" }, m.title || m.name || m.file) : h("b", null, m.title || m.name || m.file),
            source(m), m.latest ? h("span", { class: "pill warn" }, T("Update: ") + m.latest) : null),
          h("div", { class: "dim mod-sub" }, [m.version || m.declared, m.file].filter(Boolean).join(", ") + "" + ", " + fmt.bytes(m.size)),
          m.summary ? h("div", { class: "muted mod-sum" }, m.summary) : null),
        h("div", { class: "actions mod-act" },
          m.latest && m.source === "modrinth" && can("pack") ? h("button", { class: "btn small primary", onclick: () => install(m.project, m.file, (m.title || m.file) + T(" aktualisiert")) }, T("Aktualisieren")) : null,
          can("pack") ? h("button", { class: "btn small quiet", "aria-label": T("Entfernen"), onclick: async () => {
            if (!(await confirmDialog({ title: (m.title || m.file) + T(" entfernen?"), text: (data.managed ? T("Dieser Server bekommt Mods aus dem Pack; beim nächsten Pack-Update kann sie wiederkommen. ") : "") + T("Die Datei kommt in den Ordner .removed, wirksam nach einem Neustart."), ok: T("Entfernen"), danger: true }))) return;
            const r = await run(T("Entfernt"), () => api("DELETE", "/servers/" + server + "/mods?file=" + encodeURIComponent(m.file)));
            if (r?.restart) toast(T("Neustart nötig"), restartHint(r));
            drawInstalled(true);
          } }, T("Entfernen")) : null))) : h("p", { class: "empty" }, T("Nichts passt.")));
    };
    drawList();
    put(body,
      data.error ? h("p", { class: "muted" }, data.error) : null,
      h("div", { class: "actions" }, fseg, search, h("span", { style: { flex: "1" } }),
        h("span", { class: "dim" }, (data.loader || T("ohne Loader")) + " " + (data.minecraft || "") + (data.curseforge ? "" : T(", CurseForge aus"))),
        h("button", { class: "btn quiet small", onclick: () => drawInstalled(true) }, T("Neu abgleichen"))),
      h("section", { class: "panel" }, list));
  };

  const install = async (project, replace, title) => {
    const r = await run(title, () => api("POST", "/servers/" + server + "/mods", { project, replace }));
    if (r?.files?.length > 1) toast(T("Mit Abhängigkeiten"), r.files.join("\n"));
    if (r?.restart) toast(T("Neustart nötig"), restartHint(r));
    data = null;
  };

  let fq = "", results = null, timer = null;
  const drawFind = () => {
    const input = h("input", { type: "search", value: fq, placeholder: T("Modrinth durchsuchen"), autofocus: true, style: { flex: "1", minWidth: "14rem" } });
    const grid = h("div", { class: "findgrid" });
    const show = res => put(grid, (res?.hits || []).length ? res.hits.map(p => h("article", { class: "panel found" },
      h("div", { class: "found-top" }, icon(p.icon_url, p.title), h("div", null, h("a", { href: "https://modrinth.com/" + p.project_type + "/" + p.slug, target: "_blank", rel: "noopener" }, h("b", null, p.title)),
        h("div", { class: "dim" }, T("von ") + p.author + ", " + Intl.NumberFormat(LANG === "de" ? "de-DE" : "en-GB", { notation: "compact" }).format(p.downloads) + T(" Downloads")))),
      h("p", { class: "muted" }, p.description),
      h("div", { class: "actions" }, (p.categories || []).slice(0, 3).map(c => h("span", { class: "pill" }, c)), h("span", { style: { flex: "1" } }),
        (data?.mods || []).some(m => m.project === p.project_id) ? h("span", { class: "pill ok" }, T("Installiert"))
          : can("pack") ? h("button", { class: "btn small primary", onclick: e => { e.target.disabled = true; install(p.project_id, "", p.title + T(" installiert")); } }, T("Installieren")) : null))) : h("p", { class: "empty" }, T("Nichts gefunden.")));
    if (!data || data.server !== server) api("GET", "/servers/" + server + "/mods").then(d => { data = { server, ...d }; if (results) show(results); }).catch(() => {});
    const searchNow = async () => {
      try { results = await api("GET", "/servers/" + server + "/mods/search?q=" + encodeURIComponent(fq)); show(results); } catch (e) { put(grid, h("p", { class: "empty" }, e.message)); }
    };
    input.addEventListener("input", () => { fq = input.value; clearTimeout(timer); timer = setTimeout(searchNow, 300); });
    put(body, h("div", { class: "actions" }, input), grid);
    if (results) show(results);
    searchNow();
    input.focus();
  };

  nav();
  loadPack(false).catch(() => {});
  draw();
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
      const dropUpload = async files => {
        for (const f of files) {
          const buf = new Uint8Array(await f.arrayBuffer());
          let s = "";
          for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
          await run(f.name + T(" hochgeladen"), () => api("PUT", "/servers/" + server + "/files", { path: (path ? path + "/" : "") + f.name, data: btoa(s) }));
        }
        route();
      };
      main.ondragover = e => { e.preventDefault(); main.classList.add("dropping"); };
      main.ondragleave = e => { if (e.target === main) main.classList.remove("dropping"); };
      main.ondrop = e => { e.preventDefault(); main.classList.remove("dropping"); if (e.dataTransfer.files.length) dropUpload([...e.dataTransfer.files]); };
      cleanup.push(() => { main.ondragover = main.ondragleave = main.ondrop = null; main.classList.remove("dropping"); });
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

// ---- network ---------------------------------------------------------------------------------------

const CHAT_DE = { network: "Netzwerk", server: "Server", radius: "Umkreis" };

/** One line of the network's chat. */
function feedLine(e) {
  const tag = h("span", { class: "pill c", style: { "--c": colorOf(e.server) } }, e.server === "*" ? T("alle") : e.server);
  const time = h("time", { class: "dim num", datetime: e.t }, fmt.clock(e.t));
  if (e.kind === "say") return h("li", { class: "say" }, time, tag, h("b", null, e.player), h("span", null, e.text));
  const head = h("img", { src: "https://mc-heads.net/avatar/" + encodeURIComponent(e.player) + "/32", alt: "", loading: "lazy" });
  if (e.kind === "join" || e.kind === "leave") {
    return h("li", { class: "presence" }, time, tag, head, h("span", null, e.player + (e.kind === "join" ? T(" ist da") : T(" ist weg")) + (e.to ? T(", weiter nach ") + e.to : "")));
  }
  return h("li", null, time, tag, head, h("b", null, e.player), h("span", null, e.text), e.scope && e.scope !== "network" ? h("span", { class: "pill" }, T(CHAT_DE[e.scope] || e.scope)) : null);
}

/**
 * The map: one dot per Minecraft server, lines between the servers of a network. A message
 * travels along the lines as a small light, so the map shows what the settings mean.
 */
function netMap(servers) {
  const NS = "http://www.w3.org/2000/svg";
  const el = (t, a) => { const x = document.createElementNS(NS, t); for (const [k, v] of Object.entries(a)) x.setAttribute(k, v); return x; };
  const groups = [];
  for (const s of servers) {
    const g = s.network ? groups.find(x => x.name === s.network) : null;
    if (g) g.members.push(s); else groups.push({ name: s.network, members: [s] });
  }
  const pos = {};
  let x = 0;
  const H = 190;
  for (const g of groups) {
    const n = g.members.length, w = n === 1 ? 110 : n === 2 ? 220 : 240;
    const cx = x + w / 2, cy = H / 2 + 10;
    g.members.forEach((s, i) => {
      if (n === 1) pos[s.server] = [cx, cy];
      else if (n === 2) pos[s.server] = [cx + (i ? 60 : -60), cy];
      else { const a = -Math.PI / 2 + (2 * Math.PI * i) / n; pos[s.server] = [cx + 72 * Math.cos(a), cy + 60 * Math.sin(a)]; }
    });
    g.x = cx;
    x += w;
  }
  const svgEl = el("svg", { viewBox: "0 0 " + Math.max(x, 240) + " " + H, class: "netmap", role: "img", "aria-label": T("Welche Server zusammengehören") });
  const lines = el("g", {}), dots = el("g", {}), lights = el("g", {});
  for (const g of groups) {
    if (g.name) {
      const t = el("text", { x: g.x, y: 18, class: "net-name", "text-anchor": "middle" });
      t.textContent = g.name;
      svgEl.append(t);
    }
    const m = g.members;
    for (let i = 0; i < m.length; i++) for (let j = i + 1; j < m.length; j++) {
      const [a, b] = [pos[m[i].server], pos[m[j].server]];
      lines.append(el("line", { x1: a[0], y1: a[1], x2: b[0], y2: b[1], class: "net-line" + (m[i].chat === "network" && m[j].chat === "network" ? "" : " quiet") }));
    }
  }
  for (const s of servers) {
    const [cx, cy] = pos[s.server];
    const c = resolveColor(colorOf(s.server));
    const g = el("g", { class: "net-node" + (s.running ? "" : " off"), "data-server": s.server });
    g.append(el("circle", { cx, cy, r: 19, fill: "var(--panel)", stroke: c, "stroke-width": 2, "stroke-dasharray": s.bus ? "" : "3 3" }));
    const n = el("text", { x: cx, y: cy + 4, "text-anchor": "middle", class: "net-count", fill: c });
    n.textContent = (S.overview?.servers.find(o => o.name === s.server)?.players || []).length;
    const l = el("text", { x: cx, y: cy + 36, "text-anchor": "middle", class: "net-label" });
    l.textContent = s.label;
    const title = el("title", {});
    title.textContent = s.server + ": " + (s.bus ? T("Mod am Bus") : s.rcon ? T("über die Konsole") : T("ohne RCON"));
    g.append(n, l, title);
    dots.append(g);
  }
  svgEl.append(lines, lights, dots);
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  /** A message from one server: a light to every server that shows it. */
  svgEl.pulse = (from, kind) => {
    const src = servers.find(s => s.server === from);
    if (!src || !pos[from]) return;
    const node = dots.querySelector('[data-server="' + CSS.escape(from) + '"]');
    node?.classList.remove("ping"); void node?.getBoundingClientRect(); node?.classList.add("ping");
    if (still || !src.network) return;
    const to = servers.filter(s => s.server !== from && s.network === src.network && (kind === "chat" ? s.chat === "network" && src.chat === "network" : s.joins && src.joins));
    for (const t of to) {
      const [a, b] = [pos[from], pos[t.server]];
      const dot = el("circle", { r: 4, class: "net-light", fill: resolveColor(colorOf(from)) });
      lights.append(dot);
      const t0 = performance.now(), D = 650;
      const step = now => {
        const k = Math.min(1, (now - t0) / D), e = 1 - Math.pow(1 - k, 3);
        dot.setAttribute("cx", a[0] + (b[0] - a[0]) * e);
        dot.setAttribute("cy", a[1] + (b[1] - a[1]) * e);
        if (k < 1) requestAnimationFrame(step); else dot.remove();
      };
      requestAnimationFrame(step);
    }
  };
  return svgEl;
}

function pageNetwork(main) {
  const st = { data: null, filter: "*", feed: [] };
  const mapBox = h("div", { class: "netmap-box" });
  const feedEl = h("ol", { class: "feed", "aria-live": "polite", "aria-label": T("Chat aller Server") });
  const filterEl = h("div", { class: "seg", role: "group", "aria-label": T("Server filtern") });
  const settings = h("div");
  const busEl = h("div");
  main.append(header(T("Netzwerk"), T("Welche Server Chat, Beitritte, Tabliste und Listen teilen, und was gerade gesagt wird.")),
    h("div", { class: "grid-2" },
      h("section", { class: "panel" },
        h("header", { class: "net-head" }, h("h2", null, T("Chat")), filterEl),
        can("players") ? sayForm() : null,
        feedEl),
      h("section", { class: "panel" },
        h("header", null, h("h2", null, T("Karte")), h("p", null, T("Linien verbinden, was geteilt wird"))),
        h("div", { class: "body" }, mapBox, busEl))),
    h("section", { class: "panel", style: { marginTop: "1rem" } },
      h("header", null, h("h2", null, T("Was geteilt wird")), h("p", null, T("Wirkt sofort. Tabliste, Umkreis und Spielerdaten brauchen eine Mod am Bus."))),
      settings));

  function sayForm() {
    const input = h("input", { type: "text", maxlength: 256, placeholder: T("Nachricht an die Spieler"), "aria-label": T("Nachricht an die Spieler") });
    const to = h("select", { "aria-label": T("An welchen Server") });
    const f = h("form", { class: "say-form", onsubmit: async e => {
      e.preventDefault();
      const text = input.value.trim();
      if (!text) return;
      await run(T("Gesendet"), () => api("POST", "/network/say", { text, servers: to.value === "*" ? [] : [to.value] }));
      input.value = "";
    } }, to, input, h("button", { class: "btn primary", type: "submit" }, T("Senden")));
    f.fill = servers => put(to, h("option", { value: "*" }, T("Alle Server")), servers.map(s => h("option", { value: s.server }, s.label)));
    st.sayForm = f;
    return f;
  }

  const drawFeed = () => {
    const rows = st.feed.filter(e => st.filter === "*" || e.server === st.filter || e.server === "*");
    put(feedEl, rows.length ? rows.slice(-200).map(feedLine) : h("li", { class: "empty" }, T("Noch nichts gesagt. Chat und Beitritte erscheinen hier, sobald sie passieren.")));
    feedEl.scrollTop = feedEl.scrollHeight;
  };
  const drawFilter = servers => put(filterEl, [{ server: "*", label: T("Alle") }, ...servers].map(s => h("button", {
    type: "button", "aria-pressed": String(st.filter === s.server), onclick: () => { st.filter = s.server; drawFilter(servers); drawFeed(); },
  }, s.server === "*" ? null : h("span", { class: "dot", style: { "--c": colorOf(s.server) } }), s.label)));

  const save = (server, key, value) => run(T("Gespeichert"), () => api("POST", "/servers/" + server + "/config", { key, value: String(value) })).then(load);

  const drawSettings = d => {
    const mcs = d.servers.filter(s => s.minecraft);
    const names = [...new Set(mcs.map(s => s.network).filter(Boolean))];
    const edit = can("config");
    const list = h("datalist", { id: "net-names" }, names.map(n => h("option", { value: n })));
    put(settings, list, mcs.length ? h("div", { class: "table-wrap" }, h("table", { class: "net-table" },
      h("thead", null, h("tr", null, h("th", null, "Server"), h("th", null, T("Netzwerk")), h("th", null, "Chat"),
        h("th", null, T("Beitritte")), h("th", null, T("Tabliste")), h("th", null, T("Listen")), h("th", null, T("Spielerdaten")), h("th", null, T("Anbindung")))),
      h("tbody", null, mcs.map(s => {
        const net = h("input", { type: "text", value: s.network, list: "net-names", placeholder: T("allein"), size: 10, maxlength: 24, disabled: !edit || null, "aria-label": T("Netzwerk von ") + s.server });
        net.addEventListener("change", () => save(s.server, "network", net.value.trim().toLowerCase()));
        const off = !s.network;
        const sw = (key, on, needsBus) => h("input", { type: "checkbox", class: "switch", checked: on || null, disabled: !edit || off || null,
          title: needsBus && !s.bus ? T("Wirkt, sobald eine Mod am Bus ist") : null, "aria-label": key + " " + s.server,
          onchange: e => save(s.server, key, e.target.checked) });
        const chat = h("div", { class: "seg small", role: "group", "aria-label": "Chat " + s.server }, ["network", "server", "radius"].map(c => h("button", {
          type: "button", "aria-pressed": String(s.chat === c), disabled: !edit || null, onclick: () => save(s.server, "sync.chat", c),
        }, T(CHAT_DE[c]))));
        const radius = s.chat === "radius" ? h("input", { type: "number", min: 1, max: 9999, value: s.radius, class: "radius", disabled: !edit || null, "aria-label": T("Umkreis in Blöcken") }) : null;
        radius?.addEventListener("change", () => save(s.server, "chat.radius", radius.value));
        const link = s.bus ? h("span", { class: "pill ok" }, T("Mod am Bus")) : s.rcon ? h("span", { class: "pill" }, T("über die Konsole")) : h("span", { class: "pill warn" }, T("ohne RCON"));
        return h("tr", null,
          h("td", null, h("span", { class: "pill c", style: { "--c": colorOf(s.server) } }, s.server)),
          h("td", null, net),
          h("td", null, h("div", { class: "actions nowrap" }, chat, radius, radius ? h("span", { class: "dim small" }, T("Blöcke")) : null)),
          h("td", null, sw("sync.joins", s.joins)),
          h("td", null, sw("sync.tablist", s.tablist, true)),
          h("td", null, sw("sync.lists", s.lists)),
          h("td", null, sw("sync.players", s.players, true)),
          h("td", null, link));
      })))) : h("p", { class: "empty" }, T("Kein Minecraft-Server da.")));
  };

  const drawBus = d => {
    const edit = can("config");
    const port = h("input", { type: "text", value: d.busPort, placeholder: "25580", size: 6, inputmode: "numeric", disabled: !edit || null, "aria-label": T("Port des Busses") });
    port.addEventListener("change", () => run(T("Gespeichert"), () => api("POST", "/launcher/config", { key: "bus.port", value: port.value.trim() })).then(load));
    put(busEl, h("div", { class: "bus-line" },
      h("span", { class: "pill " + (d.bus ? "ok" : "") }, d.bus ? T("Bus offen") : T("Bus aus")),
      h("span", { class: "dim small" }, d.bus ? T("Nur im Container erreichbar, Mods melden sich mit dem Schlüssel aus bus.key.") : T("Ohne Bus gehen Chat und Beitritte über die Konsolen der Server.")),
      h("label", { class: "check" }, h("span", { class: "dim small" }, "Port"), port)));
  };

  const load = async () => {
    const d = await api("GET", "/network?n=300");
    st.data = d;
    if (!st.feed.length) st.feed = d.feed;
    const mcs = d.servers.filter(s => s.minecraft);
    put(mapBox, mcs.length ? (st.map = netMap(mcs)) : null);
    st.sayForm?.fill(mcs);
    drawFilter(mcs);
    drawSettings(d);
    drawBus(d);
    drawFeed();
  };
  on("chat", e => {
    st.feed.push(e);
    if (st.feed.length > 600) st.feed.splice(0, 100);
    st.map?.pulse(e.server, e.kind === "chat" ? "chat" : "joins");
    if (st.filter === "*" || e.server === st.filter) {
      const empty = feedEl.querySelector(".empty");
      if (empty) empty.remove();
      const stick = feedEl.scrollHeight - feedEl.scrollTop - feedEl.clientHeight < 40;
      feedEl.append(feedLine(e));
      while (feedEl.children.length > 300) feedEl.firstChild.remove();
      if (stick) feedEl.scrollTop = feedEl.scrollHeight;
    }
  });
  let last = Date.now();
  on("overview", () => { if (Date.now() - last > 30000 && !document.activeElement?.matches("input, select")) { last = Date.now(); load(); } });
  load().catch(e => put(settings, h("p", { class: "error" }, e.message)));
}

// ---- automation: schedule and alerts ------------------------------------------------------------------

const ACTION_DE = { restart: "Neustart", stop: "Stoppen", start: "Starten", command: "Befehl", say: "Nachricht", backup: "Backup" };
const DAY_DE = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"];
const ALERT_DE = { crash: "Absturz", down: "Aufgegeben (drei Abstürze)", mspt: "Tick-Zeit dauerhaft hoch", backup: "Backup fertig", "backup-failed": "Backup fehlgeschlagen", start: "Server läuft", stop: "Server gestoppt" };

function whenText(t) {
  if (t.kind === "every") return T("alle ") + t.hours + T(" Stunden");
  const days = (t.days || []).map(Number);
  const d = days.length === 7 ? T("täglich") : days.join() === "1,2,3,4,5" ? T("werktags") : days.map(n => T(DAY_DE[n - 1])).join(", ");
  return d + T(" um ") + t.time;
}

function taskDialog(task, onSaved) {
  const t = Object.assign({ kind: "daily", time: "05:00", days: [1, 2, 3, 4, 5, 6, 7], hours: 6, action: "restart", server: "*", text: "", warn: [10, 5, 1], enabled: true }, task || {});
  const servers = S.overview.servers;
  const name = h("input", { type: "text", value: t.name || "", maxlength: 60, placeholder: T("Nächtlicher Neustart"), required: true });
  const action = h("select", null, Object.keys(ACTION_DE).map(a => h("option", { value: a, selected: a === t.action || null }, T(ACTION_DE[a]))));
  const server = h("select", null, h("option", { value: "*" }, T("Alle Server")), servers.map(x => h("option", { value: x.name, selected: x.name === t.server || null }, x.name)));
  const text = h("input", { type: "text", value: t.text, maxlength: 256 });
  const kind = h("select", null, h("option", { value: "daily", selected: t.kind === "daily" || null }, T("Zu einer Uhrzeit")), h("option", { value: "every", selected: t.kind === "every" || null }, T("Alle paar Stunden")));
  const time = h("input", { type: "time", value: t.time || "05:00" });
  const hours = h("select", null, [1, 2, 3, 4, 6, 8, 12, 24].map(n => h("option", { value: n, selected: n === +t.hours || null }, n + " h")));
  const days = h("div", { class: "seg small", role: "group", "aria-label": T("Wochentage") }, DAY_DE.map((d, i) => {
    const b = h("button", { type: "button", "aria-pressed": String((t.days || []).map(Number).includes(i + 1)) }, T(d));
    b.addEventListener("click", () => b.setAttribute("aria-pressed", String(b.getAttribute("aria-pressed") !== "true")));
    return b;
  }));
  const warn = h("input", { type: "text", value: (t.warn || []).join(", "), placeholder: "10, 5, 1" });
  const field = (label, input, hint) => h("label", { class: "field" }, h("span", null, label), input, hint ? h("small", { class: "dim" }, hint) : null);
  const textField = field(T("Text"), text, T("Der Befehl ohne / oder die Nachricht an die Spieler."));
  const warnField = field(T("Vorwarnung"), warn, T("Minuten vorher, mit Komma. Die Spieler sehen: Neustart in 5 Minuten."));
  const dailyBox = h("div", { class: "stack" }, field(T("Uhrzeit"), time), h("div", { class: "field" }, h("span", null, T("Tage")), days));
  const everyBox = field(T("Abstand"), hours, T("Ab Mitternacht gezählt: 6 h heißt 0, 6, 12 und 18 Uhr."));
  const sync = () => {
    textField.hidden = !["command", "say"].includes(action.value);
    warnField.hidden = !["restart", "stop"].includes(action.value);
    dailyBox.hidden = kind.value !== "daily";
    everyBox.hidden = kind.value !== "every";
  };
  action.addEventListener("change", sync); kind.addEventListener("change", sync); sync();
  const err = h("p", { class: "error", role: "alert" });
  const d = h("dialog", null, h("form", { method: "dialog", onsubmit: async e => {
    e.preventDefault();
    const body = { id: t.id, name: name.value.trim(), action: action.value, server: server.value, text: text.value.trim(), kind: kind.value, time: time.value,
      hours: +hours.value, days: $$("button", days).map((b, i) => b.getAttribute("aria-pressed") === "true" ? i + 1 : 0).filter(Boolean),
      warn: ["restart", "stop"].includes(action.value) ? warn.value.split(/[ ,;]+/).filter(Boolean).map(Number) : [], enabled: t.enabled };
    try { await api("POST", "/schedule", body); d.close(); toast(T("Gespeichert"), body.name); onSaved(); } catch (x) { err.textContent = x.message; }
  } },
    h("h2", null, task ? T("Aufgabe ändern") : T("Neue Aufgabe")),
    field(T("Name"), name), h("div", { class: "grid-two" }, field(T("Was"), action), field(T("Wo"), server)), textField,
    field(T("Wann"), kind), dailyBox, everyBox, warnField, err,
    h("div", { class: "actions" }, h("span", { style: { flex: "1" } }), h("button", { class: "btn quiet", type: "button", onclick: () => d.close() }, T("Abbrechen")), h("button", { class: "btn primary", type: "submit" }, T("Speichern")))));
  d.addEventListener("close", () => d.remove());
  document.body.append(d);
  d.showModal();
}

function pageAutomation(main) {
  const tasksEl = h("div");
  const alertsEl = h("div", { class: "body stack" });
  const zoneEl = h("p");
  main.append(header(T("Automatik"), T("Was nach der Uhr passiert, und wer Bescheid bekommt, wenn etwas schiefgeht.")),
    h("section", { class: "panel" },
      h("header", null, h("div", null, h("h2", null, T("Zeitplan")), zoneEl),
        can("config") ? h("button", { class: "btn primary", onclick: () => taskDialog(null, load) }, T("Neue Aufgabe")) : null),
      tasksEl),
    h("section", { class: "panel", style: { marginTop: "1rem" } },
      h("header", null, h("div", null, h("h2", null, T("Meldungen")), h("p", null, T("An einen Discord-Webhook oder jede andere https-Adresse.")))),
      alertsEl));
  const load = async () => {
    const d = await api("GET", "/schedule");
    zoneEl.textContent = T("Zeitzone ") + d.zone + T(", jetzt ") + d.now.slice(11, 16);
    put(tasksEl, d.tasks.length ? h("table", null,
      h("thead", null, h("tr", null, h("th", null, T("Aufgabe")), h("th", null, T("Wann")), h("th", { class: "hide-s" }, T("Nächstes Mal")), h("th", null, T("An")), h("th"))),
      h("tbody", null, d.tasks.map(t => h("tr", null,
        h("td", null, h("b", null, t.name), h("div", { class: "dim small" }, T(ACTION_DE[t.action]) + (t.server === "*" ? T(" auf allen Servern") : T(" auf ") + t.server) + (t.text ? ": " + t.text : "") + (t.warn?.length && ["restart", "stop"].includes(t.action) ? T(", warnt ") + t.warn.join(", ") + " min" : ""))),
        h("td", null, whenText(t)),
        h("td", { class: "hide-s num" }, t.next ? fmt.date(t.next) : "-", t.last ? h("div", { class: "dim small" }, T("zuletzt ") + fmt.date(t.last)) : null),
        h("td", null, h("input", { type: "checkbox", class: "switch", checked: t.enabled || null, disabled: !can("config") || null, "aria-label": T("An oder aus"),
          onchange: e => run(T("Gespeichert"), () => api("POST", "/schedule", Object.assign({}, t, { enabled: e.target.checked }))).then(load) })),
        h("td", { class: "right" }, h("div", { class: "actions", style: { justifyContent: "flex-end" } },
          can("power") ? h("button", { class: "btn small quiet", onclick: async () => {
            if (await confirmDialog({ title: t.name + T(" jetzt ausführen?"), text: whenText(t) + T(" wäre das nächste Mal."), ok: T("Ausführen") })) run(T("Läuft"), () => api("POST", "/schedule/" + t.id + "/run", {}));
          } }, T("Jetzt")) : null,
          can("config") ? h("button", { class: "btn small quiet", onclick: () => taskDialog(t, load) }, T("Ändern")) : null,
          can("config") ? h("button", { class: "btn small quiet", "aria-label": T("Entfernen"), onclick: async () => {
            if (await confirmDialog({ title: t.name + T(" entfernen?"), ok: T("Entfernen"), danger: true })) { await run(T("Entfernt"), () => api("DELETE", "/schedule/" + t.id, {})); load(); }
          } }, "×") : null)))))) : h("div", { class: "empty" }, h("p", null, T("Noch nichts geplant.")), can("config") ? h("div", { class: "actions", style: { justifyContent: "center" } },
            h("button", { class: "btn", onclick: () => run(T("Gespeichert"), () => api("POST", "/schedule", { name: T("Nächtlicher Neustart"), action: "restart", server: "*", kind: "daily", time: "05:00", warn: [10, 5, 1] })).then(load) }, T("Neustart jede Nacht um 5")),
            h("button", { class: "btn", onclick: () => run(T("Gespeichert"), () => api("POST", "/schedule", { name: T("Backup alle 6 Stunden"), action: "backup", server: "*", kind: "every", hours: 6 })).then(load) }, T("Backup alle 6 Stunden"))) : null));
    const a = await api("GET", "/alerts");
    const url = h("input", { type: "url", placeholder: a.set ? T("gesetzt (") + a.host + T("), leer lassen zum Behalten") : "https://discord.com/api/webhooks/...", disabled: !can("config") || null, "aria-label": "Webhook" });
    const boxes = Object.keys(ALERT_DE).map(k => h("label", { class: "check" }, h("input", { type: "checkbox", value: k, checked: a.events.includes(k) || null, disabled: !can("config") || null }), T(ALERT_DE[k])));
    const mspt = h("input", { type: "number", min: 20, max: 1000, value: a.mspt, disabled: !can("config") || null, "aria-label": T("Grenze in ms") });
    const save = () => run(T("Gespeichert"), () => api("POST", "/alerts", { webhook: url.value.trim(), events: boxes.map(b => b.firstChild).filter(i => i.checked).map(i => i.value), mspt: +mspt.value })).then(load);
    put(alertsEl,
      h("label", { class: "field" }, h("span", null, "Webhook"), url, h("small", { class: "dim" }, T("Die Adresse bleibt im Launcher und wird nie angezeigt."))),
      h("div", { class: "alert-grid" }, boxes),
      h("label", { class: "field" }, h("span", null, T("Tick-Zeit-Grenze")), h("div", { class: "actions" }, mspt, h("span", { class: "dim small" }, T("ms im Schnitt über eine Minute (50 ms ist die Grenze für volle 20 TPS)")))),
      can("config") ? h("div", { class: "actions" }, h("button", { class: "btn primary", onclick: save }, T("Speichern")),
        a.set ? h("button", { class: "btn", onclick: () => run(T("Test gesendet"), () => api("POST", "/alerts/test", {})) }, T("Test senden")) : null,
        a.set ? h("button", { class: "btn quiet", onclick: async () => { if (await confirmDialog({ title: T("Webhook entfernen?"), ok: T("Entfernen"), danger: true })) run(T("Entfernt"), () => api("POST", "/alerts", { webhook: "-", events: a.events, mspt: a.mspt })).then(load); } }, T("Webhook entfernen")) : null) : null);
  };
  load().catch(e => put(tasksEl, h("p", { class: "error body" }, e.message)));
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
        h("div", { class: "actions" }, h("button", { class: "btn", onclick: addPasskey }, svg(ICON.key), T("Weiteren Passkey anlegen")), h("button", { class: "btn", onclick: pairDevice }, T("Gerät koppeln"))))));
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
        h("button", { class: "btn small", onclick: pairDevice }, T("Gerät koppeln")),
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

// ---- wizards ----------------------------------------------------------------------------------------

/**
 * A dialog in steps. Each step draws itself into the body and says whether "Next" may be
 * pressed; the last step's button runs finish.
 */
function wizard({ title, steps, finish, finishLabel }) {
  let i = 0;
  const state = {};
  const body = h("div", { class: "wiz-body" });
  const dots = h("ol", { class: "wiz-steps" });
  const back = h("button", { class: "btn quiet", type: "button" }, T("Zurück"));
  const next = h("button", { class: "btn primary", type: "button" });
  const err = h("p", { class: "error", role: "alert" });
  const d = h("dialog", { class: "wizard" }, h("div", { class: "wiz" },
    h("header", null, h("h2", null, title), h("button", { class: "btn quiet small", type: "button", "aria-label": T("Schließen"), onclick: () => d.close() }, "×")),
    dots, body, err, h("div", { class: "actions wiz-foot" }, back, h("span", { style: { flex: "1" } }), next)));
  const draw = () => {
    err.textContent = "";
    put(dots, steps.map((s, k) => h("li", { "aria-current": k === i ? "step" : null, class: k < i ? "done" : "" }, T(s.name))));
    put(body);
    steps[i].draw(body, state, () => refresh());
    back.hidden = i === 0;
    next.textContent = i === steps.length - 1 ? (finishLabel || T("Fertig")) : T("Weiter");
    refresh();
  };
  const refresh = () => { next.disabled = steps[i].ready && !steps[i].ready(state); };
  back.addEventListener("click", () => { i = Math.max(0, i - 1); draw(); });
  next.addEventListener("click", async () => {
    if (i < steps.length - 1) { i++; draw(); return; }
    next.disabled = true;
    next.textContent = T("Einen Moment...");
    try { await finish(state); d.close(); } catch (e) { err.textContent = e.message; next.disabled = false; next.textContent = finishLabel || T("Fertig"); }
  });
  d.addEventListener("close", () => d.remove());
  document.body.append(d);
  d.showModal();
  draw();
  return d;
}

const SOFTWARE_ICON = { paper: "P", purpur: "Pu", folia: "Fo", fabric: "Fa", neoforge: "N", vanilla: "V", velocity: "Ve", custom: "J" };

/** Software and version, shared by "new server" and "change software". */
function softwareSteps() {
  return [
    { name: "Software", ready: s => !!s.kind, draw: (b, s, refresh) => {
      api("GET", "/software").then(kinds => put(b, h("div", { class: "soft-grid" }, kinds.map(k => h("button", {
        type: "button", class: "soft" + (s.kind === k.id ? " on" : ""), "aria-pressed": String(s.kind === k.id),
        onclick: e => { s.kind = k.id; s.version = ""; $$(".soft", b).forEach(x => { x.classList.toggle("on", x === e.currentTarget); x.setAttribute("aria-pressed", String(x === e.currentTarget)); }); refresh(); },
      }, h("span", { class: "soft-icon" }, SOFTWARE_ICON[k.id] || "?"), h("b", null, T(k.name)), h("span", { class: "dim" }, T(k.about)))))));
    } },
    { name: "Version", ready: s => s.kind === "custom" ? !!s.file : !!s.version, draw: (b, s, refresh) => {
      if (s.kind === "custom") {
        const f = h("input", { type: "file", accept: ".jar" });
        f.addEventListener("change", () => { s.file = f.files[0]; refresh(); });
        put(b, h("p", { class: "muted" }, T("Die Jar wird hochgeladen, sobald der Server angelegt ist. Sie muss sich mit java -jar starten lassen.")), h("label", { class: "drop" }, f, T("Jar auswählen")));
        return;
      }
      const all = h("input", { type: "checkbox" });
      const search = h("input", { type: "search", placeholder: T("Version suchen") });
      const list = h("div", { class: "ver-list", role: "listbox" });
      let versions = [];
      const show = () => put(list, versions.filter(v => !search.value || v.includes(search.value)).slice(0, 80).map(v => h("button", {
        type: "button", role: "option", "aria-selected": String(v === s.version), class: "ver" + (v === s.version ? " on" : ""),
        onclick: () => { s.version = v; show(); refresh(); },
      }, v)));
      const load = () => api("GET", "/software/" + s.kind + "/versions" + (all.checked ? "?all=1" : "")).then(v => { versions = v; if (!s.version && v.length) s.version = v[0]; show(); refresh(); })
        .catch(e => put(list, h("p", { class: "error" }, e.message)));
      search.addEventListener("input", show);
      all.addEventListener("change", load);
      put(b, h("div", { class: "actions" }, search, h("label", { class: "check" }, all, T("Auch Vorabversionen"))), list);
      load();
    } },
  ];
}

function newServerWizard() {
  const used = new Set(S.overview.servers.map(s => s.port).filter(Boolean));
  wizard({
    title: T("Neuer Server"), finishLabel: T("Server anlegen"),
    steps: [...softwareSteps(), {
      name: "Details", ready: s => /^[a-z0-9][a-z0-9_-]{0,23}$/.test(s.name || "") && /^[0-9]{2,5}$/.test(s.port || "") && (s.kind === "velocity" || s.kind === "custom" || s.eula),
      draw: (b, s, refresh) => {
        s.memory ||= s.kind === "neoforge" ? "8G" : "4G";
        s.share ||= 3;
        const field = (label, input, hint) => h("label", { class: "field" }, h("span", null, label), input, hint ? h("small", { class: "dim" }, hint) : null);
        const name = h("input", { type: "text", value: s.name || "", placeholder: "lobby", maxlength: 24 });
        const port = h("input", { type: "text", value: s.port || "", placeholder: "25566", inputmode: "numeric" });
        const mem = h("select", null, ["2G", "4G", "6G", "8G", "12G", "16G", "24G"].map(m => h("option", { value: m, selected: m === s.memory || null }, m)));
        const eula = h("input", { type: "checkbox", checked: s.eula || null });
        name.addEventListener("input", () => { s.name = name.value.trim().toLowerCase(); refresh(); });
        port.addEventListener("input", () => { s.port = port.value.trim(); refresh(); });
        mem.addEventListener("change", () => { s.memory = mem.value; });
        eula.addEventListener("change", () => { s.eula = eula.checked; refresh(); });
        const first = S.overview.servers[0];
        const packed = s.kind === "neoforge" && S.overview.pack?.version;
        if (s.samePack == null) s.samePack = !!packed;
        const same = h("input", { type: "checkbox", checked: s.samePack || null });
        same.addEventListener("change", () => { s.samePack = same.checked; });
        const net = h("input", { type: "text", value: s.network || "", placeholder: T("allein"), list: "wiz-nets", maxlength: 24 });
        const nets = h("datalist", { id: "wiz-nets" });
        api("GET", "/network?n=0").then(d => put(nets, [...new Set(d.servers.map(x => x.network).filter(Boolean))].map(n => h("option", { value: n })))).catch(() => {});
        net.addEventListener("input", () => { s.network = net.value.trim().toLowerCase(); });
        const voice = h("input", { type: "text", value: s.voice || "", placeholder: T("leer: keiner"), inputmode: "numeric" });
        voice.addEventListener("input", () => { s.voice = voice.value.trim(); });
        put(b, h("div", { class: "form-grid" },
          field(T("Name"), name, T("Kleinbuchstaben, Ziffern, - und _. Wird der Ordner servers/<name>.")),
          field(T("Port"), port, T("Ein freier Port, den dein Anbieter nach außen gibt (beim Panel eine Allocation). Belegt: ") + ([...used].join(", ") || "-")),
          field(T("Arbeitsspeicher"), mem, T("Heap des Servers. Der Launcher prüft, ob alles zusammen passt.")),
          packed ? h("label", { class: "check" }, same, h("span", null, T("Gleiches Pack wie ") + first.name + T(": Mods und Configs werden verlinkt, ein Update gilt für beide"))) : null,
          field(T("Netzwerk"), h("span", null, net, nets), T("Server mit dem gleichen Netzwerk teilen Chat, Beitritte und Listen. Was genau, stellst du unter Netzwerk ein.")),
          field(T("Voice-Port"), voice, T("UDP-Port für Simple Voice Chat, falls die Mod drin ist."))),
          s.kind === "velocity" || s.kind === "custom" ? null : h("label", { class: "check eula" }, eula, h("span", null, T("Ich akzeptiere die "), h("a", { href: "https://aka.ms/MinecraftEULA", target: "_blank", rel: "noopener" }, "Minecraft EULA"), ".")));
      },
    }],
    finish: async s => {
      await api("POST", "/servers", { name: s.name, kind: s.kind, version: s.version, port: s.port, memory: s.memory, share: s.share, eula: !!s.eula, start: true,
        samePack: !!s.samePack, network: s.network || "", transfers: !!s.network, voice: s.voice || "" });
      toast(T("Server angelegt"), s.name + ": " + (s.kind === "custom" ? T("Jar wird hochgeladen") : T("startet gleich")));
      await waitForServer(s.name);
      if (s.kind === "custom" && s.file) await uploadJar(s.name, s.file);
      go("/konsole/" + s.name);
    },
  });
}

/** After a reload the new server appears; this waits for it (the stream reconnects by itself). */
async function waitForServer(name) {
  for (let k = 0; k < 30; k++) {
    await new Promise(r => setTimeout(r, 1000));
    try {
      const o = await api("GET", "/overview");
      if (o.servers.some(s => s.name === name)) { S.overview = o; drawFleet(); return; }
    } catch {}
  }
}

async function uploadJar(server, file) {
  const res = await fetch("/api/servers/" + server + "/jar?name=" + encodeURIComponent(file.name.replace(/[^A-Za-z0-9_.+-]/g, "-")), {
    method: "PUT", body: file, headers: { "X-Csrf-Token": S.session.csrf, "Content-Type": "application/octet-stream" }, credentials: "same-origin",
  });
  const j = await res.json().catch(() => ({ ok: false, error: "HTTP " + res.status }));
  if (!j.ok) throw new Error(j.error);
  toast(T("Jar hochgeladen"), file.name);
}

function softwareWizard(server) {
  wizard({
    title: T("Software für ") + server, finishLabel: T("Installieren"),
    steps: softwareSteps(),
    finish: async s => {
      if (s.kind === "custom") {
        await uploadJar(server, s.file);
      } else {
        await api("POST", "/servers/" + server + "/software", { kind: s.kind, version: s.version });
        toast(T("Installiert"), s.kind + " " + s.version);
      }
      refreshOverview();
      if (S.page?.path === "/server") route();
    },
  });
}

function welcomeWizard() {
  wizard({
    title: T("Willkommen"), finishLabel: T("Los geht's"),
    steps: [{
      name: "Name", ready: s => (s.name || "").trim().length > 0, draw: (b, s, refresh) => {
        const name = h("input", { type: "text", value: s.name || "", placeholder: T("Mein Netzwerk"), maxlength: 40 });
        const lang = h("div", { class: "seg" }, [["de", "Deutsch"], ["en", "English"]].map(([k, n]) => h("button", { type: "button", "aria-pressed": String((s.language || LANG) === k), onclick: e => { s.language = k; $$("button", lang).forEach(x => x.setAttribute("aria-pressed", String(x === e.currentTarget))); } }, n)));
        const colors = ["#e5b451", "#8bb6dc", "#bb98f4", "#7fd0c8", "#f06579", "#8fd18b"];
        const sw = h("div", { class: "swatches" }, colors.map(c => h("button", { type: "button", class: "swatch", style: { "--c": c }, "aria-label": c, "aria-pressed": String((s.accent || S.session.accent) === c), onclick: e => { s.accent = c; document.documentElement.style.setProperty("--gold", c); $$(".swatch", sw).forEach(x => x.setAttribute("aria-pressed", String(x === e.currentTarget))); } })));
        name.addEventListener("input", () => { s.name = name.value; refresh(); });
        put(b, h("p", { class: "muted" }, T("Ein paar Dinge, damit die Console nach dir aussieht. Alles lässt sich später ändern.")),
          h("label", { class: "field" }, h("span", null, T("Name, steht oben in der Console")), name),
          h("div", { class: "field" }, h("span", null, T("Sprache")), lang),
          h("div", { class: "field" }, h("span", null, T("Farbe")), sw));
        setTimeout(() => name.focus(), 50);
      },
    }],
    finish: async s => {
      await api("POST", "/launcher/settings", { name: s.name.trim(), language: s.language || LANG, accent: s.accent || S.session.accent });
      S.session = await api("GET", "/session");
      start();
      const first = S.overview?.servers?.[0];
      if (first && !first.wanted && first.state === "stopped") setTimeout(() => softwareWizard(first.name), 800);
    },
  });
}

// ---- one server ------------------------------------------------------------------------------------

function pageServer(main, rest) {
  const s = S.overview.servers.find(x => x.name === rest) || S.overview.servers[0];
  const body = h("div", { class: "stack" });
  main.append(header(s.name, T("Software, Start und Einstellungen dieses Servers."),
    h("a", { class: "btn", href: "/konsole/" + s.name, "data-link": true }, T("Konsole")), ...powerButtons(s)), body);
  const row = (label, value, action) => h("div", { class: "kv" }, h("span", { class: "muted" }, label), h("b", null, value || "-"), action || h("span"));
  const set = (key, value, label) => run(label, () => api("POST", "/servers/" + s.name + "/config", { key, value: String(value) })).then(refreshOverview);
  put(body,
    h("section", { class: "panel" }, h("header", null, h("h2", null, T("Software"))), h("div", { class: "body stack" },
      row(T("Art"), s.type === "neoforge" ? "NeoForge" : s.type === "command" ? T("Programm") : "Jar"),
      row(T("Datei"), s.jar || (s.type === "neoforge" ? "NeoForge " + (s.neoforge || T("aus dem Pack")) : "")),
      row("Minecraft", s.minecraft),
      can("config") && can("power") && s.type !== "command" ? h("div", { class: "actions" }, h("button", { class: "btn", onclick: () => softwareWizard(s.name) }, T("Software installieren oder wechseln"))) : null)),
    h("section", { class: "panel" }, h("header", null, h("h2", null, T("Start"))), h("div", { class: "body stack" },
      row(T("Ordner"), s.dir || "."),
      row(T("Port"), s.port || T("aus server.properties")),
      row(T("Arbeitsspeicher"), s.memory, can("config") ? h("button", { class: "btn small", onclick: async () => {
        const v = await confirmDialog({ title: T("Heap für ") + s.name, ok: T("Speichern"), input: { label: T("Zum Beispiel 8G"), value: s.memory } });
        if (v) set("memory", v.trim().toUpperCase(), T("Gespeichert"));
      } }, T("Ändern")) : null),
      row(T("JVM-Flags"), s.jvmArgs, can("config") ? h("button", { class: "btn small", onclick: async () => {
        const v = await confirmDialog({ title: T("JVM-Flags für ") + s.name, ok: T("Speichern"), input: { label: T("Zusätzlich zu denen des Launchers"), value: s.jvmArgs || "" } });
        if (v != null) set("jvm.args", v.trim(), T("Gespeichert"));
      } }, T("Ändern")) : null),
      h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: s.autostart || null, disabled: !can("config") || null, onchange: e => set("autostart", e.target.checked, T("Gespeichert")) }), T("Mit dem Launcher starten")),
      h("label", { class: "check" }, h("input", { type: "checkbox", class: "switch", checked: s.restartOnCrash || null, disabled: !can("config") || null, onchange: e => set("restart.on.crash", e.target.checked, T("Gespeichert")) }), T("Nach einem Absturz neu starten")))),
    s.type !== "command" ? maintenancePanel(s) : null,
    s.type !== "command" ? backupPanel(s) : null,
    s.type !== "command" && (s.tools || []).length ? toolsPanel(s) : null,
    s.type !== "command" ? propertiesPanel(s) : null,
    s.name !== S.overview.servers[0].name && can("config") && can("power") ? h("section", { class: "panel danger-zone" }, h("header", null, h("h2", null, T("Server entfernen"))), h("div", { class: "body actions" },
      h("p", { class: "muted", style: { margin: "0", flex: "1" } }, T("Stoppt ihn und nimmt ihn aus dem Launcher. Sein Ordner mit Welt und Dateien bleibt.")),
      h("button", { class: "btn danger", onclick: async () => {
        const n = await confirmDialog({ title: s.name + T(" entfernen?"), text: T("Tippe den Namen zur Bestätigung."), ok: T("Entfernen"), danger: true, input: { label: T("Name"), required: true } });
        if (n !== s.name) return n && toast(T("Name stimmt nicht"), "", true);
        await run(T("Entfernt"), () => api("DELETE", "/servers/" + s.name, {}));
        setTimeout(() => go("/"), 2500);
      } }, T("Entfernen")))) : null);
}

// ---- server tools: maintenance, backups, profiler, pregeneration, server.properties ---------------

function maintenancePanel(s) {
  const msg = h("input", { type: "text", maxlength: 200, placeholder: T("Wartungsarbeiten, bis gleich."), disabled: !can("power") || null, "aria-label": T("Nachricht an alle, die rausmüssen") });
  const sw = h("input", { type: "checkbox", class: "switch", checked: s.maintenance || null, disabled: !can("power") || null });
  sw.addEventListener("change", async () => {
    const on = sw.checked;
    if (on && !(await confirmDialog({ title: T("Wartung für ") + s.name + "?", text: T("Alle außer Operatoren werden sofort rausgeschickt und kommen nicht mehr rein."), ok: T("Wartung an") }))) { sw.checked = false; return; }
    const r = await run(on ? T("Wartung an") : T("Wartung aus"), () => api("POST", "/servers/" + s.name + "/maintenance", { on, message: msg.value.trim() }));
    if (r?.sent?.length) toast(T("Rausgeschickt"), r.sent.join(", "));
    refreshOverview();
  });
  return h("section", { class: "panel" + (s.maintenance ? " warn-zone" : "") },
    h("header", null, h("h2", null, T("Wartung")), h("p", null, T("Nur Operatoren kommen rein, alle anderen sehen deine Nachricht."))),
    h("div", { class: "body stack" },
      h("label", { class: "check" }, sw, s.maintenance ? h("b", { class: "warn-text" }, T("Wartung läuft")) : T("Wartung einschalten")),
      h("label", { class: "field" }, h("span", null, T("Nachricht")), msg)));
}

function backupPanel(s) {
  const list = h("div");
  const make = h("button", { class: "btn", disabled: !can("power") || null }, T("Backup jetzt"));
  const draw = async () => {
    let d;
    try { d = await api("GET", "/servers/" + s.name + "/backups"); } catch (e) { return put(list, h("p", { class: "error body" }, e.message)); }
    make.disabled = d.busy || !can("power") || null;
    make.textContent = d.busy ? T("Backup läuft...") : T("Backup jetzt");
    put(list, d.backups.length ? h("table", null, h("tbody", null, d.backups.map(b => h("tr", null,
      h("td", { class: "num" }, fmt.date(b.modified)),
      h("td", { class: "num dim" }, fmt.bytes(b.size)),
      h("td", { class: "right" }, h("div", { class: "actions", style: { justifyContent: "flex-end" } },
        can("files") ? h("a", { class: "btn small quiet", href: "/api/servers/" + s.name + "/backups/" + b.name, download: b.name }, T("Laden")) : null,
        can("power") ? h("button", { class: "btn small quiet", onclick: async () => {
          if (!(await confirmDialog({ title: T("Welt von ") + fmt.date(b.modified) + T(" zurückholen?"), text: T("Der Server stoppt, die jetzige Welt wird beiseitegelegt (nicht gelöscht), das Backup wird ausgepackt und der Server startet wieder."), ok: T("Zurückholen"), danger: true }))) return;
          const r = await run(T("Zurückgeholt"), () => api("POST", "/servers/" + s.name + "/backups/" + b.name + ":restore", {}));
          if (r) toast(T("Alte Welt liegt in"), r.aside);
          draw();
        } }, T("Zurückholen")) : null,
        can("power") ? h("button", { class: "btn small quiet", "aria-label": T("Backup löschen"), onclick: async () => {
          if (!(await confirmDialog({ title: T("Backup löschen?"), text: fmt.date(b.modified), ok: T("Löschen"), danger: true }))) return;
          await run(T("Gelöscht"), () => api("DELETE", "/servers/" + s.name + "/backups/" + b.name, {}));
          draw();
        } }, "×") : null)))))) : h("p", { class: "empty" }, T("Noch kein Backup. Mit dem Zeitplan geht es auch jede Nacht.")));
    if (d.busy) setTimeout(draw, 4000);
  };
  make.addEventListener("click", async () => { await run(T("Backup gestartet"), () => api("POST", "/servers/" + s.name + "/backups", {})); setTimeout(draw, 800); });
  draw();
  return h("section", { class: "panel" },
    h("header", null, h("div", null, h("h2", null, "Backups"), h("p", null, T("Die Welt als Zip, die neuesten bleiben (backup.keep)."))), make),
    list);
}

function toolsPanel(s) {
  const out = h("div", { class: "stack" });
  const tools = s.tools || [];
  const parts = [];
  if (tools.includes("spark")) {
    const secs = h("select", { "aria-label": T("Dauer") }, [30, 60, 120].map(n => h("option", { value: n }, n + " s")));
    const result = h("p", { class: "muted", style: { margin: "0" } });
    const start = h("button", { class: "btn", disabled: !can("command") || null, onclick: async () => {
      const n = +secs.value;
      start.disabled = true;
      result.textContent = T("Misst ") + n + T(" Sekunden, danach erscheint hier der Link.");
      const stop = (() => { const f = d => {
        if (d.server !== s.name) return;
        const m = /https:\/\/spark\.lucko\.me\/[A-Za-z0-9]+/.exec(d.text);
        if (m) { put(result, T("Fertig: "), h("a", { href: m[0], target: "_blank", rel: "noopener" }, m[0])); start.disabled = false; listeners.line.delete(f); }
      }; listeners.line.add(f); return () => listeners.line.delete(f); })();
      cleanup.push(stop);
      try { await api("POST", "/servers/" + s.name + "/profile", { seconds: n }); } catch (e) { result.textContent = e.message; start.disabled = false; stop(); }
    } }, T("Profiler starten"));
    parts.push(h("div", { class: "stack" }, h("h3", { class: "sub" }, T("Profiler (spark)")),
      h("p", { class: "muted", style: { margin: "0" } }, T("Misst, was den Server bremst, und liefert einen Bericht als Link.")),
      h("div", { class: "actions" }, secs, start), result));
  }
  if (tools.includes("chunky")) {
    const dim = h("select", { "aria-label": T("Dimension") }, ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", ...(s.dimensions || []).map(d => d.name).filter(n => n.includes(":") && !n.startsWith("minecraft:"))]
      .filter((v, i, a) => a.indexOf(v) === i).map(n => h("option", { value: n }, n)));
    const radius = h("input", { type: "number", min: 100, max: 50000, step: 100, value: 3000, "aria-label": T("Radius in Blöcken") });
    const state = h("p", { class: "muted", style: { margin: "0" } });
    const cmd = c => run(T("Gesendet"), () => api("POST", "/servers/" + s.name + "/command", { cmd: c })).then(a => { if (a) state.textContent = String(a).trim().split("\n").pop(); return a; });
    parts.push(h("div", { class: "stack" }, h("h3", { class: "sub" }, T("Welt vorgenerieren (Chunky)")),
      h("p", { class: "muted", style: { margin: "0" } }, T("Erzeugt Chunks im Voraus, damit Spieler später nicht warten. Kostet CPU, am besten wenn wenig los ist.")),
      h("div", { class: "actions" }, dim, radius, h("span", { class: "dim small" }, T("Blöcke um den Spawn"))),
      h("div", { class: "actions" },
        h("button", { class: "btn primary", disabled: !can("command") || null, onclick: async () => { await cmd("chunky world " + dim.value); await cmd("chunky radius " + radius.value); await cmd("chunky start"); } }, T("Starten")),
        h("button", { class: "btn", disabled: !can("command") || null, onclick: () => cmd("chunky pause") }, T("Pause")),
        h("button", { class: "btn", disabled: !can("command") || null, onclick: () => cmd("chunky continue") }, T("Weiter")),
        h("button", { class: "btn quiet", disabled: !can("command") || null, onclick: () => cmd("chunky progress") }, T("Stand"))),
      state));
  }
  put(out, parts);
  return h("section", { class: "panel" }, h("header", null, h("h2", null, T("Werkzeuge"))), h("div", { class: "body" }, out));
}

const PROP_DE = {
  "motd": "Text in der Serverliste", "max-players": "Höchstens so viele Spieler", "view-distance": "Sichtweite in Chunks",
  "simulation-distance": "Simulationsweite in Chunks", "difficulty": "peaceful, easy, normal oder hard", "gamemode": "Spielmodus neuer Spieler",
  "pvp": "Spieler können sich schaden", "white-list": "Nur Spieler auf der Whitelist", "enforce-whitelist": "Wer von der Whitelist fliegt, fliegt raus",
  "spawn-protection": "Radius um den Spawn, den nur Operatoren ändern", "online-mode": "Konten bei Mojang prüfen", "allow-flight": "Fliegen nicht als Cheat werten",
  "max-tick-time": "Nach so vielen ms hängt der Server als abgestürzt (-1: nie)", "level-seed": "Seed einer neuen Welt", "level-name": "Ordner der Welt",
  "hardcore": "Hardcore", "allow-nether": "Nether erlaubt", "spawn-monsters": "Monster erscheinen", "enable-command-block": "Befehlsblöcke",
  "player-idle-timeout": "Minuten bis zum Kick wegen Abwesenheit (0: nie)", "network-compression-threshold": "Ab so vielen Bytes komprimieren",
  "server-port": "Vom Launcher gesetzt (port in seiner Datei)", "rcon.port": "Vom Launcher gesetzt", "enable-rcon": "Vom Launcher gesetzt",
  "rcon.password": "Vom Launcher gesetzt", "accepts-transfers": "Vom Launcher gesetzt (transfers)",
};

function propertiesPanel(s) {
  const body = h("div");
  const filter = h("input", { type: "search", placeholder: T("Einstellung suchen"), "aria-label": T("Einstellung suchen") });
  let rows = [];
  const draw = () => put(body, h("table", { class: "props" }, h("tbody", null, rows.filter(r => !filter.value || r.key.includes(filter.value.toLowerCase())).map(r => {
    const bool = r.value === "true" || r.value === "false";
    const input = bool
      ? h("input", { type: "checkbox", class: "switch", checked: r.value === "true" || null, disabled: r.managed || !can("config") || null })
      : h("input", { type: "text", value: r.value, disabled: r.managed || !can("config") || null, "aria-label": r.key });
    input.addEventListener("change", () => {
      const v = bool ? String(input.checked) : input.value.trim();
      run(r.key + " = " + v, () => api("POST", "/servers/" + s.name + "/properties", { key: r.key, value: v })).then(x => { if (x) r.value = v; });
    });
    return h("tr", null,
      h("td", null, h("span", { class: "mono" }, r.key), PROP_DE[r.key] ? h("div", { class: "dim small" }, T(PROP_DE[r.key])) : null),
      h("td", { class: "right" }, input));
  }))));
  filter.addEventListener("input", draw);
  const d = h("details", { class: "panel props-panel" },
    h("summary", null, "server.properties", h("span", { class: "dim small" }, T("  Änderungen wirken beim nächsten Start"))),
    h("div", { class: "body stack" }, filter, body));
  d.addEventListener("toggle", async () => {
    if (!d.open || rows.length) return;
    try { rows = await api("GET", "/servers/" + s.name + "/properties"); draw(); } catch (e) { put(body, h("p", { class: "error" }, e.message)); }
  }, { once: false });
  return d;
}

/** Shows a code that lets another device of the same person make its own passkey. */
async function pairDevice() {
  let r;
  try { r = await api("POST", "/access/pair", {}); } catch (e) { return toast(T("Kein Code"), e.message, true); }
  const left = h("span", { class: "dim" });
  const d = h("dialog", null, h("form", { method: "dialog" },
    h("h2", null, T("Gerät koppeln")),
    h("p", null, T("Auf dem anderen Gerät ") + S.session.host + T(" öffnen, unten auf koppel es mit einem Code tippen und diesen Code eingeben:")),
    h("div", { class: "paircode" }, r.code),
    h("p", null, T("Gilt einmal, noch "), left, "."),
    h("div", { class: "actions" }, h("button", { class: "btn primary", value: "ok" }, T("Fertig")))));
  const tick = () => {
    const s = Math.max(0, r.expires - Math.floor(Date.now() / 1000));
    left.textContent = Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0") + " min";
    if (s === 0) d.close();
  };
  tick();
  const timer = setInterval(tick, 1000);
  d.addEventListener("close", () => { clearInterval(timer); d.remove(); });
  document.body.append(d);
  d.showModal();
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

// ---- the command line in the palette ---------------------------------------------------------------
// Ctrl K searches and runs. A line that starts with a verb is a command; several are chained
// with && (stop at the first error) or ; (carry on). Tab completes verbs, servers, players,
// streamers and pages. Examples:
//   restart mining && say "Mining ist gleich wieder da"
//   streamer add Elchi_Dev 5 && invite Elchi_Dev KwTester
//   /time set day            (a Minecraft command on the first server)
//   @mining weather clear    (on another server)

const SH = { people: null, roster: null, loaded: 0 };

async function shellData(force) {
  if (!force && SH.people && Date.now() - SH.loaded < 30000) return;
  try {
    const d = await api("GET", "/people");
    SH.people = d.players.map(p => p.name);
    SH.roster = (d.roster?.streamers || []).map(s => s.name);
    SH.loaded = Date.now();
  } catch { SH.people ||= []; SH.roster ||= []; }
}

function tokenize(s) {
  const out = [];
  const re = /"([^"]*)"?|(\S+)/g;
  let m;
  while ((m = re.exec(s))) out.push({ text: m[1] ?? m[2], quoted: m[1] != null, end: re.lastIndex });
  return out;
}

function splitChain(line) {
  const parts = [];
  let cur = "", quote = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') quote = !quote;
    if (!quote && (line.startsWith("&&", i) || c === ";")) {
      parts.push({ text: cur.trim(), stop: line.startsWith("&&", i) });
      cur = "";
      if (c === "&") i++;
      continue;
    }
    cur += c;
  }
  parts.push({ text: cur.trim(), stop: true });
  return parts.filter(p => p.text);
}

const servers = () => S.overview.servers.map(s => s.name);
const minecraftServers = () => S.overview.servers.filter(s => s.type !== "command").map(s => s.name);
const mainServer = () => S.overview.servers[0].name;
const mc = (server, command) => api("POST", "/servers/" + server + "/command", { cmd: command }).then(a => {
  if (/^ERR/.test(a || "")) throw new Error(a.replace(/^ERR\s*/, ""));
  return (a || "").replace(/^OK\s*/, "").trim() || T("erledigt");
});
const forServers = async (name, fn, all = servers) => {
  const list = name ? [name] : all();
  const out = [];
  for (const n of list) out.push(n + ": " + await fn(n));
  return out.join(", ");
};

/** Verbs: words to match, argument kinds, what it says it does, and what it does. */
const VERBS = [
  { w: ["start"], a: ["server?"], say: a => T("Starte ") + (a[0] || T("alle Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/power", { action: "start" })) },
  { w: ["stop"], a: ["server?"], say: a => T("Stoppe ") + (a[0] || T("alle Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/power", { action: "stop" })) },
  { w: ["restart"], a: ["server?"], say: a => T("Starte neu: ") + (a[0] || T("alle Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/power", { action: "restart" })) },
  { w: ["kill"], a: ["server"], say: a => T("Beende hart: ") + a[0], scope: "power",
    run: a => api("POST", "/servers/" + a[0] + "/power", { action: "kill" }) },
  { w: ["run"], a: ["server", "text"], say: a => a[0] + ": /" + a[1], scope: "command", run: a => mc(a[0], a[1]) },
  { w: ["say"], a: ["text"], say: a => T("An alle Server: ") + a[0], scope: "players", run: a => api("POST", "/network/say", { text: a[0] }) },
  { w: ["msg"], a: ["player", "text"], say: a => T("Nachricht an ") + a[0], scope: "players", run: a => mc(onlineServer(a[0]), "tell " + a[0] + " " + a[1]) },
  { w: ["kick"], a: ["player", "text?"], say: a => T("Kicke ") + a[0], scope: "players", run: a => mc(onlineServer(a[0]), "kick " + a[0] + (a[1] ? " " + a[1] : "")) },
  { w: ["ban"], a: ["player", "text?"], say: a => T("Banne ") + a[0], scope: "players", run: a => mc(mainServer(), "ban " + a[0] + (a[1] ? " " + a[1] : "")) },
  { w: ["pardon"], a: ["player"], say: a => T("Entbanne ") + a[0], scope: "players", run: a => mc(mainServer(), "pardon " + a[0]) },
  { w: ["op"], a: ["player"], say: a => "Op: " + a[0], scope: "command", run: a => mc(mainServer(), "op " + a[0]) },
  { w: ["deop"], a: ["player"], say: a => "Deop: " + a[0], scope: "command", run: a => mc(mainServer(), "deop " + a[0]) },
  { w: ["whitelist", "add"], a: ["player"], say: a => T("Whitelist: ") + a[0], scope: "players", run: a => mc(mainServer(), "whitelist add " + a[0]) },
  { w: ["whitelist", "remove"], a: ["player"], say: a => T("Von der Whitelist: ") + a[0], scope: "players", run: a => mc(mainServer(), "whitelist remove " + a[0]) },
  { w: ["streamer", "add"], a: ["player", "num?"], say: a => T("Streamer anlegen: ") + a[0] + (a[1] ? ", " + a[1] + T(" Plätze") : ""), scope: "command", core: true,
    run: a => mc(mainServer(), "kw admin grant " + a[0] + (a[1] ? " " + a[1] : "")) },
  { w: ["add", "streamer"], a: ["player", "num?"], say: a => T("Streamer anlegen: ") + a[0] + (a[1] ? ", " + a[1] + T(" Plätze") : ""), scope: "command", core: true, hidden: true,
    run: a => mc(mainServer(), "kw admin grant " + a[0] + (a[1] ? " " + a[1] : "")) },
  { w: ["streamer", "remove"], a: ["streamer"], say: a => T("Streamer entfernen: ") + a[0], scope: "command", core: true, run: a => mc(mainServer(), "kw admin ungrant " + a[0]) },
  { w: ["slots"], a: ["streamer", "num"], say: a => a[0] + ": " + a[1] + T(" Plätze"), scope: "command", core: true, run: a => mc(mainServer(), "kw admin slots " + a[0] + " " + a[1]) },
  { w: ["whitelist", "slots"], a: ["streamer", "num"], say: a => a[0] + ": " + a[1] + T(" Plätze"), scope: "command", core: true, hidden: true, run: a => mc(mainServer(), "kw admin slots " + a[0] + " " + a[1]) },
  { w: ["bonus"], a: ["streamer", "num"], say: a => a[0] + ": Bonus " + a[1], scope: "command", core: true, run: a => mc(mainServer(), "kw admin bonus " + a[0] + " " + a[1]) },
  { w: ["invite"], a: ["streamer", "player"], say: a => a[1] + T(" auf einen Platz von ") + a[0], scope: "command", core: true, run: a => mc(mainServer(), "kw admin invite " + a[0] + " " + a[1]) },
  { w: ["revoke"], a: ["streamer", "player"], say: a => T("Platz frei: ") + a[1] + T(" bei ") + a[0], scope: "command", core: true, run: a => mc(mainServer(), "kw admin revoke " + a[0] + " " + a[1]) },
  { w: ["backup"], a: ["server?"], say: a => T("Backup: ") + (a[0] || T("alle Minecraft-Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/backups", {}), minecraftServers) },
  { w: ["maintenance", "on"], a: ["server?"], say: a => T("Wartung an: ") + (a[0] || T("alle Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/maintenance", { on: true }).then(r => r.sent.length ? T("rausgeschickt: ") + r.sent.join(", ") : T("an")), minecraftServers) },
  { w: ["maintenance", "off"], a: ["server?"], say: a => T("Wartung aus: ") + (a[0] || T("alle Server")), scope: "power",
    run: a => forServers(a[0], n => api("POST", "/servers/" + n + "/maintenance", { on: false }).then(() => T("aus")), minecraftServers) },
  { w: ["pack", "update"], a: [], say: () => T("Pack aktualisieren (alle Server starten neu)"), scope: "pack", run: () => api("POST", "/pack/update", {}) },
  { w: ["reload"], a: [], say: () => T("Launcher neu laden"), scope: "power", run: () => api("POST", "/launcher/reload", {}) },
  { w: ["wait"], a: ["num"], say: a => T("Warte ") + a[0] + " s", run: a => new Promise(r => setTimeout(() => r(T("gewartet")), Math.min(600, +a[0]) * 1000)) },
  { w: ["go"], a: ["page"], say: a => T("Gehe zu ") + a[0], run: a => { const p = PAGES.find(x => T(x.name).toLowerCase().startsWith(a[0].toLowerCase()) || x.path.slice(1).startsWith(a[0].toLowerCase())); if (!p) throw new Error(T("Keine Seite ") + a[0]); go(p.path); return T("da"); } },
  { w: ["open"], a: ["server"], say: a => T("Öffne ") + a[0], run: a => { go("/server/" + a[0]); return T("da"); } },
  { w: ["console"], a: ["server?"], say: a => T("Konsole ") + (a[0] || mainServer()), run: a => { go("/konsole/" + (a[0] || mainServer())); return T("da"); } },
  { w: ["new", "server"], a: [], say: () => T("Neuer Server"), scope: "config", run: () => { newServerWizard(); return T("Assistent offen"); } },
];

function onlineServer(player) {
  const s = S.overview.servers.find(x => x.players.some(p => p.toLowerCase() === player.toLowerCase()));
  return s ? s.name : mainServer();
}

const verbsHere = () => VERBS.filter(v => (!v.scope || can(v.scope)) && (!v.core || S.session.season));

/** Parses one command. Returns {verb, args, say} or {error} or null when it is not a command. */
function parseOne(text) {
  if (text.startsWith("/")) return can("command") || can("players") ? { args: [], say: mainServer() + ": " + text, run: () => mc(mainServer(), text.slice(1)) } : { error: T("Keine Befehle erlaubt") };
  const at = /^@(\S+)\s+(.+)$/.exec(text);
  if (at) {
    if (!servers().includes(at[1])) return { error: T("Kein Server ") + at[1] };
    return { args: [], say: at[1] + ": /" + at[2], run: () => mc(at[1], at[2].replace(/^\//, "")) };
  }
  const toks = tokenize(text);
  const v = verbsHere().find(v => v.w.every((w, i) => (toks[i]?.text || "").toLowerCase() === w));
  if (!v) return null;
  const rest = toks.slice(v.w.length);
  const args = [];
  for (let i = 0; i < v.a.length; i++) {
    const kind = v.a[i], opt = kind.endsWith("?"), base = kind.replace("?", "");
    if (base === "text") {
      const from = rest[i];
      if (!from) { if (opt) break; return { error: T("Es fehlt: Text") }; }
      const prevEnd = toks[v.w.length + i - 1]?.end ?? 0;
      const raw = text.slice(prevEnd).trim();
      args.push(/^"[^"]*"$/.test(raw) ? raw.slice(1, -1) : raw);
      break;
    }
    const t = rest[i]?.text;
    if (t == null) { if (opt) continue; return { error: T("Es fehlt: ") + T(ARG_NAME[base]) }; }
    if (base === "server" && !servers().includes(t)) return { error: T("Kein Server ") + t };
    if (base === "num" && !/^-?\d+$/.test(t)) return { error: t + T(" ist keine Zahl") };
    if (base === "player" && !/^[A-Za-z0-9_]{2,16}$/.test(t)) return { error: t + T(" ist kein Minecraft-Name") };
    args.push(t);
  }
  return { verb: v, args, say: v.say(args), run: () => v.run(args) };
}

const ARG_NAME = { server: "Server", player: "Spieler", streamer: "Streamer", num: "Zahl", text: "Text", page: "Seite" };

/** Candidates for the word under the cursor. */
function complete(line) {
  const parts = splitChain(line + "\u0000");
  const seg = (parts[parts.length - 1]?.text || "").replace("\u0000", "");
  const toks = tokenize(seg);
  const endsSpace = /\s$/.test(seg) || seg === "";
  const cur = endsSpace ? "" : (toks[toks.length - 1]?.text || "");
  const done = endsSpace ? toks : toks.slice(0, -1);
  const lower = cur.toLowerCase();
  if (seg.startsWith("@") && done.length === 0) return servers().map(s => "@" + s + " ").filter(s => s.startsWith(cur));
  if (seg.startsWith("@") || seg.startsWith("/")) return [];
  const words = done.map(t => t.text.toLowerCase());
  const cands = new Set();
  for (const v of verbsHere()) {
    if (v.hidden && words.length < v.w.length) continue;
    let ok = true;
    for (let i = 0; i < Math.min(words.length, v.w.length); i++) if (v.w[i] !== words[i]) ok = false;
    if (!ok) continue;
    if (words.length < v.w.length) { cands.add(v.w[words.length]); continue; }
    const kind = (v.a[words.length - v.w.length] || "").replace("?", "");
    const list = kind === "server" ? servers() : kind === "player" ? (SH.people || []) : kind === "streamer" ? (SH.roster || [])
      : kind === "page" ? PAGES.filter(p => !p.hidden).map(p => p.path.slice(1) || "overview") : [];
    list.forEach(x => cands.add(x));
  }
  return [...cands].filter(c => c.toLowerCase().startsWith(lower) && c.toLowerCase() !== lower).sort().slice(0, 12)
    .map(c => line.slice(0, line.length - cur.length) + (/\s/.test(c) ? '"' + c + '"' : c) + " ");
}

function openPalette(initial) {
  if ($("dialog.palette")) return;
  shellData();
  const input = h("input", { type: "text", value: initial || "", placeholder: T("Suchen, oder einen Befehl: restart mining && say Hallo"), "aria-label": T("Suchen oder Befehl"), spellcheck: "false", autocomplete: "off" });
  const list = h("ul", { role: "listbox" });
  const out = h("div", { class: "pal-out" });
  const hint = h("div", { class: "pal-hint dim" }, T("Tab ergänzt, Enter führt aus, && verkettet, ; macht trotz Fehler weiter. help zeigt alle Befehle."));
  const d = h("dialog", { class: "palette" }, input, list, out, hint);
  let items = paletteItems(), shown = [], sel = 0, hist = storage("palette") || [], at = hist.length;
  const plan = () => {
    const parts = splitChain(input.value.trim());
    if (!parts.length) return null;
    const steps = parts.map(p => ({ ...parseOne(p.text), stop: p.stop, text: p.text }));
    if (steps.some(s => s.error === undefined && !s.run)) return null;
    if (!steps[0].run && !steps[0].error) return null;
    return steps;
  };
  const draw = () => {
    const q = input.value.toLowerCase().trim();
    const steps = plan();
    const comps = q ? complete(input.value) : [];
    const rows = [];
    if (q === "help" || q === "hilfe") {
      for (const v of verbsHere().filter(v => !v.hidden)) rows.push({ kind: "help", name: v.w.join(" ") + " " + v.a.map(a => "<" + T(ARG_NAME[a.replace("?", "")]) + (a.endsWith("?") ? "?" : "") + ">").join(" "), hint: "", act: () => { input.value = v.w.join(" ") + " "; draw(); } });
    } else if (steps) {
      const bad = steps.find(s => s.error);
      rows.push({ kind: "run", name: bad ? bad.error : steps.map(s => s.say).join(steps.length > 1 ? "  >  " : ""), hint: bad ? T("unvollständig") : T("Enter führt aus"), bad: !!bad, act: () => runSteps(steps) });
    }
    for (const c of comps) rows.push({ kind: "comp", name: c.trim(), hint: "Tab", act: () => { input.value = c; draw(); } });
    if (!steps) {
      for (const i of items.filter(i => !q || q.split(/\s+/).every(w => (i.name + " " + i.hint).toLowerCase().includes(w))).slice(0, 25)) rows.push({ kind: "item", ...i });
    }
    shown = rows;
    sel = Math.min(sel, Math.max(0, shown.length - 1));
    put(list, shown.map((r, k) => h("li", { role: "option", class: r.kind + (r.bad ? " bad" : ""), "aria-selected": String(k === sel), onmousedown: e => { e.preventDefault(); sel = k; pick(); } },
      r.kind === "run" ? h("span", null, h("b", null, r.bad ? "" : T("Ausführen: ")), r.name) : r.name, h("small", null, r.hint))));
  };
  const pick = () => {
    const r = shown[sel];
    if (!r) return;
    if (r.kind === "item") { d.close(); r.act(); } else if (!r.bad) r.act();
  };
  const runSteps = async steps => {
    const line = input.value.trim();
    hist = [...hist.filter(x => x !== line), line].slice(-50);
    storage("palette", hist);
    at = hist.length;
    input.value = "";
    draw();
    for (const s of steps) {
      const row = h("div", { class: "pal-line" }, h("span", { class: "pill" }, "..."), h("span", null, s.say));
      out.append(row);
      out.scrollTop = out.scrollHeight;
      try {
        const r = await s.run();
        row.firstChild.replaceWith(h("span", { class: "pill ok" }, "ok"));
        if (r && typeof r === "string" && r !== T("da")) row.append(h("span", { class: "dim" }, " " + (r.length > 200 ? r.slice(0, 200) + "..." : r)));
      } catch (e) {
        row.firstChild.replaceWith(h("span", { class: "pill bad" }, T("Fehler")));
        row.append(h("span", { class: "dim" }, " " + e.message));
        if (s.stop) break;
      }
    }
    shellData(true);
    refreshOverview();
  };
  input.addEventListener("input", () => { sel = 0; draw(); });
  input.addEventListener("keydown", e => {
    if (e.key === "Tab") {
      e.preventDefault();
      const c = complete(input.value);
      if (c.length) { input.value = c[0]; sel = 0; draw(); }
    } else if ((e.key === "ArrowUp" || e.key === "ArrowDown") && e.altKey) {
      e.preventDefault();
      at = Math.max(0, Math.min(hist.length, at + (e.key === "ArrowUp" ? -1 : 1)));
      input.value = hist[at] || "";
      draw();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!shown.length) return;
      sel = (sel + (e.key === "ArrowDown" ? 1 : -1) + shown.length) % shown.length;
      draw();
      $$("li", list)[sel]?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") {
      e.preventDefault();
      const steps = plan();
      if (steps && !steps.some(s => s.error)) runSteps(steps); else pick();
    }
  });
  d.addEventListener("close", () => d.remove());
  d.addEventListener("click", e => { if (e.target === d) d.close(); });
  document.body.append(d);
  draw();
  d.showModal();
  input.focus();
  shellData().then(draw);
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
    if (path === "/pair") return door("pair");
    return door("login");
  }
  if (path === "/setup" || path === "/invite" || path === "/pair") history.replaceState(null, "", "/");
  try {
    S.overview = await api("GET", "/overview");
  } catch (e) {
    toast(T("Übersicht nicht geladen"), e.message, true);
    return;
  }
  shell();
  route();
  startStream();
  if (!S.session.named && S.session.user.role === "owner" && !$("dialog.wizard")) setTimeout(welcomeWizard, 400);
}

start();
