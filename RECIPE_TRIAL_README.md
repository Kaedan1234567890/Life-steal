# Lifesteal Recipe Registration Trial — 0.2.2 alpha 3

These are ACTUAL data/chillzone/recipe/*.json Fabric-mod datapack recipes, unlike previous builds with zero recipe files.

Heart output: vanilla Nether Star with special custom_data and red bold name.
Revive Beacon output: vanilla Beacon with special custom_data and gold bold name.

IMPORTANT NOT COMPLETE: Recipe-book dynamic hiding / crafting prevention at >=15 hearts is NOT implemented. Heart crafting is currently unrestricted by heart count. Beacon ingredient accepts ANY Nether Star, because vanilla shaped ingredient cannot distinguish named custom Heart without custom recipe logic. Also no revive GUI, storage prevention, or deathban UI. Do NOT deploy to live server as a finished update. Test on an isolated instance after successful GitHub build.

Note: Recipe IDs chillzone:heart and chillzone:revive_beacon. Use `/recipe give @s chillzone:heart` and `/recipe give @s chillzone:revive_beacon` as a book visibility test; ordinary crafting itself should not require the recipe to be in recipe book.
