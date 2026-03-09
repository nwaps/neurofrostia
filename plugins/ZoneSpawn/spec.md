# ZoneSpawn — Plugin Specification

## Overview

A Paper plugin that intercepts player respawn events, determines the player's assigned zone via LuckPerms, and teleports them to a random safe coordinate within that zone's defined boundaries. Also handles initial zone assignment teleportation from the DeluxeMenus zone picker.

**Target platform:** Paper 1.21.x  
**Java version:** 21+  
**Dependencies:** LuckPerms (required), WorldGuard (optional — for boundary validation only)  
**Soft dependencies:** None required beyond LuckPerms

---

## Config structure

`plugins/ZoneSpawn/config.yml`

```yaml
zones:
  blue:
    min-x: -2000
    max-x: -500
    min-z: -1000
    max-z: 1000
    world: world
  green:
    min-x: 500
    max-x: 2000
    min-z: -1000
    max-z: 1000
    world: world
  purple:
    min-x: -1000
    max-x: 1000
    min-z: 500
    max-z: 2000
    world: world
  red:
    min-x: -1000
    max-x: 1000
    min-z: -2000
    max-z: -500
    world: world

# How many times to attempt finding a safe location before giving up
max-attempts: 50

# Minimum Y level to consider a spawn valid (avoids underground spawns)
min-y: 60

# Fall back to zone's fixed EssentialsX spawn if no safe location found after max-attempts
fallback-to-group-spawn: true

# Whether to randomise on first zone selection (via /zonespawn teleport <player> <zone>)
randomise-on-selection: true

# Default group name for players with no zone assigned
default-group: default

# Debug logging — disable in production
debug: false
```

---

## Commands

### `/zonespawn reload`
Reloads config.yml without restarting the server.  
Permission: `zonespawn.reload`  
Default: op only

### `/zonespawn teleport <player> <zone>`
Assigns the player to the specified zone's LuckPerms group AND teleports them immediately to a random safe location within that zone. Used exclusively by the first-join menu (`ZonePickerFirstJoin`) so new players are placed in their chosen zone right away.  
Permission: `zonespawn.teleport`  
Default: op only (console always has access)

### `/zonespawn setzone <player> <zone>`
Assigns the player to the specified zone's LuckPerms group only — no teleport. Used by the zone-switching menu (`ZonePicker`) so that players changing zones update their future respawn location without being teleported away from where they currently are.  
Permission: `zonespawn.setzone`  
Default: op only (console always has access)

### `/zonespawn info <player>`
Shows the player's current zone assignment and the configured boundaries for that zone. Useful for debugging.  
Permission: `zonespawn.info`  
Default: op only

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `zonespawn.reload` | Reload config | op |
| `zonespawn.teleport` | Assign zone + teleport (first join menu) | op |
| `zonespawn.setzone` | Assign zone only, no teleport (zone switch menu) | op |
| `zonespawn.info` | View a player's zone info | op |
| `zonespawn.bypass` | Skip random respawn, use vanilla logic | false |
| `zonespawn.openmenu` | Open the zone switcher via `/zone` manually | false |

`zonespawn.openmenu` is checked by DeluxeMenus, not ZoneSpawn directly. Grant it to the `default` LuckPerms group so all players can open `/zone`. Console bypasses all permission checks so both menus work fine when triggered programmatically.

---

## Core logic

### Safe location finding

This is the most important part of the plugin. A naive random coordinate will frequently land inside a mountain, underwater, in mid-air, or on top of a tree. The algorithm must find a location that is safe to stand on.

```
function findSafeLocation(zone):
    for attempt = 1 to max-attempts:
        x = random integer between zone.min-x and zone.max-x
        z = random integer between zone.min-z and zone.max-z
        world = zone.world
        
        y = world.getHighestBlockYAt(x, z)
        
        block_at_y   = world.getBlockAt(x, y, z)
        block_above  = world.getBlockAt(x, y + 1, z)
        block_above2 = world.getBlockAt(x, y + 2, z)
        
        if y < min-y:
            continue  # too low, probably ocean floor
        
        if block_at_y is LAVA or WATER:
            continue
        
        if block_above is not AIR or TALL_GRASS or FERN etc:
            continue  # not enough headroom
            
        if block_above2 is not AIR etc:
            continue  # not enough headroom
        
        # Valid location found
        return Location(world, x + 0.5, y + 1, z + 0.5)
    
    return null  # exhausted attempts, trigger fallback
```

