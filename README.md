```
 _  __                                 _        
| |/ /_ __ ___  _ ____      _____ _ __| | _____ 
| ' /| '__/ _ \| '_ \ \ /\ / / _ \ '__| |/ / _ \
| . \| | | (_) | | | \ V  V /  __/ |  |   <  __/
|_|\_\_|  \___/|_| |_|\_/\_/ \___|_|  |_|\_\___|
                                                
                     l a u n c h e r
```

**One jar that runs all your game servers inside a single container: Minecraft (NeoForge, Paper, Fabric, vanilla) or any other program, with a web console, passkeys, an API, live CPU sharing and updates that never stop a running server.**

[![ci](https://github.com/kronwerke/launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/kronwerke/launcher/actions/workflows/ci.yml)
![status](https://img.shields.io/badge/status-early-orange)
![java](https://img.shields.io/badge/java-21-blue)
![licence](https://img.shields.io/badge/licence-MIT-green)
[![made by](https://img.shields.io/badge/made%20by-Elchi-black)](https://github.com/Elchi-dev)

## Overview

Game hosts give you a container, a panel with a console and a file manager, and a start command that runs one jar. That is enough for one server and a person clicking buttons. It is not enough for a network of servers, for a team that needs roles, or for scripts that need an API.

Upload this launcher as that one jar. It becomes the container's supervisor: it starts every server you describe in a small properties file, restarts them after a crash, shares the container's CPUs and memory between them, keeps a modpack up to date, and serves a web console with passkey sign in and a JSON API. The host's panel keeps working as before; nothing needs support from the host.

Written for [Kronwerke](https://kronwerke.com), a modded Minecraft server for streamers, and used there every day. Nothing in it is tied to Kronwerke.

## What it does

- **Several servers in one container.** One file per server in `launcher/servers`. A second server can link the first one's mods and configs, so one update covers both.
- **Any server.** `type=neoforge` (version from a packwiz pack or set by hand, installed for you), `type=jar` (Paper, Fabric, vanilla, anything that is a jar), `type=command` (any program, with its own stop command and a "ready" pattern).
- **Never down for an update.** A small boot part owns the running processes and the panel's console; a new launcher version is loaded next to it and takes over the servers while they run.
- **Crashes handled.** A crashed server starts again after 15 seconds; three crashes in ten minutes and it waits for a person.
- **CPU and memory.** Each server has a CPU share; with pinning on, each gets its share of the container's CPUs, and the shares can move by themselves to a server whose tick time suffers. A start that would not fit into memory is refused.
- **Watching.** Every ten seconds: CPU, memory, tick time per dimension and players of every server, kept for an hour.
- **Modpacks.** A packwiz pack is installed before a start with an update: every server stops, the pack updates once, they start again in order.
- **Install from the browser.** Paper, Purpur, Folia, Fabric, NeoForge, Vanilla, Velocity or your own jar, with a wizard for new servers and one to change a server's software.
- **Mods and plugins.** Every jar matched against Modrinth and CurseForge, with updates, search, install with dependencies and removal.
- **The web console.** Live overview with the tick time of the last hour, console with commands, history and completion, every player the server knows, files with drag and drop, CPU and memory, timeline, audit log, crash reports, people and API keys, a command palette. English and German, your name, colour and logo.
- **Safe by default.** Passkeys only, no passwords. Roles, one time invites, API keys with scopes stored as hashes, an audit log of every change. Behind Cloudflare, only Cloudflare's client certificate gets through.

## How it fits together

```
  panel "start"
       |
       v
  launcher (the panel's jar)
       |   boot part: the panel's console, the running processes, loading the launcher
       |   console on HTTPS (Cloudflare's client certificate required), JSON API
       |   optional: outbound WebSocket to a controller (a Discord bot, say)
       v
  server A (child process)      server B (child process)      ...
       own folder and ports, RCON on loopback for Minecraft
```

## Quick look

```
sh build.sh
cd /path/to/server && java -jar launcher.jar
```

The first start writes `launcher/launcher.properties` and `launcher/servers/main.properties` with every setting explained, and waits: tell `main.properties` what to run (`jar=paper.jar`, say) or set `pack.url` for a NeoForge pack, then `launcher reload`. On a game panel, see [docs/SETUP.md](docs/SETUP.md).

In the panel's console, besides the first server's own commands:

| Line | What |
| --- | --- |
| `stop` | Stop every server, then the launcher (what the panel sends) |
| `@name <command>` | A command for another server |
| `launcher status` | State of every server |
| `launcher start\|stop\|restart [name]` | One server, or all without a name; the launcher stays |
| `launcher update` | Pack update: stop all, update, start again |
| `launcher reload` | Load the launcher afresh; the servers keep running |

## Parts

| Part | What it does |
| --- | --- |
| `boot.Boot`, `boot.Pump` | The jar's entry point, the processes and their output, handed from launcher version to launcher version |
| `Main` | The launcher as Boot starts it, the panel's console lines |
| `Fleet` | Every server: start order, pack updates, memory check, CPU shares and pinning, samples |
| `Server` | One server: its folder, its process, its state, restarts after a crash |
| `Pack` | packwiz and the NeoForge installer |
| `web.Web`, `web.Api` | The console page and its JSON API |
| `web.Access`, `web.Tls` | Passkeys, sessions, invites, keys; certificates and the client certificate check |
| `Link` | The optional outbound connection to a controller |
| `ServerFiles`, `Rcon`, `Proc`, `Metrics`, `Updater` | Files with keys hidden, RCON, what Linux says, the samples, releases |
| `Config`, `Home`, `Json` | Properties files, where they live, just enough JSON. No dependencies at all |

## Status

Early, but in daily use: Kronwerke runs on it since September 2026. Tested with real and stand in servers: several servers, reload with running processes, crashes, pack update order, the panel's stop, passkeys in a real browser, API keys, Cloudflare's client certificate check.

## Docs

| Page | What |
| --- | --- |
| [docs/SETUP.md](docs/SETUP.md) | On a game panel, more servers, every setting |
| [docs/CONSOLE.md](docs/CONSOLE.md) | The console: Cloudflare, first passkey, people, keys, the API |
| [docs/KRONWERKE.md](docs/KRONWERKE.md) | How Kronwerke uses it: a mining server, moving players between servers |
| [CHANGELOG.md](CHANGELOG.md) | What changed per version |

## Licence

MIT. See `LICENSE`. Fonts in the console: Schibsted Grotesk and JetBrains Mono, both under the SIL Open Font License (texts next to the fonts).

Made by [Elchi](https://github.com/Elchi-dev)
