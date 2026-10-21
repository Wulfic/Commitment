# Runs on every (re)load. A stale "alive" from the last save must not count as the mod being here.
data remove storage commitment:guard alive
data remove storage commitment:guard warned
schedule function commitment_guard:check 40t replace
