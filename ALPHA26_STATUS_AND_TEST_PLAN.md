# Chill Zone Lifesteal Alpha 26 — experimental SOURCE REVIEW

## Important: NOT RELEASE READY. DO NOT INSTALL ON A PUBLIC SERVER.

This is a source-preserving starting point from Alpha25, **not** a completed or compiled fix.

### Changes actually applied
- Configured combat timer 15/30/60/90/120 seconds now controls how long a player remains tagged after PvP damage, rather than the hard-coded 30 seconds.
- `/lifesteal craftstatus` continues to warn that pickup enforcement is missing. Its counters remain non-authoritative until a transaction-safe output pickup hook is implemented.
- Alpha25 code and save layout otherwise preserved.

### Critical remaining work before deploy
1. Intercept crafting RESULT pickup server-side for every normal click, shift-click, and recipe-book output extraction. Atomically validate <= 3 outputs in each 24-hour window starting on first successful pickup, before materials or items move. Cancel excess output, then update timestamp and count and persist on success; block repetitive extraction and automation bypasses. Server must protect remainders and crafting ingredient loss on denied pickup.
2. Implement logout death in a verified Fabric 26.3 disconnect lifecycle, preserving normal death drops/attribution, and broadcast the bold red combat-log message **before** normal leave message. Test an offline disconnect.
3. Block teleport commands during tag using a shared server-side command execution guard, including aliases and `/home`, `/homes`, `/spawn`, `/tpa`, and `/tpaccept` (whether issued by another player). Exempt only correctly authorized staff controls.
4. Verify or finish all 6 Combat Settings menu categories and restrictions; 5 are currently previews only. Verify death attribution doesn't double-consume hearts.
5. Build with Java 25 and Minecraft/Fabric 26.3; run multiplayer tests. Gradle wrapper is not present in this archive and this package has **not** been compiled.

### Suggested test cases
- Craft 1 Heart => 1/3. Craft 2 more => 3/3. Fourth blocked with no item or ingredient loss. Reset exactly 24 hours after first completed Heart (not server ticks). Restart at 2/3 and retain count and timer. Admin skip resets.
- Try output left-click/right-click, shift-click, recipe-book repetitions, full inventory, high latency, and crafting table vs player crafting.
- Tag 30s, disconnect at 29s, death drops and announcement before quit. Relog no free survival. Disconnect at 0s no punishment. Commands denied in both alias and primary command forms. Staff permissions preserved.
