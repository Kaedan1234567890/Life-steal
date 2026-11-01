# Alpha 28 startup safety (source, not verified build)

This is a minimal change from Alpha 27. The mod ID and persistent data remain unchanged.

## Changes
- Mixin config `required: false` and `injectors.defaultRequire: 0`. A missing target injection is no longer a mandatory fatal startup error. This may cause crafting quotas or combat command blocks to silently NOT run if the injection does not match Minecraft 26.3.
- Version marker `0.4.6-alpha.28-startup-safety` shown by `/lifestealbuild` and the Gradle property.

## Important limitations
- We do not have the server's `logs/latest.log` or crash report, so the startup failure's actual cause is **not confirmed**.
- This is source code for GitHub; a successful GitHub build is not a tested server start.
- The quota, anti-combat-logging death and teleport-blocking features are NOT validated. DO NOT use this revision on a live production server without testing.
- For the next targeted correction, provide the first `Caused by:` section from `logs/latest.log`, or the latest file in `crash-reports/`.
- Before testing back up the world and config, remove the old Lifesteal JAR, and install only the newly built JAR. Verify with `/lifestealbuild`.
