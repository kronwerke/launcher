# Changelog

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
