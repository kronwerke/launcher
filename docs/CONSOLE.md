# The console

The console is served by the launcher itself. It works while every server is down and without the host's panel. Examples use console.example.com; Kronwerke's is console.kronwerke.com.

## The way in

```
browser  --HTTPS-->  Cloudflare  --HTTPS, Cloudflare's client certificate-->  container :9900
```

- A proxied DNS record for the console's host, pointing at the container's address.
- An origin rule sends that host to `console.port` (9900 here), one of the container's allocations. Cloudflare's free plan has origin rules.
- Authenticated origin pulls on for the zone: Cloudflare shows its client certificate to the origin, and the launcher accepts no connection without it. A request straight to the container's address ends in the TLS handshake.
- The launcher's certificate is self signed (made with the JDK's keytool on the first start, ten years). The zone's SSL mode "Full" accepts it. A Cloudflare origin certificate can be used instead with `console.cert` and `console.key`.

## The first passkey

On the first start with `console.port` set, the panel's console shows

```
[Launcher] Console: nobody has a passkey yet. Open https://console.example.com/setup and enter XXXX-XXXX
```

The code is also in `launcher/console/setup.code`. Open the address, enter the code and a name, and the browser makes a passkey (Touch ID, Windows Hello, a phone, a security key). That person is the owner. The code is gone afterwards.

Lost every passkey: delete `launcher/console/access.json` with the panel's file manager and run `launcher reload`; a new setup code appears.

## People

| Role | May |
| --- | --- |
| owner | Everything, including people and keys |
| admin | Everything except people and keys |
| mod | Read, players (kick, message) |
| view | Read |

Invite under Access: the link is valid for 24 hours and once. Whoever opens it makes their own passkey. Everyone can add more passkeys for their other devices.

## API keys

For programs. Made under Access, shown once, stored as a hash. Scopes: `read`, `players`, `command`, `power`, `files`, `pack`, `config`. No key can manage people or keys. Send it as `Authorization: Bearer key_...`.

Kronwerke's operations tooling keeps its key in a vault and sends it only to the console's host.

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
| `GET /api/season` | read | Kronwerke Core's season and goals, when the page is on (`console.season`) |
| `GET /api/pack` | read | Version here and in the repository, the changelog, the mods |
| `POST /api/pack/update` | pack | Stop all, packwiz, start again |
| `POST /api/launcher/reload` | power | Load the launcher afresh, servers keep running |
| `POST /api/launcher/update` `{"version": "v0.2.1"}` | power | Install a release and take over at once |
| `POST /api/launcher/config` `{"key": "cpu.pin", "value": "true"}` | config | cpu.pin, cpu.balance |
| `GET /api/audit?n=200` | read | Who did what |
| `GET /api/stream?servers=main,mining` | read | Server sent events: `line`, `event`, `overview` every ten seconds |

Every change lands in `launcher/console/audit.jsonl` and in the timeline.

## Files

`launcher/console` holds `access.json` (people, passkeys, invites, keys, sessions; secrets only as SHA-256), `audit.jsonl`, `tls.p12` with `tls.pass`, and `setup.code` while nobody exists. Neither the console nor the link can read that folder, `link.key` or `bus.key`.

## Settings

In `launcher/launcher.properties`:

| Key | Default | What |
| --- | --- | --- |
| `console.port` | empty | The allocation; empty turns the console off |
| `console.host` | empty, needed | The name passkeys are bound to |
| `console.language` | `en` | `en` or `de` |
| `console.accent` | `#e5b451` | The accent colour |
| `logo.svg`, `logo.png` | | Put either next to `launcher.properties` for your own logo |
| `console.season` | `auto` | The Kronwerke season page: auto (Kronwerke Core in the first server's mods), on, off |
| `console.bind` | `0.0.0.0` | |
| `console.client.ca` | `cloudflare` | Which client certificates count; `off` only for a test |
| `console.cert`, `console.key` | empty | PEM files instead of the self signed certificate |
| `console.tls` | empty | `off` serves plain HTTP, only on a loopback address, for tests |
| `console.origins`, `console.rpid` | empty | For tests on another address |
