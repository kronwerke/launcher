# Setup on a game panel

For a Pterodactyl style panel where the startup command is fixed and runs `java ... -jar server.jar`. Nothing here needs support from the host.

## Once

1. **Docker image:** Java 21. NeoForge 1.21.1 wants 21; newer versions may work but are not what the mods are tested with.
2. **Automatic update:** off. It would replace `server.jar`.
3. **The jar:** download `kronwerke-launcher.jar` from the [latest release](https://github.com/kronwerke/launcher/releases/latest), check it against `SHA256SUMS`, and upload it to the server's root folder as `server.jar`. The NeoForge installer never writes a file with that name, so nothing overwrites it.
4. **Memory:** the panel's memory share sets the launcher's own heap, and the launcher needs little. Minecraft's heap is `memory` in `kronwerke/launcher.properties`. Keep the two together below the container's limit, with a few GB to spare for the JVM itself.
5. **EULA:** read [Minecraft's EULA](https://aka.ms/MinecraftEULA), then create `eula.txt` in the root folder with the line `eula=true`. The launcher does not start Minecraft without it and says so in the console.
6. **Start.** The first start writes `kronwerke/launcher.properties` and `kronwerke/link.key`, downloads the pack, installs NeoForge and starts Minecraft.

## The link to the bot

1. In `kronwerke/launcher.properties`:

   ```
   link.url=wss://api.kronwerke.com/link
   link.name=kronwerke
   ```

2. Restart from the panel. The console shows:

   ```
   [Kronwerke] Link to wss://api.kronwerke.com/link, fingerprint 616c86da53d3
   [Kronwerke] Link: waiting until the team accepts this server, fingerprint 616c86da53d3
   ```

3. The bot's control channel shows the same fingerprint. If they match: `!link accept 616c86da53d3`. The console says `Link: connected to the bot`, and `!mc status` answers from then on.

The key stays in `kronwerke/link.key` on the server. The bot keeps only its hash. A new key (a new server, or a deleted file) has to be accepted again; `!link revoke <fingerprint>` locks an old one out.

## The console

1. In `kronwerke/launcher.properties`: `console.port=9900` (an allocation of the container).
2. Cloudflare, zone kronwerke.com: `console` proxied to the container's address; an origin rule "console.kronwerke.com to port 9900"; SSL "Full"; Authenticated Origin Pulls on.
3. `kronwerke reload` (or a start of the container). The panel's console shows the setup code; open https://console.kronwerke.com/setup. Details in [CONSOLE.md](CONSOLE.md).

## A second server

1. An allocation for its game port (and one for its voice chat).
2. `kronwerke/servers/mining.properties`:

   ```
   dir=servers/mining
   port=27212
   rcon.port=25576
   voice.port=9901
   memory=8G
   cpu.share=3
   order=20
   role=mining
   ```

3. `kronwerke reload`. The launcher makes the folder and its links and starts the server; the console shows it at once.

## Everyday

- **Pack update:** push to the pack repository, then `!mc restart update` (or `kronwerke update` in the console). The panel's own start also updates the pack.
- **Crash:** the launcher starts Minecraft again after 15 seconds and the bot reports it. After three crashes in ten minutes it waits: `!mc logs 80`, fix, `!mc start`.
- **Stop for maintenance:** `!mc stop` stops Minecraft and keeps the launcher and the link up. The panel's stop ends both.
- **Launcher update:** in the console (or `POST /api/launcher/update {"version": "v0.2.1"}`), or `!mc launcher-update <jar url from the release> <sha256>`. The new version takes over at once and the servers keep running.

## Settings

Every key is in `kronwerke/launcher.properties` with a comment. Changes apply on the next `kronwerke reload`.

| Key | Default | What |
| --- | --- | --- |
| `pack.url` | the Kronwerke pack | packwiz `pack.toml`; empty turns updates off |
| `jvm.args` | G1 flags | Flags for every server |
| `java` | empty | The java for Minecraft; empty is the launcher's |
| `bus.port` | `25580` | Loopback port Kronwerke Core will connect to |
| `cpu.pin`, `cpu.balance` | `false` | CPU shares as hard limits, and moving them by load |
| `container.memory` | empty | GB the servers may use together; empty reads the container's limit |
| `console.*` | | See [CONSOLE.md](CONSOLE.md) |

Per server, in `kronwerke/servers/<name>.properties`: `dir`, `port`, `rcon.port`, `voice.port`, `memory`, `jvm.args`, `cpu.share`, `autostart`, `restart.on.crash`, `order`, `role`. Memory and flags apply on that server's next start, the CPU share at once.
| `link.url` | empty | The bot's link; empty turns it off |
| `link.name` | `kronwerke` | How the bot calls this server |