**Block types to reject as the standing block (block_at_y):**
- LAVA, WATER, STATIONARY_LAVA, STATIONARY_WATER
- CACTUS
- FIRE, SOUL_FIRE
- MAGMA_BLOCK
- SWEET_BERRY_BUSH
- POWDER_SNOW
- POINTED_DRIPSTONE (upward facing)

**Block types to allow as passable (block_above / block_above2):**
- AIR, CAVE_AIR, VOID_AIR
- Grass variants: SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN
- Flowers: all single and double flower types
- SNOW (layer, not block)
- TORCH and variants
- VINE

Paper API method to use: `world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)` — this finds the highest block ignoring leaves, so players don't spawn on top of a forest canopy.

### Respawn event handling

Listen to `PlayerRespawnEvent`. This fires when a player respawns after death.

```
on PlayerRespawnEvent:
    player = event.getPlayer()
    
    if player has permission zonespawn.bypass:
        return  # let normal respawn logic handle it
    
    if event.isBedSpawn() or event.isAnchorSpawn():
        return  # player has a bed/anchor, respect it
    
    zone = getZoneForPlayer(player)
    
    if zone is null:
        # No zone assigned — open the zone switcher menu 1 tick after respawn
        # and make the player invulnerable until they pick a zone.
        # Uses ZonePicker (setzone menu) not ZonePickerFirstJoin (teleport menu)
        # since the respawn event already places the player — we just need zone assignment.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.setInvulnerable(true)
            Bukkit.dispatchCommand(console, "deluxemenus open ZonePicker " + player.getName())
        }, 1L)
        return
    
    location = findSafeLocation(zone)
    
    if location is null and fallback-to-group-spawn is true:
        return  # exhausted attempts, let EssentialsX group spawn handle it
    
    if location is null:
        player.sendMessage("Could not find a safe spawn in your zone. Contact an admin.")
        return
    
    event.setRespawnLocation(location)
```

The 1-tick delay is required — opening a GUI in the same tick as the respawn event fires before the player has fully loaded in, causing the menu to silently fail.

### Invulnerability management

Players who have no zone assigned are made invulnerable when the zone picker opens so they can't be killed while choosing. Invulnerability must be removed in two places:

**On zone selection** — in both the `/zonespawn teleport` and `/zonespawn setzone` command handlers, after assignment (and teleport where applicable):
```
player.setInvulnerable(false)
```

**On menu close without picking** — listen to `InventoryCloseEvent` as a fallback in case the player closes the GUI without selecting a zone:
```
on InventoryCloseEvent:
    player = event.getPlayer()
    
    if not player.isInvulnerable():
        return  # not our concern
    
    title = event.getView().title()
    
    if title does not contain "Choose Your Spawn Zone":
        return  # a different inventory closed, ignore
    
    # Delay 2 ticks before removing invulnerability.
    # When a player clicks a zone, the inventory close event fires BEFORE
    # the click command runs. The 2-tick delay lets the teleport command
    # execute and call setInvulnerable(false) first. If no command ran
    # (player just closed the menu), the delay expires and removes it cleanly.
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
        player.setInvulnerable(false)
    }, 2L)
```

The title string `"Choose Your Spawn Zone"` must match your DeluxeMenus `menu_title` value exactly (strip colour codes when comparing).

### Disconnect handling

When a player disconnects mid-flow (menu open, no zone assigned, currently invulnerable), `InventoryCloseEvent` fires but the 2-tick delayed removal may run against an invalid/offline player object — resulting in either a silent no-op or an NPE. Worse, invulnerability is saved to player data, so if removal doesn't run cleanly the player rejoins still invulnerable with no zone and no menu — permanently invulnerable until an admin intervenes.

Fix this with a `PlayerQuitEvent` listener that cleans up immediately on disconnect:

```
on PlayerQuitEvent:
    player = event.getPlayer()
    
    if player.isInvulnerable():
        player.setInvulnerable(false)
```

This runs synchronously before the player fully disconnects so the player object is still valid and state is cleared reliably.

### Join handling

The `PlayerJoinEvent` listener has one job only: clearing stale invulnerability left over from a server crash mid-flow. It does **not** open the zone picker — first join is handled by EssentialsX `newbie-commands`, and reconnects after a clean disconnect are handled by the player typing `/zone` or dying again.

