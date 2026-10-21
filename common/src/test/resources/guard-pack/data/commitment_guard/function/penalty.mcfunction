# Commitment is missing. Each effect lasts 3 s, so they run out by themselves once it's back.
execute unless data storage commitment:guard warned run say Commitment guard: Commitment is missing
data modify storage commitment:guard warned set value 1b
effect give @a minecraft:blindness 3 0 true
effect give @a minecraft:slowness 3 255 true
effect give @a minecraft:mining_fatigue 3 255 true
effect give @a minecraft:resistance 3 255 true
title @a times 0 50 10
title @a title {"text":"This world is committed","color":"red"}
title @a subtitle {"text":"Commitment is missing: reinstall it to play"}
