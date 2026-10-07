# The console

console.kronwerke.com is served by the launcher itself. It works while Minecraft is down, while the bot is down, and without the host's panel.

## The way in

```
browser  --HTTPS-->  Cloudflare  --HTTPS, Cloudflare's client certificate-->  container :9900
```

- `console` in the kronwerke.com zone, proxied.
- An origin rule sends `console.kronwerke.com` to port 9900, which is one of the container's allocations.
- Authenticated origin pulls on for the zone: Cloudflare shows its client certificate to the origin, and the launcher accepts no connection without it. A request straight to the container's address ends in the TLS handshake.
- The launcher's certificate is self signed (made with the JDK's keytool on the first start, ten years). The zone's SSL mode "Full" accepts it. A Cloudflare origin certificate can be used instead with `console.cert` and `console.key`.

## The first passkey

On the first start with `console.port` set, the panel's console shows

```
[Kronwerke] Console: nobody has a passkey yet. Open https://console.kronwerke.com/setup and enter KW-XXXX-XXXX
```

The code is also in `kronwerke/console/setup.code`. Open the address, enter the code and a name, and the browser makes a passkey (Touch ID, Windows Hello, a phone, a security key). That person is the owner. The code is gone afterwards.

Lost every passkey: delete `kronwerke/console/access.json` with the panel's file manager and run `kronwerke reload`; a new setup code appears.

## People

| Role | May |
| --- | --- |
| Inhaber (owner) | Everything, including people and keys |
| Admin | Everything except people and keys |
| Moderation (mod) | Read, players (kick, message) |
| Nur lesen (view) | Read |

Invite under Zugang: the link is valid for 24 hours and once. Whoever opens it makes their own passkey. Everyone can add more passkeys for their other devices.

## API keys

For programs. Made under Zugang, shown once, stored as a hash. Scopes: `read`, `players`, `command`, `power`, `files`, `pack`, `config`. No key can manage people or keys. Send it as `Authorization: Bearer kwc_...`.

Elchi Ops keeps its key in the vault as `kronwerke.console` (host console.kronwerke.com, bearer).

## The API

Every answer is `{"ok": true, "data": ...}` or `{"ok": false, "error": "..."}`. A browser session also needs its `X-Kw-Csrf` header on every change; keys do not.

| Call | Scope | What |
| --- | --- | --- |
| `GET /api/overview` | read | Every server with its last sample, the container, the pack, the timeline |
| `GET /api/servers/{name}/console?n=500` | read | The last console lines |
| `GET /api/servers/{name}/metrics` | read | The last hour, a sample every ten seconds |
| `POST /api/servers/{name}/command` `{"cmd": "list"}` | command | Runs a command over RCON and returns the answer. With only `players`: list, kick, say, msg, tell |
| `POST /api/servers/{name}/power` `{"action": "start"}` | power | start, stop, restart, kill |
| `POST /api/servers/{name}/config` `{"key": "memory", "value": "20G"}` | config | memory, cpu.share (at once), autostart, restart.on.crash, jvm.args |
| `GET /api/servers/{name}/files?path=config` | files | A folder's entries, or a file as base64 with `writable` |
| `PUT /api/servers/{name}/files` `{"path": "...", "data": "<base64>"}` | files | Writes where the link may write |
| `DELETE /api/servers/{name}/files?path=...` | files | Deletes there |
| `GET /api/servers/{name}/logs?path=logs/latest.log&lines=500` | read | The end of a log or crash report |
| `GET /api/servers/{name}/crashes` | read | Crash reports with the line that says what happened |
| `GET /api/players` | read | Who is on which server |
| `GET /api/season` | read | Core's season and goals as JSON |
| `GET /api/pack` | read | Version here and in the repository, the changelog, the mods |
| `POST /api/pack/update` | pack | Stop all, packwiz, start again |
| `POST /api/launcher/reload` | power | Load the launcher afresh, servers keep running |
| `POST /api/launcher/update` `{"version": "v0.2.1"}` | power | Install a release and take over at once |
| `POST /api/launcher/config` `{"key": "cpu.pin", "value": "true"}` | config | cpu.pin, cpu.balance |
| `GET /api/audit?n=200` | read | Who did what |
| `GET /api/stream?servers=main,mining` | read | Server sent events: `line`, `event`, `overview` every ten seconds |

Every change lands in `kronwerke/console/audit.jsonl` and in the timeline.

## Files

`kronwerke/console` holds `access.json` (people, passkeys, invites, keys, sessions; secrets only as SHA-256), `audit.jsonl`, `tls.p12` with `tls.pass`, and `setup.code` while nobody exists. Neither the console nor the link can read that folder, `link.key` or `bus.key`.

## Settings

In `kronwerke/launcher.properties`:

| Key | Default | What |
| --- | --- | --- |
| `console.port` | empty | The allocation; empty turns the console off |
| `console.host` | `console.kronwerke.com` | The name passkeys are bound to |
| `console.bind` | `0.0.0.0` | |
| `console.client.ca` | `cloudflare` | Which client certificates count; `off` only for a test |
| `console.cert`, `console.key` | empty | PEM files instead of the self signed certificate |
| `console.tls` | empty | `off` serves plain HTTP, only on a loopback address, for tests |
| `console.origins`, `console.rpid` | empty | For tests on another address |
