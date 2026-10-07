# Changelog

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
