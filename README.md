```
 _  __                                 _        
| |/ /_ __ ___  _ ____      _____ _ __| | _____ 
| ' /| '__/ _ \| '_ \ \ /\ / / _ \ '__| |/ / _ \
| . \| | | (_) | | | \ V  V /  __/ |  |   <  __/
|_|\_\_|  \___/|_| |_|\_/\_/ \___|_|  |_|\_\___|
                                                
                     l a u n c h e r
```

**The jar the Kronwerke server starts: it brings the pack up to date, runs Minecraft, and lets the Discord bot start, stop and read the server without any API from the host.**

[![ci](https://github.com/kronwerke/launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/kronwerke/launcher/actions/workflows/ci.yml)
![status](https://img.shields.io/badge/status-early-orange)
![java](https://img.shields.io/badge/java-21-blue)
![licence](https://img.shields.io/badge/licence-MIT-green)
[![made by](https://img.shields.io/badge/made%20by-Elchi-black)](https://github.com/Elchi-dev)

## Overview

The Kronwerke server runs at a host with a game panel and no API: start, stop, the console and the files are buttons in a browser. That is fine for a person and useless for the [Discord bot](https://github.com/kronwerke/bot), which has to whitelist players, report crashes and roll out pack updates while nobody is watching.

The panel does let us choose the jar it starts. So the jar is this launcher. It stays up for as long as the container runs, and Minecraft runs as its child.

## The trick

The launcher dials out to the bot, so the server needs no open port and the host needs to offer nothing.

```
  panel "start"
       |
       v
  launcher (server.jar) ---- WebSocket, outbound ----> bot (/link) <---- control channel
       |   1. packwiz: mods and configs to the state of the pack repository
       |   2. NeoForge installed if the pack wants another version
       |   3. RCON on, with a random password, on loopback only
       v
  Minecraft (child process)
       stdout -> the panel's console, as before
       stdin  <- the panel's console, as before
```

Because the launcher outlives Minecraft, a restart, a pack update or a crash does not need the panel: the launcher stops the child, updates, and starts it again. Three crashes in ten minutes and it waits for a person.

## Parts

| Part | What it does |
| --- | --- |
| `Main` | Entry point, the console, the panel's stop |
| `Server` | Runs Minecraft, knows its state, restarts it after a crash |
| `Pack` | packwiz and the NeoForge installer |
| `Link` | The connection to the bot and what it may ask |
| `ServerFiles` | File access for the link, kept inside the server folder |
| `Rcon` | Commands to Minecraft on loopback |
| `Config` | `kronwerke/launcher.properties`, written with every default on the first start |
| `Json` | Just enough JSON; the launcher has no dependencies |

## Quick look

```
sh build.sh
cd /path/to/server && java -jar kronwerke-launcher.jar
```

The first start writes `kronwerke/launcher.properties`, downloads the pack, installs NeoForge and starts Minecraft. On a panel, see [docs/SETUP.md](docs/SETUP.md).

In the console, besides every Minecraft command:

| Line | What |
| --- | --- |
| `stop` | Stop Minecraft, then the launcher (what the panel sends) |
| `kronwerke status` | State, since when, how many starts |
| `kronwerke restart` | Restart Minecraft |
| `kronwerke update` | Restart Minecraft with a pack update first |
| `kronwerke stop`, `kronwerke start` | Stop or start Minecraft, the launcher stays |

Over the link the bot can ask for: `status`, `command`, `start`, `stop`, `restart` (optionally with an update), `console`, `logs`, `ls`, `read`, `write`, `delete`, `follow` and `launcher-update`. Writing is limited to config, kubejs, mods and a few files like `server.properties`; the world is read only, and the link key cannot be read at all.

## Planned

- Chat and join events to the bot, for a bridge to Discord.
- A world backup on request, next to the panel's own.

## Non-goals

- Replacing the panel. Its console, schedules and backups keep working.
- Running more than one Minecraft server.
- A plugin or mod. The launcher works whether Minecraft is up or not, which a mod cannot.

## Status

Early. Tested on a dedicated server with the full pack: pack update, NeoForge already installed, start, the panel's stop, restart with update, a killed Minecraft coming back, RCON commands and Kronwerke Core's whitelist commands over the link, file access and its limits. Not yet run on the real host or in a season.

## Docs

| Page | What |
| --- | --- |
| [docs/SETUP.md](docs/SETUP.md) | Putting it on a server with a game panel |
| [CHANGELOG.md](CHANGELOG.md) | What changed per version |

## Licence

MIT. See `LICENSE`.

Made by [Elchi](https://github.com/Elchi-dev)
