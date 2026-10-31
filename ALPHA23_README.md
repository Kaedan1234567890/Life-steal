# Alpha 23 – Skip state consistency diagnostic

**NOT A COMPLETE GAMEPLAY FIX. DO NOT DEPLOY TO LIVE SERVER AS A QUOTA FIX.**

- `/lifesteal admin skip <online player>` now explicitly persists both count=0 and start=0.
- Craft status no longer falsely implies the 3-per-day crafting output is enforced.
- The heart recipe is absent and there is no crafting pickup counter; daily restriction is NOT implemented.
- Revive Beacon recipe still accepts any Nether Star. Fixing this requires a custom ingredient/recipe implementation, not JSON name changes.
- No Combat logout death or command teleport blocking included.
- This package has not passed Gradle compilation/runtime testing.
