# Chill Zone Lifesteal 0.2.1 — source-only development checkpoint

This version **has not been compiled** or confirmed safe for a survival server. Do NOT replace the multiplayer-tested Lifesteal JAR yet.

Changes in this checkpoint:
- Persistent `deathBans` toggle and eliminated UUID set in existing state JSON.
- Last eligible PvP death marks the victim eliminated and kicks them. Rejoin is rejected while death bans are active.
- `lifesteal admin deathbans on|off` toggles enforcement.
- Prototype `lifesteal admin revive <online-player>` clears the mark; **offline revival is not implemented**.
- Existing PvP heart transfer unchanged except final-heart killer gain handling.

Known gaps: crafting recipes, real 27-slot revival GUI, offline player registry, restricted storage enforcement, combat-tag menu, and verified compilation. The `deathbans off` toggle permits eliminated players to rejoin without resetting their elimination records.

The alpha is meant for source review only, not deployment. Do not replace working player data.
