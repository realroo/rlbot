# RealBots

Real fake players for Paper. Every bot is a genuine server-side player:

- it has an entry in the **tab list** with its name (and skin),
- it has a **body in the world** that other players can see, hit and interact with,
- it appears in `Bukkit.getOnlinePlayers()`, fires join/quit/death events, has an inventory and saves player data.

## Requirements

- Paper **1.20.5 or newer** (Mojang-mapped servers) and Java 21
- Internet access on first start (the server downloads ByteBuddy through `plugin.yml` libraries)

## Build

```
mvn clean package
```

The jar is `target/RealBots-1.0.0.jar`. Put it in `plugins/`.

## Commands (`/realbots`, `/rb`, `/bot`)

| Command | What it does |
|---|---|
| `/rb` or `/rb gui` | Open the GUI |
| `/rb spawn <name> [skin]` | Spawn a bot at your position |
| `/rb spawnmany <count> [prefix]` | Spawn random-named bots |
| `/rb joinlist [file]` | Spawn every name from `plugins/RealBots/names/` (or from one file, e.g. `friends`) |
| `/rb names` | Show loaded name files |
| `/rb remove <name>` / `/rb removeall` | Remove bots |
| `/rb list` | List bots |
| `/rb tp <name>` / `/rb bring <name>` | Teleport to the bot / bring the bot to you |
| `/rb say <name> <text>` | Bot says something in chat |
| `/rb cmd <name> <command>` | Bot runs a command |
| `/rb toggle <name> <lookat\|jump\|sneak\|swing\|chat\|invisible>` | Behaviours |
| `/rb setspawn` / `/rb clearspawn` | Set / disable the fixed bot spawn point |
| `/rb reload` | Reload config |

Permissions: `realbots.use` (GUI, list), `realbots.admin` (everything else). Both default to op.

## Custom names folder

On first start the plugin creates `plugins/RealBots/names/`. Put any number of `.txt` files there,
one name per line (3-16 characters: letters, numbers, underscore; lines starting with `#` are ignored).

```
Steve_01
Alex_02
Herobrine_X
```

Run `/rb reload` after editing. `/rb joinlist` spawns all of them, `/rb joinlist friends` only the
names in `friends.txt`, and `/rb spawnmany` and the GUI "Spawn 5 random bots" button use these
names first before falling back to generated ones. If a name is a real Minecraft username, the bot
gets that player's skin.

## Invisible bots and spawn point (config.yml)

```yaml
settings:
  invisible-by-default: false   # true = new bots are invisible but stay in the tab list

spawn:
  enabled: false                # true = every new bot spawns at the point below
  world: world
  x: 0.5
  y: 64.0
  z: 0.5
```

Stand where you want and run `/rb setspawn` to fill the `spawn:` section automatically.
Invisibility can also be toggled per bot from the GUI or with `/rb toggle <bot> invisible`.

## GUI

- **Main menu**: paged list of bots (click one to manage), spawn a bot, spawn 5 random bots, remove all, reload.
- **Bot menu**: teleport to / bring the bot, toggles (look at players, hop, sneak, swing arm, random chat), open the bot's inventory, cycle game mode, make it say something or run a command, change skin, remove.

## Features

- Skins by player name (looked up from Mojang, cached), or a random skin from a pool
- Bots survive restarts (`data.yml`), with their location, skin and behaviours
- Auto-respawn after death
- Simple server-side physics (gravity, hopping), looks at the nearest player, optional random chat lines
- Join/quit messages can be hidden in `config.yml`
- Every message is configurable (MiniMessage)

## Notes

The bot engine uses reflection over Mojang-mapped server classes and a ByteBuddy-generated
`Connection` that never touches the network. If a future Minecraft version renames something, the
plugin logs a clear error and stays loaded with the engine disabled rather than crashing the server.
