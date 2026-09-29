# Changelog

## 0.1.0

First version.

- Runs Minecraft as a child process; the panel's console and stop work as before.
- Before every start: the pack through packwiz (packwiz-installer is fetched directly, so GitHub's API is not needed), NeoForge in the version the pack names, RCON on with a random password.
- Minecraft is not started while `eula.txt` is not accepted. It counts as running once RCON is up. A crash starts it again after 15 seconds; three crashes in ten minutes and it waits.
- The link to the Discord bot: an outbound WebSocket, a key made on the first start, and requests for status, commands, start, stop, restart with or without an update, console, logs, files and a launcher update. It reconnects on its own.
- `kronwerke status|restart|update|start|stop` in the console.
- No dependencies: `build.sh` needs a JDK and nothing else, and runs the tests.
