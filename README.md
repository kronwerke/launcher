```
 _  __                                 _        
| |/ /_ __ ___  _ ____      _____ _ __| | _____ 
| ' /| '__/ _ \| '_ \ \ /\ / / _ \ '__| |/ / _ \
| . \| | | (_) | | | \ V  V /  __/ |  |   <  __/
|_|\_\_|  \___/|_| |_|\_/\_/ \___|_|  |_|\_\___|
                                                
                     l a u n c h e r
```

**The jar the Kronwerke server starts: it runs every Kronwerke server in the container, keeps the pack up to date, serves the web console at console.kronwerke.com, and lets the Discord bot reach the servers without any API from the host.**

[![ci](https://github.com/kronwerke/launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/kronwerke/launcher/actions/workflows/ci.yml)
![status](https://img.shields.io/badge/status-early-orange)
![java](https://img.shields.io/badge/java-21-blue)
![licence](https://img.shields.io/badge/licence-MIT-green)
[![made by](https://img.shields.io/badge/made%20by-Elchi-black)](https://github.com/Elchi-dev)

## Overview

The Kronwerke server runs at a host with a game panel and no API: start, stop, the console and the files are buttons in a browser. That is fine for a person and useless for the [Discord bot](https://github.com/kronwerke/bot), which has to whitelist players, report crashes and roll out pack updates while nobody is watching.

The panel does let us choose the jar it starts. So the jar is this launcher. It stays up for as long as the container runs, and Minecraft runs as its child.

## The trick

The launcher dials out to the bot, so the bot needs no way into the container. The console is the one door in, and only Cloudflare can open it.

```
  panel "start"
       |
       v
  launcher (the panel's jar) ---- WebSocket, outbound ----> bot (/link)
       |   packwiz: mods and configs to the state of the pack repository
       |   NeoForge installed if the pack wants another version
       |   console on HTTPS, Cloudflare's client certificate required
       v
  main (child process)          mining (child process)       ...
       own folder, own ports, RCON on loopback, the same mods
```

Because the launcher outlives Minecraft, a restart, a pack update or a crash does not need the panel. And because the processes belong to a small boot part that never reloads, a new launcher version takes over running servers without stopping them.

## Parts

| Part | What it does |
| --- | --- |
| `boot.Boot` | The jar's entry point: the panel's console, the running processes, and loading the launcher itself so it can be replaced while the servers run |
| `boot.Pump` | One server process and the thread reading its output, handed from launcher to launcher |
| `Main` | The launcher as Boot starts it, the console lines typed in the panel |
| `Fleet` | Every server: start order, pack updates for all, memory check, CPU shares and pinning, the samples every ten seconds |
| `Server` | One server: its folder, its process, its state, restarts after a crash |
| `Pack` | packwiz and the NeoForge installer |
| `Link` | The connection to the bot and what it may ask |
| `web.Web`, `web.Api` | console.kronwerke.com: the page and the JSON API behind it |
| `web.Access` | Passkeys, sessions, invites and API keys, all secrets stored as hashes |
| `web.Tls` | The certificate and Cloudflare's client certificate check |
| `ServerFiles` | File access for the link and the console, keys hidden |
| `Rcon`, `Proc`, `Metrics` | Commands to Minecraft on loopback, what Linux says about the processes, the last hour of samples |
| `Updater` | A new launcher from a release, taking over at once |
| `Config`, `Json` | The properties files and just enough JSON; the launcher has no dependencies |

## Quick look

```
sh build.sh
cd /path/to/server && java -jar kronwerke-launcher.jar
```

The first start writes `kronwerke/launcher.properties`, downloads the pack, installs NeoForge and starts Minecraft. On a panel, see [docs/SETUP.md](docs/SETUP.md).

In the panel's console, besides every Minecraft command for the first server:

| Line | What |
| --- | --- |
| `stop` | Stop every server, then the launcher (what the panel sends) |
| `@mining <command>` | A command for another server |
| `kronwerke status` | State of every server |
| `kronwerke start\|stop\|restart [name]` | One server, or all without a name; the launcher stays |
| `kronwerke update` | Pack update: stop all, update, start again |
| `kronwerke reload` | Load the launcher afresh; the servers keep running |

Over the link the bot can ask for: `status`, `command`, `start`, `stop`, `restart` (optionally with an update), `console`, `logs`, `ls`, `read`, `write`, `delete`, `follow`, `launcher-update` and `reload`, each with an optional `server`. Writing is limited to config, kubejs, mods and a few files like `server.properties`; the world is read only, and keys cannot be read at all.

## The console

console.kronwerke.com, served by the launcher: every server live (tick time over the last hour, CPU, memory, players), the console of each with commands, players across servers, the season and the obelisk's goals, pack updates, files, CPU shares and heaps, the timeline, who did what, crash reports, and people and keys. Sign in with a passkey; there are no passwords. Programs use API keys with scopes. See [docs/CONSOLE.md](docs/CONSOLE.md).

## Planned

- Kronwerke Core on the bus: chat and tablist across servers, moving players between them. See [docs/DESIGN.md](docs/DESIGN.md).
- Chat and join events to the bot, for a bridge to Discord.

## Non-goals

- Replacing the panel. Its console, schedules and backups keep working.
- A plugin or mod. The launcher works whether Minecraft is up or not, which a mod cannot.

## Status

0.1 runs the live server since September 2026. 0.2 (several servers, reload, console) is tested with stand in servers: two servers, reload with running processes, crash, pack update order, the panel's stop, the link, passkeys in a real browser with a virtual authenticator, API keys, Cloudflare's client certificate check.

## Docs

| Page | What |
| --- | --- |
| [docs/SETUP.md](docs/SETUP.md) | Putting it on a server with a game panel |
| [docs/CONSOLE.md](docs/CONSOLE.md) | The console: first passkey, people, keys, the API |
| [docs/DESIGN.md](docs/DESIGN.md) | More than one server, moving players, the plan |
| [CHANGELOG.md](CHANGELOG.md) | What changed per version |

## Licence

MIT. See `LICENSE`.

Made by [Elchi](https://github.com/Elchi-dev)
