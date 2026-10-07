# Changelog

## 0.6.1

- The "stopped" alert only comes for a stop that stays, not for every restart.
- `backup.keep` can be changed from the console.

## 0.6.0

- Automation page: a schedule of restarts (with warnings to the players at chosen minutes before), stops, starts, commands, messages and backups, daily at a time on chosen weekdays or every few hours; run any task now. `timezone` sets the clock.
- Alerts to a Discord webhook or any https URL: crash, a server that gave up after three crashes, tick time above a limit for a minute, backups done or failed, starts and stops. The URL stays in `console/alerts.json` and is never shown again; a test button.
- Backups of a Minecraft server's world: saving paused, zipped into `launcher/backups/<name>`, the newest `backup.keep` stay. Download, delete, restore (the current world is moved aside, never deleted).
- Maintenance mode per server: only operators may join, everyone else is sent away with a message, at once and on every join.
- A form for server.properties with a word on the common keys; keys the launcher sets are read only.
- Search through the logs, the packed ones of earlier days included.
- Playtime: every join and leave the network sees, per player in the players list and their side panel with the last sessions.
- Tools on a server's page when their mod or plugin is there: spark's profiler (the report link shows when ready) and Chunky's pregeneration (dimension, radius, start, pause, continue, progress).
- Command line: `backup [server]`, `maintenance on|off [server]`.

## 0.5.1

- Mods may send `extra` with chat, joins and leaves (a rank, say); the other mods get it untouched.

## 0.5.0

Networks: servers that belong together share chat, joins, lists and, with a mod, the tab list and player data. Details in [docs/BUS.md](docs/BUS.md).

- `network=` in a server's file puts it into a network; `sync.chat` (`network`, `server`, `radius` with `chat.radius`), `sync.joins`, `sync.tablist`, `sync.lists`, `sync.players` say what it shares, `label` how the others name it.
- The bridge: chat and joins of any Minecraft server, read from its console and shown on the other servers with `tellraw`, no mod needed. Joins and leaves in each player's own language.
- The bus (`bus.port`, loopback only): mods sign in with an HMAC over a nonce and the key in `bus.key`, report chat, joins and players, get the others' players for their tab list, and send their own messages to the other servers. A mod on the bus takes over from the bridge for its server.
- Lists: a server of a network links the first one's whitelist, every server reads it again when it changes, and `ban`, `pardon`, `op`, `deop`, `whitelist add|remove` from any console run on all of them.
- A new server in a network starts with the first server's `server.properties`, with its own world, ports and RCON.
- The console's Network page: a map of the networks where every message travels as a light, the chat of every server live with a filter, writing to the players of one or all servers, every setting, the bus and its port. `say` in the command line writes to every server.
- The new server wizard: same pack as the first server (mods and configs linked), network, Simple Voice Chat port.
- Colours without a setting follow the start order, not the server's name.

## 0.4.0

- The palette (Ctrl K) is a command line too: `start|stop|restart|kill [server]`, `run <server> <command>`, `/command`, `@server command`, `say`, `msg`, `kick`, `ban`, `pardon`, `op`, `deop`, `whitelist add|remove`, with Kronwerke Core `streamer add|remove`, `slots`, `bonus`, `invite`, `revoke`, and `pack update`, `reload`, `wait`, `go`, `open`, `console`, `new server`. Chains with `&&` (stop at an error) and `;` (carry on), Tab completes verbs, servers, players, streamers and pages, `help` lists everything, Alt and the arrow keys walk the history. Each step reports back in the palette.
- Pairing a device: a signed in person gets a code (ten minutes, once) under Access; typed on another device at "pair it with a code", it makes that device a passkey of its own.
- Members who linked their Minecraft name on Discord (pushed by the Kronwerke bot) show with the players and as "Linked on Discord" next to the streamers, newest marked, with a button to give them slots.

## 0.3.1

- The season page also finds Kronwerke Core when its jar is called kronwerke-<version>.jar.

## 0.3.0

For everyone, not only Kronwerke.

