# Chill Zone Lifesteal 0.2.0-alpha.1 — source-only feature checkpoint

This is **NOT** the complete recipe/revival/storage update and **NOT** verified in Minecraft. It is a test source checkpoint based on prior 0.1.3-alpha.4 code.

Implemented now:
- Existing PvP heart transfer code preserved.
- `/lifesteal admin giveheart <player> [amount]` (1–64), player autocomplete.
- `/lifesteal admin giverevivebeacon <player> [amount]` (1–64), player autocomplete.
- Normal vanilla Beacon item, bold named `Revive Beacon`, with server-side custom-data marker.
- `/lifesteal craftstatus` shows whether current equipped hearts are below 15 (a preview only; does NOT limit crafting yet).
- Ordinary bold red Nether Star Heart remains.

**Not implemented or tested in this checkpoint:**
- The two requested real 3x3 crafting recipes, recipe-book display or the under-15 crafting enforcement.
- Revive Beacon right-click or 27-slot GUI and revival mechanics.
- Preventing both items entering chests/containers, bundles, item frames, hoppers, etc.
- Player elimination at zero hearts and mod-specific revival/pardons.
- Old Combat timer/settings GUI.

**Avoid installing over a working server build without a backup.** Github build and in-game results are unverified. The source has no Fabric dependencies available locally to compile.
