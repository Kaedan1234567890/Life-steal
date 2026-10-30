# Only use original tick if the original version gate was not established.
execute unless score #life_steal.major load.status matches 2 if score #life_steal.loaded load.status matches 1 run function life_steal:v2.5.1/tick
execute if score #life_steal.major load.status matches 2 unless score #life_steal.minor load.status matches 5 if score #life_steal.loaded load.status matches 1 run function life_steal:v2.5.1/tick
execute if score #life_steal.major load.status matches 2 if score #life_steal.minor load.status matches 5 unless score #life_steal.patch load.status matches 1 if score #life_steal.loaded load.status matches 1 run function life_steal:v2.5.1/tick