```
on PlayerJoinEvent:
    player = event.getPlayer()
    
    # Clear stale invulnerability from a crash during zone picker flow
    if player.isInvulnerable():
        player.setInvulnerable(false)
        player.sendMessage("&8» &cYour invulnerability was cleared. Type /zone to pick your spawn zone.")
```

This is intentionally minimal — opening the menu automatically on every join with no zone would fire in too many legitimate situations (admin accounts, LuckPerms data wipes, etc).

### Zone lookup

```
function getZoneForPlayer(player):
    luckperms = LuckPerms API
    user = luckperms.getUserManager().getUser(player.getUniqueId())
    
    groups = user.getNodes()
              .filter(type == GROUP)
              .map(group name)
    
    for each group in groups:
        if config.zones contains group:
            return config.zones.get(group)
    
    return null
```

Note: LuckPerms users can have multiple groups. Iterate in priority order and return the first match against a configured zone name. This means if a player is in `blue` and `admin`, and only `blue` is a configured zone, they get `blue` correctly.

### `/zonespawn teleport` command handling

```
on command zonespawn teleport <player> <zone>:
    if zone not in config.zones:
        sender.sendMessage("Unknown zone: " + zone)
        return
    
    # Assign LuckPerms group — clear existing zone groups first
    lp_user = luckperms.getUser(player)
    
    for each configured zone name:
        lp_user.data().remove(InheritanceNode.builder(zone_name).build())
    
    lp_user.data().add(InheritanceNode.builder(zone).build())
    luckperms.getUserManager().saveUser(lp_user)
    
    # Find and teleport
    location = findSafeLocation(config.zones.get(zone))
    
    if location is null:
        sender.sendMessage("Could not find safe location in " + zone + " after " + max-attempts + " attempts")
        return
    
    player.teleport(location)
    player.setInvulnerable(false)  # remove immunity granted when zone picker opened
    player.sendMessage("You have been placed in the " + zone + " zone!")
```

The key detail here is **clearing existing zone groups before setting the new one**. Without this, a player who switches from blue to green will be in both groups simultaneously, and the zone lookup will return whichever comes first. The command should iterate all configured zone names and remove those group nodes before adding the new one.

### `/zonespawn setzone` command handling

Identical to `teleport` except the player is not teleported. Used by the `/zone` switcher menu so players can update their future respawn zone without being moved.

```
on command zonespawn setzone <player> <zone>:
    if zone not in config.zones:
        sender.sendMessage("Unknown zone: " + zone)
        return
    
    # Assign LuckPerms group — clear existing zone groups first
    lp_user = luckperms.getUser(player)
    
    for each configured zone name:
        lp_user.data().remove(InheritanceNode.builder(zone_name).build())
    
    lp_user.data().add(InheritanceNode.builder(zone).build())
    luckperms.getUserManager().saveUser(lp_user)
    
    player.setInvulnerable(false)  # remove immunity if set during respawn no-zone flow
    player.sendMessage("Your spawn zone has been set to " + zone + "!")
```

---

## DeluxeMenus integration

Two menus are required — they look identical to the player but call different ZoneSpawn commands:

**`ZonePickerFirstJoin.yml`** — opened by EssentialsX `newbie-commands` on first join. Calls `zonespawn teleport` so the player is assigned their zone and immediately teleported there.

**`ZonePicker.yml`** — opened by `/zone` for zone switching. Calls `zonespawn setzone` so the player's future respawn zone is updated without teleporting them. Also opened by ZoneSpawn's respawn handler when a player has no zone.

Example button config for each menu — the only difference is the command called:

```yaml
# ZonePickerFirstJoin.yml — assign + teleport
left_click_commands:
  - '[console] zonespawn teleport %player_name% blue'
  - '[message] &8» &9You have joined the Blue Zone!'
  - '[close]'

# ZonePicker.yml — assign only, no teleport
left_click_commands:
  - '[console] zonespawn setzone %player_name% blue'
  - '[message] &8» &9Your spawn zone has been set to Blue!'
  - '[close]'
```

`ZonePicker.yml` should have the `open_requirement` permission check for `zonespawn.openmenu` since it's the menu tied to the `/zone` command. `ZonePickerFirstJoin.yml` does not need this check since it is only ever opened via console command, never directly by players.

---

## plugin.yml

