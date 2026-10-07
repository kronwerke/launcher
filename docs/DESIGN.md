# Design: more than one server, and the console

Internal. The plan for launcher 0.2 and later: one launcher runs every Kronwerke server in one container, players move between them without noticing much, and console.kronwerke.com is where the team runs all of it. Decided with Samuel on 2026-10-07. Sections marked *open* are not settled yet.

## Why not panel subservers

The host could give every server its own container. Then the servers would share nothing: no disk, no loopback, no common process, so chat, tablist and player data would need a broker (Redis or similar), and the panel would show one console per server. One container with one launcher keeps all of that local: the servers share a disk, talk over loopback, and the launcher sees every process, so it can also move CPU between them while they run.

The price: one container is one failure domain. If the container dies, every server is down. That was true for one server too.

## The picture

```
                 Cloudflare (console.kronwerke.com, proxied)
                        |  mTLS, only Cloudflare gets through
                        v
  container  ----------------------------------------------------------------------
  |                                                                              |
  |   launcher (server.jar, the process the panel starts)                        |
  |     Console    HTTPS on :9900, UI and API                                    |
  |     Bus        loopback :25580, one connection per Kronwerke Core            |
  |     Link       outbound WebSocket to the bot, as before                      |
  |     Fleet      the servers below, their processes, CPU and memory            |
  |        |                         |                                           |
  |        v                         v                                           |
  |   main    :25565 game       mining  :27212 game                              |
  |           :19132 voice              :9901  voice                             |
  |           rcon :25575 lo            rcon :25576 lo                           |
  |                                                                              |
  |   shared disk: kronwerke/shared (player handoff, locks)                      |
  --------------------------------------------------------------------------------
```

## Servers

A server is a folder and a few lines of config.

| Server | Folder | Game port | Voice port | Heap | Role |
| --- | --- | --- | --- | --- | --- |
| `main` | the container root, as today | 25565 | 19132 | 20G | Everything except mining |
| `mining` | `servers/mining` | 27212 | 9901 | 8G | The resource world, reset per season stage or on demand |

`main` stays in the root so the world, the panel's backups and every path that exists today stay where they are.

Config moves from one file to one file plus one per server:

```
kronwerke/launcher.properties          launcher-wide: console, bus, link, pack
kronwerke/servers/main.properties      folder, ports, memory, cpu, autostart, role
kronwerke/servers/mining.properties
```

A missing `kronwerke/servers` folder means "only main, with the old keys from launcher.properties", so a 0.1 config keeps working unchanged.

**One pack for all.** Every server gets the same pack, so a client that joins one can join all. `mining` does not run packwiz itself: its `mods`, `config`, `defaultconfigs` and `kubejs` are symlinks to the root's, so one update covers both. What differs per server is passed on the command line: `-Dkronwerke.server=mining -Dkronwerke.bus=127.0.0.1:25580`. Core reads it and switches its role (no obelisk, no spawn rules, its own reset).

**Updates.** A pack update means: packwiz once, then restart every server, `main` last, so players move to main and the mining world's restart goes unnoticed. A single server can restart alone.

## Moving between servers

Minecraft 1.21 has this built in: the server sends a transfer packet with a host and a port, and the client connects there, keeping its identity. Before that the server can store cookies on the client, and the next server can ask for them. Both target servers have `accepts-transfers=true`.

The move, from main to mining:

1. The player uses the way to the mining world (a gate at spawn; *open*: gate, item or command).
2. Core on main sends the client `kronwerke:transfer_begin` with the target's name. The client starts the transfer animation (below), which hides everything that follows.
3. Core saves the player, writes the player file and everything that travels with it to `kronwerke/shared/players/<uuid>/`, and writes a lock: `owner=mining, since=<time>`.
4. Core stores a cookie on the client: player uuid, target, time, signed with the bus key (HMAC-SHA256).
5. Core sends the transfer packet to `kronwerke.net:27212`.
6. The client reconnects. Core on mining asks for the cookie during configuration. No valid cookie, a wrong uuid or older than 60 seconds: the player is kicked with "Join through kronwerke.net". So nobody joins the mining server directly.
7. Core on mining waits until the lock says `mining` and the handoff files are complete, loads the player from them, places them at the mining world's arrival point and tells the client to play the arrival half of the animation.
8. Back to main the same way. A player who disconnects on mining is moved back on their next join: main sees the lock pointing at mining, loads the handoff (written by mining on disconnect), and the player stands at the gate.

