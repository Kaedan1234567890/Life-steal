# Lifesteal Alpha 27 — UNVERIFIED SOURCE

This project has new server-side mixins to enforce crafting output pickup and intercept prohibited teleport commands, plus disconnect-triggered death handling and a prominent `/lifestealbuild` version check.

**Not compilation-tested.** The exact Minecraft 26.3 methods and mixin targets must pass GitHub Actions and then test on a backup server before deployment. Do not install alongside an older Lifesteal JAR; both share mod ID `chillzone_lifesteal` intentionally to preserve persistent data.

- `/lifestealbuild` must print `0.4.5-alpha.27-combat-crafting` after installation.
- Do a fresh GitHub Actions build of the latest commit; download its new artifact; remove previous Lifesteal JAR before copying the new one.
- Verify normal crafting, shift-click, recipe book, crafting-table and inventory crafting. Counter should be 1/3, 2/3, 3/3; fourth craft blocked; resets 24 hours after first craft.
- Verify disconnect while combat-tagged, loot drops, broadcast sequence and heart loss on a test world. These are untested and must be confirmed.
- Verify blocked `/home`, `/homes`, `/spawn`, `/tpa` during combat and success afterward.
- If Actions fails, supply complete first `error:` log; do not deploy.
