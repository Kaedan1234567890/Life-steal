# Lifesteal 0.2.4-alpha.5 — Right-click interaction checkpoint

Changes: one shared server-side Revive Beacon activation method across UseItem, UseBlock, and UseEntity; successful interaction cancels vanilla block placement. Both the special item and `/lifesteal admin revive` open the same 27-slot chest UI. A regular Beacon is not a special Revive Beacon. A special Beacon is spent only after confirmed revival; closing/canceling does not consume it.

Test: give yourself a special Beacon using `/lifesteal admin giverevivebeacon <player> 1` (if granted permission); right-click air, stone, an interactable block, and an entity; verify a chest opens and no beacon block is placed. Then use `/lifesteal admin revive` and verify it opens the same menu. Check eliminated-player page, cancel and confirm.

CAUTION: Not compiled or tested with Minecraft 26.3. The prior Alpha4 already contains the chest menus and item-use callbacks. This update improves interaction coverage but cannot promise a 100% success rate until compiled and tested. Other planned features (container prevention, crafting limits and server command settings GUI) are not complete. Preserve production backup.
