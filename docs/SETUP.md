# Setup

For a Pterodactyl style panel, where the startup command is fixed and runs `java ... -jar server.jar`. Nothing here needs support from the host. On a machine of your own, run the jar in the folder the servers should live in.

The launcher's own files live in `launcher/` next to the jar. Rename the folder to anything you like: the launcher uses whichever folder holds `launcher.properties`.

## Once

1. **Docker image:** Java 21 or newer, as your servers need it.
2. **Automatic update:** off. It would replace `server.jar`.
3. **The jar:** download `launcher.jar` from the [latest release](https://github.com/kronwerke/launcher/releases/latest), check it against `SHA256SUMS`, and upload it to the root folder under the name the panel starts (`server.jar` on most panels). No Minecraft installer writes a file with that name, so nothing overwrites it.
4. **Memory:** the panel's memory share is the launcher's own heap, and it needs little (256 MB is plenty). Each server's heap is `memory` in its file. The launcher refuses a start when the heaps plus 3 GB per server would not fit into the container.
5. **Start.** The first start writes `launcher/launcher.properties` and `launcher/servers/main.properties`, every setting with a comment, and waits.

## The first server

Edit `launcher/servers/main.properties`, then type `launcher reload` in the panel's console.

**Paper, Fabric, vanilla** (anything that is a jar):

```
type=jar
jar=paper.jar
memory=8G
autostart=true
```

**NeoForge from a packwiz pack:** set `pack.url` in `launcher.properties` to the pack's `pack.toml`, then

```
type=neoforge
memory=16G
autostart=true
```

The launcher runs packwiz, installs the NeoForge version the pack names, and starts it. Without a pack, `neoforge=21.1.252` picks the version.

**Any other program:**

```
type=command
command=./run.sh --port 7777
stop=quit
ready=Server started
autostart=true
```

`stop` is what is typed into the program to stop it (empty: a stop signal). `ready` is a regular expression on a console line that means the program is up (empty: up at once).

Minecraft servers need `eula.txt` with `eula=true` in their folder: read [Minecraft's EULA](https://aka.ms/MinecraftEULA) first. A second Minecraft server copies the first one's.

## More servers

One file per server in `launcher/servers`. The file name is the server's name.

```
# launcher/servers/mining.properties
type=neoforge
dir=servers/mining
port=27212
rcon.port=25576
transfers=true
voice.port=9901
memory=8G
cpu.share=3
order=20
```

`launcher reload`, and the launcher makes the folder and starts the server. A second NeoForge server takes mods, defaultconfigs, kubejs, libraries, ops.json and every config entry from the first one as links, so one pack update covers both; `voice.port` gives it its own copy of Simple Voice Chat's config, `own=` names more entries it should copy, `share=` replaces the list.

Each server needs its own game port, a RCON port of its own (loopback only), and an allocation on the panel for every port players connect to.

## The console

1. In `launcher/launcher.properties`: `name` (shown in the console's title), `console.port` (an allocation), `console.host` (the name it is reached by), `console.language` (`en` or `de`).
2. Behind Cloudflare: a proxied DNS record for the host to the container's address, an origin rule sending that host to `console.port`, SSL mode "Full", Authenticated Origin Pulls on. Details and other setups in [CONSOLE.md](CONSOLE.md).
3. `launcher reload`. The panel's console shows a setup code (it is also in `launcher/console/setup.code`); open `https://<host>/setup`.

## A controller over WebSocket (optional)

The launcher can dial out to a controller, for example a Discord bot, so the controller needs no way into the container. Kronwerke's bot ([kronwerke/bot](https://github.com/kronwerke/bot)) speaks this; the protocol is in `Link.java`.

1. `link.url=wss://controller.example/link` and `link.name=...` in `launcher.properties`, then `launcher reload`.
2. The panel's console shows a fingerprint. The controller shows the same one and accepts it once (the Kronwerke bot: `!link accept <fingerprint>` in its control channel).

The key stays in `launcher/link.key`; the controller keeps only its hash.

## Everyday

- **Pack update:** push to the pack repository, then "Update pack" in the console (or `launcher update`). Every server stops, the pack updates, they start again. A fresh container also updates first.
- **Crash:** the server starts again after 15 seconds. After three crashes in ten minutes it waits: read the console or the crash report, fix, start it.
- **Maintenance:** stop a server in the console; the launcher and the others stay up. The panel's stop ends everything.
- **Launcher update:** in the console, or `POST /api/launcher/update {"version": "v0.3.0"}`. The new version takes over at once and the servers keep running. Releases come from `update.repo`.

## Settings

`launcher/launcher.properties`, every key with a comment. Changes apply on `launcher reload`.

| Key | Default | What |
| --- | --- | --- |
| `name` | empty | Shown in the console's title and in front of the launcher's lines |
| `pack.url` | empty | A packwiz `pack.toml`; empty turns pack updates off |
| `java` | empty | The java for servers; empty is the launcher's |
| `jvm.args` | G1 flags | Flags for every Java server |
| `link.url`, `link.name` | empty | The optional controller |
| `console.*` | | See [CONSOLE.md](CONSOLE.md) |
| `update.repo` | `kronwerke/launcher` | GitHub repository launcher updates come from |
| `bus.port` | empty | A loopback port mods may connect to (Kronwerke Core does) |
| `cpu.pin`, `cpu.balance` | `false` | CPU shares as hard limits, and moving them by load |
| `container.memory` | empty | GB the servers may use together; empty reads the container's limit |

Per server, in `launcher/servers/<name>.properties`:

| Key | What |
| --- | --- |
| `type` | `neoforge`, `jar` or `command` |
| `jar`, `command`, `neoforge` | What to run, by type |
| `dir` | Its folder, relative to the root |
| `port`, `rcon.port`, `transfers`, `voice.port` | Minecraft: game port, RCON on loopback, accepts-transfers, Simple Voice Chat's port |
| `memory`, `jvm.args`, `java`, `args` | Java servers: heap, flags, java, program arguments (default `nogui`) |
| `stop`, `ready` | What stops it, which line means it is up |
| `share`, `own` | What a second server links or copies from the first |
| `cpu.share` | Share of the CPUs; applies at once |
| `autostart`, `restart.on.crash`, `order` | Starting with the launcher, after crashes, in which order |
| `role` | Passed to the server as `-Dlauncher.role` |
| `color` | Its colour in the console |

Java servers also get `-Dlauncher.server=<name>`, and with `bus.port` set `-Dlauncher.bus` and `-Dlauncher.bus.key`.