- Server types: `neoforge` (the version from the pack or `neoforge=`, installed into the server's own folder), `jar` (Paper, Fabric, vanilla, any jar), `command` (any program, with `stop` and a `ready` pattern). RCON, EULA and server.properties only for Minecraft.
- Server software from the console: Paper, Purpur, Folia, Fabric, NeoForge, Vanilla, Velocity with their version lists, checked against the makers' checksums, or an uploaded jar. A wizard for a new server (software, version, name, port, memory, EULA), one to change a server's software, and a welcome wizard for name, language and colour.
- A page per server: software, folder, port, memory, JVM flags, start settings, removing it (its folder stays).
- Mods and plugins: every jar matched by hash against Modrinth and CurseForge with name, icon, summary and the newest version for the server's loader and Minecraft version; search Modrinth (only what runs on a server), install with required dependencies, update, remove (into `.removed`). CurseForge goes through a proxy that holds the key (`curseforge.proxy`), or an own key in `console/curseforge.key`.
- Players: everyone the server knows, online or not (usercache, whitelist, operators, bans), with a side panel per player for messages, kicks, whitelist, op and bans. With Kronwerke Core also its streamers and their slots.
- White label: the launcher's folder is whichever holds `launcher.properties` (`launcher/` on a new install), `name`, `console.accent` and a `logo.svg` or `logo.png` next to it brand the console; codes, keys and the cookie carry no name. The release jar is `launcher.jar`.
- The console speaks English and German (`console.language`), with colours per server (`color`). The season page only shows with Kronwerke Core (`console.season`).
- Fixes: the command line sits above the console's lines; CPU in percent of one core like the panels; memory like `docker stats`, its limit from the panel's `SERVER_MEMORY`; the disk counted every five minutes, its quota from `container.disk`; inline styles in the console were ignored (the API key dialog fell apart).
- More to touch: values under the cursor on the tick time, a notice when a server crashes, player names in chat lines open the player, files dropped on the files page are uploaded.
- `transfers` sets accepts-transfers; `share` and `own` choose what a second server links or copies.
- Java servers get `-Dlauncher.server`, `-Dlauncher.role`, `-Dlauncher.bus` and `-Dlauncher.bus.key` (were `kronwerke.*`).
- `launcher status|start|stop|restart|update|reload` in the panel's console; `kronwerke ...` still works. API keys start with `key_`; `kwc_` keys keep working. Sessions from 0.2 end once.

## 0.2.0

More than one server, and the console.

- Every server has a file in `kronwerke/servers`. A 0.1 install becomes one server, `main`, with its memory and start settings taken over. A second server gets its own folder with links to the first one's mods, kubejs, libraries and configs; a config folder it needs for itself (Simple Voice Chat's, for its port) is copied instead.
- One pack update for all: every server stops (the last in the start order first), packwiz runs once, the ones that ran start again.
- A start that would not fit into the container's memory is refused with the numbers.
- CPU shares per server; with `cpu.pin` each server is pinned to its share of the CPUs, and `cpu.balance` moves a share to a server whose tick time stays above 40 ms while another has room.
- Every ten seconds: CPU, memory, tick time per dimension and players of every server, kept for an hour.
- The jar starts a small boot part that keeps the panel's console and the running servers. A new launcher version loads next to it and takes over the servers without stopping them, with their console, samples and timeline. A broken version falls back to the jar the panel starts.
- `launcher-update` installs and takes over at once; `reload` loads the launcher afresh.
- The console at console.kronwerke.com: live overview with the tick time of the last hour, console with commands, history and completion, players, season and goals, pack and mods, files, resources, timeline, audit, crash reports, people and keys, a command palette. Passkeys only, API keys with scopes, every change in an audit log, only Cloudflare can connect. See docs/CONSOLE.md.
- The link's ops take an optional `server`; `status` lists every server.
- `kronwerke status|start|stop|restart [name]`, `kronwerke reload` and `@name <command>` in the panel's console.

## 0.1.0

First version.

- Runs Minecraft as a child process; the panel's console and stop work as before.
- Before every start: the pack through packwiz (packwiz-installer is fetched directly, so GitHub's API is not needed), NeoForge in the version the pack names, RCON on with a random password.
- Minecraft is not started while `eula.txt` is not accepted. It counts as running once RCON is up. A crash starts it again after 15 seconds; three crashes in ten minutes and it waits.
- The link to the Discord bot: an outbound WebSocket, a key made on the first start, and requests for status, commands, start, stop, restart with or without an update, console, logs, files and a launcher update. It reconnects on its own.
- `kronwerke status|restart|update|start|stop` in the console.
- No dependencies: `build.sh` needs a JDK and nothing else, and runs the tests.
