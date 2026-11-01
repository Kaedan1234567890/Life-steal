# Alpha 29 — partial, not verified

Changes from Alpha 28:
- On combat disconnect, damage is attributed to the last combat opponent (if still online) rather than generic damage; existing PVP death heart transfer then runs.
- If a reward Heart cannot enter the killer's inventory, drop it on the ground.
- Defensive guard for shift-clicking Heart crafting results in the regular crafting table: currently *blocks* bulk Heart crafting and instructs using individual clicks. This prevents the reported unlimited-shift-click path but does **not** yet support shift-click taking up to the remaining quota. The inventory 2x2 crafting UI and other automated paths are not proven protected.
- No custom death screen message is installed yet. It still may display You Died.

**Do not replace an installed working JAR before building and test-checking these changes.** Minecraft mixin targeting is not compile-tested; the guard is optional under the existing startup-safe mixin config, so a skipped guard must be treated as a serious exploit and the mod should not be made public before testing.
