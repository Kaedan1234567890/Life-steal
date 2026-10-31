# Alpha 8 experimental, not fully secure storage or Combat port

Added: server-side inspection every five ticks of open non-player menu slots; tries to return marked Heart/Revive Beacon stacks to player inventory without deleting them. Added PvP combat tags triggered on damage, 30s default, /lifesteal admin menu with ON/OFF and 15/30/60 second duration presets, persisted in existing JSON.

**Known limitations:** This only handles open menus, NOT unattended chest inventories or hopper pipes; bundles/shulker nesting not reliably covered. It is NOT a complete preventative storage guard. Combat tag currently only marks PvP participants and reports status via /lifesteal admin combat status; it does NOT block /home, /spawn or /tpa. Original Combat/Ranked code was unavailable, so no original GUI/items restrictions imported. This source is not Gradle compiled or runtime-tested and must not replace working live JAR before test.

GitHub build might surface Fabric API/mapping errors. Preserve world/data/chillzone-lifesteal.json backup.