```yaml
name: ZoneSpawn
version: 1.0.0
main: com.yourname.zonespawn.ZoneSpawn
api-version: '1.21'
description: Per-zone random respawning based on LuckPerms group

depend:
  - LuckPerms

commands:
  zonespawn:
    description: ZoneSpawn admin commands
    usage: /zonespawn <reload|teleport|setzone|info>
    permission: zonespawn.use

permissions:
  zonespawn.reload:
    description: Reload ZoneSpawn config
    default: op
  zonespawn.teleport:
    description: Assign zone and teleport player (first join)
    default: op
  zonespawn.setzone:
    description: Assign zone only, no teleport (zone switching)
    default: op
  zonespawn.info:
    description: View a player's zone info
    default: op
  zonespawn.bypass:
    description: Bypass zone respawn, use vanilla logic
    default: false
  zonespawn.openmenu:
    description: Open the zone switcher via /zone
    default: false
```

---

## Suggested class structure

```
ZoneSpawn.java              — Main plugin class, onEnable/onDisable, registers listeners and commands
ConfigManager.java          — Loads and parses config.yml, exposes getZone(name), getAllZones()
ZoneConfig.java             — Simple data class: name, minX, maxX, minZ, maxZ, world name
SafeLocationFinder.java     — findSafeLocation(ZoneConfig) — all the block-checking logic lives here
RespawnListener.java        — Listens to PlayerRespawnEvent, triggers ZonePicker menu + invulnerability on no-zone
JoinQuitListener.java       — PlayerJoinEvent: clears stale invulnerability from crashes only. PlayerQuitEvent: clears invulnerability immediately on disconnect
InventoryCloseListener.java — Listens to InventoryCloseEvent, removes invulnerability as fallback when menu is closed without picking
ZoneSpawnCommand.java       — Handles /zonespawn subcommands including teleport (assign + tp) and setzone (assign only)
LuckPermsHook.java          — Wrapper around LuckPerms API calls: getZoneForPlayer(), setZone()
```

Keeping `SafeLocationFinder` separate makes it easy to unit test the location logic without spinning up a server. Keeping `LuckPermsHook` separate means if LuckPerms ever changes its API you only touch one file.

---

## Edge cases to handle

**Player has no zone on first join** — EssentialsX `newbie-commands` opens `ZonePickerFirstJoin`. Player picks a zone, `zonespawn teleport` runs, they are assigned and teleported. Invulnerability is not set here since world spawn is a safe admin-controlled area and the menu appears immediately.

**Player switches zone via `/zone`** — `ZonePicker` opens, player picks a zone, `zonespawn setzone` assigns the new zone without teleporting. Player stays where they are, future respawns go to the new zone.

**Player has no zone on respawn** — `PlayerRespawnEvent` opens `ZonePicker` with invulnerability set. Player picks a zone, `zonespawn setzone` assigns zone and clears invulnerability. Player respawns at wherever the respawn event placed them — no teleport needed since the respawn location is already set.

**Player disconnects mid-flow (menu open, no zone, invulnerable)** — `PlayerQuitEvent` immediately calls `setInvulnerable(false)`. On reconnect, player types `/zone` to pick a zone or waits until their next death.

**Server crash during mid-flow** — Player rejoins with stale invulnerability. `PlayerJoinEvent` detects `isInvulnerable()` is true, clears it, and sends a message telling them to type `/zone`.

**Player closes menu without picking** — `InventoryCloseEvent` fires and removes invulnerability after a 2-tick delay. Player remains without a zone and can type `/zone` to pick at any time.

**Zone boundaries are all ocean** — The safe location finder will exhaust attempts and fall back to the EssentialsX group spawn. Log a warning to console so the admin knows to reconfigure the zone boundaries.

**Player switches zones mid-session** — `getZoneForPlayer()` is called fresh on every respawn event. No caching needed.

**LuckPerms not available on startup** — The `depend` declaration in plugin.yml will prevent ZoneSpawn from loading at all if LuckPerms isn't present. The server will log a clear error.

**`findSafeLocation` is called on the main thread** — `getHighestBlockYAt` and block lookups are main-thread operations in Paper. The respawn event fires on the main thread, so this is fine for 6–15 players. If you ever scale up significantly, consider moving the location search to an async task that sets the respawn location via a callback, but this is unnecessary for your server size.

**Player dies in the nether or end** — `PlayerRespawnEvent` still fires. The zone lookup works the same way — the player teleports to their zone in the overworld. This is the desired behaviour for a soft-hardcore server.