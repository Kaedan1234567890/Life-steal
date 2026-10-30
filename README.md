# Chill Zone Lifesteal 0.1.0-alpha.1 — CORE PROTOTYPE

This is an **independent from-scratch Fabric 26.3 server mod**, not a copy of LifeSteal FR.

## Implemented in source (not yet compiled or server-tested)
- PvP death transfers one heart from a victim to the killer, up to 20 hearts.
- Maximum-health attribute updates and persists by UUID to `world/data/chillzone-lifesteal.json`.
- Default 10 hearts, min 1, max 20; natural deaths do not lose hearts by default.
- `/withdraw <amount>` gives uniquely tagged **vanilla Nether Stars** with bold red Heart names (no resource pack).
- Right-click marked Heart to gain a heart instantly; ordinary Nether Stars are unaffected.
- `/lifesteal`, `/lifesteal hearts`, OP-level-3 `/lifesteal admin giveheart <player>`, `sethearts <player> <amount>`, `maxhearts <amount>`, `pvploss on|off`, `naturalloss on|off`.

## NOT implemented in this alpha
- Last-heart elimination/ban/revival, revive beacon item or hopper GUI, craft recipes.
- Other settings from the 14-setting reference, full settings GUI, Lifesteal-specific LuckPerms nodes.
- Combat tagging, home restrictions, item restriction GUI; Season 1 Combat core source is required.
- Anti-farming, heart-cap economy balancing, sophisticated death attribution, restart/gameplay testing.

**Do not deploy on a live survival world.** Test build and behavior on an isolated test server first. When a player's heart count reaches one, this prototype deliberately does not ban the player.

## GitHub
Upload the ZIP contents at repository root. Actions → Build Chill Zone Lifesteal 0.1.0-alpha.1 → Run workflow. Only the successful JAR from Actions goes into the server `mods` folder. Java 25 is required for compilation.

The provided source uses Mojang-mapped 26.3 APIs. Compilation has NOT been completed in this environment and minor API adjustments may be needed. Do not confuse source presence with verified functionality.


## Alpha 2 — 26.3 compiler repairs
- Replace the old `ServerPlayer.drop(ItemStack, boolean)` call with the 26.3 `giveOrDropStack(ItemStack)` API to avoid losing withdrawn Heart items when the inventory is full.
- Replace integer `CommandSourceStack.hasPermission(3)` with the 26.3 Brigadier `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` requirement.
- Gameplay design unchanged. These are source-level fixes; full Gradle compilation and in-game verification are still pending.


## Alpha 3 inventory compatibility fix
- Replaces nonexistent `ServerPlayer.giveOrDropStack(ItemStack)` with `ServerPlayer.getInventory().add(ItemStack)`, already used elsewhere in this source.
- `/withdraw` now deducts only hearts that were successfully placed in the player's inventory. If inventory capacity runs out, undelivered hearts stay in the player's heart total.
- This is a targeted fix for the GitHub compilation error at `ChillZoneLifesteal.java:120`; compilation and server behavior have **not** yet been independently verified.


## Alpha 4 GitHub diagnostic build
This update changes **only** the GitHub workflow and version, not Java gameplay or heart-transfer code.
Run **Actions → Build Chill Zone Lifesteal 0.1.3-alpha.4 (diagnostic) → Run workflow**.
The build has a hard timeout so it cannot hang forever. The `chillzone-lifesteal-alpha4-diagnostic-log` artifact appears even if compilation fails (when the log exists). Download it to identify the last Gradle step. A successful build produces a separate JAR artifact.
Do not upload the ZIP as a Minecraft mod; the ZIP contains GitHub source.
