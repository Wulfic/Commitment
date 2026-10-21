# Every 40 game ticks. The mod sets "alive" every tick while it runs, so a missing "alive" means it's gone.
execute unless data storage commitment:guard alive run function commitment_guard:penalty
data remove storage commitment:guard alive
schedule function commitment_guard:check 40t replace
