# Chill Zone load fallback: ensure original objective exists even if version-resolver fails.
scoreboard objectives add load.status dummy
execute unless score #life_steal.loaded load.status matches 1 run function life_steal:v2.5.1/load/secondary
