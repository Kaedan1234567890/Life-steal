# Chill Zone Lifesteal 0.2.0 — GitHub-buildable server-side prototype

Base: the original LifeSteal FR 2.5.1 (Stoupy51), MIT licensed. Original datapack functions, recipes and configuration remain included. Retain original creator attribution and upstream license.

**Important**: This is a packaging and initialization test, **not** yet proven to work in Minecraft 26.3. The underlying game data is otherwise unchanged.

## Install
1. Upload the *contents* of this directory into a new GitHub repo.
2. Click **Actions → Build Chill Zone Lifesteal 0.2.0 → Run workflow**.
3. Download the generated **JAR** from the GitHub Actions artifacts. Install that JAR (not this source ZIP) in `mods` after removing any older LifeSteal FR mod copies.
4. Make sure the Fabric API (including fabric-resource-loader-v0) is installed. Server-side only; no client mods.
5. Restart server. Verify `/datapack list enabled` and `/scoreboard objectives list`, then `/trigger life_steal.withdraw` as a member. The `/trigger` objective is created during load and re-enabled per player each tick.
6. `/function` and `/scoreboard` still require owner/operator or suitable permissions. Set `function-permission-level=3` in server.properties (separate setting).

## What changed
- New source Gradle/Actions packaging project for GitHub.
- Fabric metadata marks server-side.
- Added fallback load function to create trigger objective if original resolver did not complete; fallback calls original versioned initializer.
- Added tick fallback only if original version gate does not match 2.5.1.
- Original datapack functions and recipes preserved (the originals are not rewritten).

## Important limitations
If the datapack is not enabled by Fabric's resource loader, none of these commands will appear; permissions cannot repair a non-loaded datapack. Check server log and list enabled packs. This source is **not** a general Forge/NeoForge mod or a replacement for Fabric API. Test on a backed-up server before launch.
