# Alpha 31 — scoped changes

- Fixed the packet-listener mixin injection method from `handleCommand` to `handleChatCommand`, which is the mapped Minecraft method handling client command packets. This was the likely reason commands remained usable.
- Kept commands visible in autocomplete while canceling execution when a player is combat-tagged; red message: `You can't do that while in combat.`
- Expanded blocked teleport/home command roots for common TPA variants.
- Added a combat-log warning when the player rejoins: `You combat logged, and now you have to pay for it.` (red, bold). This is a **chat/system message**, NOT a replacement for the vanilla client-rendered `You Died!` screen title. A server-only mod cannot reliably replace that local title without client support.
- No change to Heart crafting restrictions, heart-transfer rewards, disconnect damage/death sequence, GUIs, recipes or saved-state format.

## Known limitations / test points
- This version was NOT compiled against Minecraft 26.3 or server-tested in this environment.
- If Minecraft 26.3 changes the handler further, consult `logs/latest.log` for mixin application errors.
- The rejoin warning uses in-memory state only: restarting the server after combat logging clears the warning, but the player's vanilla death remains.
- Some teleport mechanics not using typed player commands (plugin callbacks, UI teleports, commands run on behalf of players, etc.) need testing.
- Verify the server only contains ONE `chillzone_lifesteal` JAR.
