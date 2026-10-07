# Networks and the bus

Servers that belong together form a network: they share chat, joins and leaves, the whitelist and bans, and with a mod also the tab list and player data. A server joins a network with one line in its file, or on the console's Network page.

## Settings

In `launcher/servers/<name>.properties`:

| Key | Default | What |
| --- | --- | --- |
| `network` | empty | Servers with the same name share what is switched on below. Empty: the server stands alone |
| `sync.chat` | `network` | `network`: chat shows on every server of the network. `server`: it stays on this server. `radius`: only players within `chat.radius` blocks see it (needs a mod) |
| `chat.radius` | `100` | Blocks, for `sync.chat=radius` |
| `sync.joins` | `true` | Joins and leaves show on the other servers |
| `sync.tablist` | `true` | Every player of the network in every tab list (needs a mod) |
| `sync.lists` | `true` | Whitelist, bans and operators stay the same on every server |
| `sync.players` | `false` | Player data travels with the player between servers (needs a mod) |
| `label` | empty | The server's name in front of its chat on the others; empty is the file name |
| `color` | empty | Its colour there and in the console |

Both ends decide: a chat line from A shows on B only when A and B both have `sync.chat=network`.

## Without a mod: the bridge

Any Minecraft server (vanilla, Paper, Fabric, NeoForge) works without help. The launcher reads chat lines (`<name> text`) and joins from the server's console and shows them on the other servers of the network with `tellraw` over RCON:

```
[mining] <Grubenhund> found diamonds
```

Joins and leaves use Minecraft's own translated text, so every player reads them in their language.

Lists: a second server of a network links the first one's `whitelist.json`, and when the file changes (a `whitelist add` anywhere, or a mod that writes it) every server of the network reads it again within ten seconds. `ban`, `pardon`, `op`, `deop` and `whitelist add|remove` typed in the console (the panel's, the web console's, the API) run on every running server of the network.

A new server in a network starts with the first server's `server.properties` (whitelist on, difficulty, view distance and so on), with its own world, ports and RCON.

## With a mod: the bus

`bus.port` in `launcher/launcher.properties` opens a TCP port on loopback. Java servers then start with:

```
-Dlauncher.server=<name>        the server's name
-Dlauncher.role=<role>          role= from its file, or its name
-Dlauncher.bus=127.0.0.1:<port>
-Dlauncher.bus.key=<path>       the bus key, readable only inside the container
```

A mod that connects takes over from the bridge for its server: the launcher stops reading that server's console for chat, and the other servers' messages reach it over the bus instead of `tellraw`. Kronwerke Core is such a mod.

### Protocol

One JSON object per line, UTF-8, both ways.

**Signing in.** The launcher speaks first:

```json
{"op": "hello", "nonce": "9f2c...", "launcher": "0.5.0"}
```

The mod answers with an HMAC-SHA256 over `nonce + ":" + server`, keyed with the content of the key file (trimmed, as UTF-8 bytes), in lower case hex:

```json
{"op": "auth", "server": "mining", "mac": "5a1e...", "mod": "kronwerke-core 0.17.0"}
```

A wrong key ends the connection with `{"op": "error", "text": "wrong key"}`. A second connection for the same server replaces the first.

**What the launcher sends.**

`welcome` right after signing in, and the same shape as `policy` whenever a setting, a server or a connection of the network changes:

```json
{"op": "welcome", "server": "mining", "label": "Mining", "color": "#8bb6dc",
 "policy": {"network": "kronwerke", "chat": "network", "radius": 100, "joins": true, "tablist": true, "lists": true, "players": true},
 "peers": [{"name": "main", "label": "main", "color": "#e5b451", "bus": true, "running": true, "policy": {...}}],
 "players": {"main": [{"name": "Elchi_Sam", "uuid": "..."}]}}
```

Then, from the other servers of the network, each with `from`, `label` and `color`:

| op | Fields | When |
| --- | --- | --- |
| `chat` | `player`, `uuid`, `text`, `extra` | A chat line on another server |
| `join`, `leave` | `player`, `uuid`, and `to` or `via` if the sender gave them | A player came or went |
| `players` | `list` | Another server's players, for the tab list. An empty list when that server left the bus |
| `message` | `topic`, `data`, `id` | A mod's own message (below) |
| `say` | `who`, `text` | Someone in the web console wrote to the players |
| `sent` | `id`, `delivered` | Answer to a `send` with an `id` |
| `pong` | `t` | Answer to `ping` |
| `error` | `text` | The last line was not understood |

**What the mod sends.**

| op | Fields | Effect |
| --- | --- | --- |
| `chat` | `player`, `uuid`, `text`, `scope`, optional `extra` | `scope` is `network` (default) to share it, anything else (`radius`, `server`) only for the console's chat log. `extra` (an object) reaches the other mods untouched, for a rank, say |
| `join`, `leave` | `player`, `uuid`, optional `to`, `via`, `extra` | Shown on the others when `sync.joins` is on. `to` says a player moved to another server instead of leaving |
| `players` | `list` of objects with at least `name` and `uuid` | Replaces this server's list; sent to the others when `sync.tablist` is on. Send it on every join and leave, and once after signing in |
| `send` | `to` (a server or `*`), `topic`, `data`, optional `id` | Delivered as `message` to that server's mod. Topics starting with `player.` need `sync.players` on both ends |
| `event` | `text` | A line in the console's timeline |
| `ping` | `t` | `pong` |

The bus survives nothing: a launcher update closes it and opens it again. Mods reconnect (Kronwerke Core tries every few seconds) and send `players` again.

## The console

The Network page shows a map of the networks, the chat of every server live, a line to write to the players of one or all servers, and every setting above. `say <text>` in the command line (Ctrl K) writes to every server.

API (scope in brackets):

```
GET  /api/network?n=200        servers and their settings, bus state, last chat lines   (read)
POST /api/network/say          {"text": "...", "servers": ["mining"]}  (empty: all)      (players)
POST /api/servers/{name}/config {"key": "sync.chat", "value": "radius"}                  (config)
POST /api/launcher/config      {"key": "bus.port", "value": "25580"}                    (config)
```

Chat also arrives on the live stream (`/api/stream`) as `chat` events.