**What travels.** The player file (inventory, ender chest, attachments of every mod, which covers Curios, origins, Iron's Spells and our own stages) travels as a whole. That is not enough, because some mods keep a player's things in world data:

| Mod | Where | Handling |
| --- | --- | --- |
| Sophisticated Backpacks | backpack contents in world storage, by backpack uuid | Core copies the entries of every backpack in the inventory (and backpacks in backpacks) along, and writes them back on arrival |
| FTB Quests | team progress in world data | Progress lives on main only. On mining, quests are read only; item tasks complete when the player is back on main with the items |
| FTB Teams, FTB Chunks | world data | Teams copied read only to mining on start; claims are not allowed in the mining world |
| Waystones | player data | Travels with the player; waystones are not placeable on mining |
| Mekanism, AE2, Flux, RFTools, XNet, Draconic, EnderIO | networks and frequencies in world data | Do not reach across servers. Wireless terminals and remotes show "no network" on mining. Accepted |
| Lootr | per player loot in world data | Not used in the mining world |
| Kronwerke Core | season, goals, slots, obelisk | main is the only owner. mining asks main over the bus |

This table is the audit to finish before mining opens: every mod in the pack checked for world data keyed by player or item. Anything not in the table is assumed to be safe and that assumption is what the load test checks.

**Why not the Nether and the End.** Portals, the dragon, Draconic's chaos guardian, waystones and AE2 networks all assume one world. The mining world is a dead end with one entrance, which is why it is first. Nether and End are decided after the load test shows whether main needs the relief at all.

## Chat and tablist

Core on every server connects to the launcher's bus and sends what the others should see:

- **Chat.** A message on one server is shown on the others with the server's mark in front (a small gem in the stage colour of the world). Commands and private messages stay local; `/msg` across servers goes through the bus.
- **Tablist.** Every server shows every player. Players on another server are added as list entries only (no entity), with their skin, grey ping and the server's mark. Nautical Ranks keeps formatting the local ones; Core formats the remote ones the same way.
- **Joins and leaves.** A move between servers is not a leave and a join; Core shows "moved to the mining world" instead.

The bus is line delimited JSON over loopback TCP. Every connection starts with the server's name and an HMAC over a nonce with the key from `kronwerke/bus.key`, which the launcher creates and only processes in the container can read.

## CPU and memory

The container has 9 vCores and 51 GB.

**Memory** is fixed per start: heap from each server's config. A change applies on the server's next restart, and the console says so. The launcher keeps the sum of heaps plus 3 GB per server for the JVM's own memory below the container's limit and refuses a start that would break it.

**CPU** can move while servers run. Each server has a share (main 6, mining 3). The launcher applies it as CPU affinity (a nice value can only get worse without privileges, so it is not used):

- - **Cores:** with `cpu.pin=true`, each server is limited to its share of logical CPUs through `taskset`. Only useful when the host lets us see which CPUs the container gets; the launcher reads `/sys/fs/cgroup/cpu.max` and the CPU list at start and turns pinning off when it cannot tell.

**Auto balance** (off by default, switch in the console): every 10 seconds the launcher reads each server's tick time (`neoforge tps` over RCON). When the busiest server averages above 40 ms over a minute and another one is below 25 ms with more than one share, one share moves. At most one move a minute. Shares do not move back by themselves; the console shows them and sets them back. Every move is in the timeline.

## The console

console.kronwerke.com. The launcher serves it itself, so it works while Minecraft is down, while the bot is down, and without the host's panel.

### Path

```
browser  --HTTPS-->  Cloudflare  --HTTPS, client certificate-->  container :9900
```

- DNS `console` in the kronwerke.com zone, proxied.
- An origin rule in Cloudflare sends that host to port 9900 (any allocated port works).
- The launcher serves TLS with a certificate for console.kronwerke.com (Cloudflare origin certificate when the token allows creating one, else self signed with "Full" for that host).
- Authenticated origin pulls: the launcher demands Cloudflare's client certificate. A request straight to `93.90.74.243:9900` fails in the TLS handshake, before any of our code runs.

### Who gets in

- **Passkeys** (WebAuthn, ES256 and RS256). No passwords exist.
- **The first passkey.** On the first start the launcher prints a one time setup code in the panel's console and writes it to `kronwerke/console/setup.code`. The code opens the page to register the owner's passkey and is gone after that.
- **More people.** The owner creates an invite link (24 hours, one use) with a role. Roles: `owner` (everything, including users and keys), `admin` (everything except users and keys), `mod` (players, chat, console read, whitelist), `view` (read only).
- **Sessions.** A cookie (`__Host-`, HttpOnly, Secure, SameSite Strict), 12 hours, ended on sign out or when the user's passkey is removed. Every write needs the session plus a per session token in a header.
- **API keys** for machines (Elchi Ops). Made in the console, shown once, stored only as a hash, with scopes (`read`, `command`, `files`, `pack`, `power`) and an optional expiry. Elchi Ops keeps it in the vault as `kronwerke.console` with host console.kronwerke.com.
- **Limits.** Failed sign ins and bad keys are rate limited per address (Cloudflare's `CF-Connecting-IP`, trusted only because of the client certificate).
- **Audit.** Every write by anyone (user, key, the bot) goes to `kronwerke/console/audit.jsonl`: who, what, which server, result. The console shows it, filterable.

### What it does

- **Overview.** Every server: state, players, TPS and MSPT over the last hour, CPU and heap, uptime, pack version. The container: CPU, memory, disk. One timeline of what happened (starts, crashes, updates, CPU moves, sign ins).
- **Console.** Live log per server or all servers mixed with colour per server, command line with history and completion of known commands, filter and search, jump to errors.
- **Players.** Online across servers, where, since when; kick, move to another server, whitelist and slots (Core's commands), inventory view (read only).
- **Pack and mods.** Pack version on the server against the repository, the change list in between, update (one click: packwiz, then restart in order). Mod list with versions. Core version.
- **Files.** Browse, edit text files with syntax colours, upload and download, inside the same limits as the link (world read only, keys unreadable).
- **Resources.** Shares and heaps per server, auto balance on or off, the CPU graph per server.
- **Season.** Core's admin: season state, goals and progress, the obelisk's tier, the rite (start, test).
- **Crash reports.** The newest ones, readable, with the first relevant line found.
- **Keyboard first.** A command palette (Ctrl K) reaches every action and every server.

### API

The same JSON API the UI uses, under `/api`. Everything the UI does, a key with the right scope can do:

```
GET  /api/servers                       state of all servers
GET  /api/servers/{name}/console?n=200  console lines
GET  /api/servers/{name}/stream         live console (server sent events)
POST /api/servers/{name}/command        {"cmd": "list"}
POST /api/servers/{name}/power          {"action": "start|stop|restart", "update": false}
GET  /api/servers/{name}/files?path=    list or read
PUT  /api/servers/{name}/files?path=    write
POST /api/pack/update                   packwiz, then restart in order
GET  /api/players                       across servers
GET  /api/audit?n=100
```

Server sent events instead of WebSockets: the JDK's HTTP server can do them without a library, and they pass Cloudflare unchanged.

## The transfer animation

What the player sees while the client reconnects, which takes several seconds with this pack. Idea: Zelda's fast travel, where Link dissolves into light that rises, and appears again as light that comes down. Ours borrows from what Kronwerke already has, the obelisk's beam and the galaxy of the rite.

1. **Rising.** A beam (the obelisk's beam shader) comes down on the player, the view lifts slowly, the world gets brighter and loses its colour, particles of light rise from the player's own body.
2. **Between.** White fades into the galaxy sky of the rite, flying through it. The target's name stands in the middle in the server's colour. This part loops for as long as the reconnect needs; Minecraft's own screens (connecting, configuration, loading terrain) are hidden behind it.
3. **Arriving.** Only when the chunks around the player are built, the galaxy turns white, the beam comes down at the arrival point and fades, the view settles.

The client keeps the animation running across the disconnect because it is a Core overlay, drawn on every screen and in the world, not a screen of its own. If the move fails, the overlay fades out over the error screen, so nothing is hidden that matters.

Drippy Loading Screen only changes the screen while the game itself starts and has no part in this. FancyMenu can style the connect screens, but the overlay covers them anyway.

## Order of work

1. Launcher: servers as a list, per server config, the old config still valid. Bus. CPU shares. Release 0.2.0 with only `main` configured, so nothing changes for players.
2. Console: HTTPS, passkeys, API keys, overview, console, power, files, audit. Cloudflare and DNS. Elchi Ops gets its key; the bot's admin API stays as a fallback until the console has run for a week.
3. Core: bus client, chat and tablist across servers, the handoff and the transfer with cookies.
4. Core client: the transfer animation.
5. The mining server: folder, symlinks, Core's mining role, world reset, the audit of world data above, then a load test with fake clients on both servers.
6. Then decide about Nether and End.

## Open

- The way into the mining world: gate at spawn, item, command, or all three.
- Reset rhythm of the mining world.
- Whether the bot keeps its own link or reads from the console's API later.
