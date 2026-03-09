package com.corelife.combat;

import com.corelife.CoreLife;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages combat-log ghost entities.
 *
 * When a combat-tagged player disconnects, a ghost villager is spawned at their location.
 * Villagers are used instead of zombies because they are immune to sunlight and cannot
 * convert to Drowned entities. Killing the ghost triggers the full death sequence
 * (life loss, XP drops, enderchest drop) in DeathListener.
 */
public class GhostManager {

    public static final String GHOST_OWNER_KEY = "corelife_ghost_owner";
    public static final String GHOST_MORALITY_KEY = "corelife_ghost_morality_tier";

    private final CoreLife plugin;
    private final NamespacedKey ownerKey;
    /** Maps player UUID → their active ghost entity UUID */
    private final Map<UUID, UUID> playerToGhost = new HashMap<>();
    /** Maps ghost entity UUID → player UUID */
    private final Map<UUID, UUID> ghostToPlayer = new HashMap<>();
    /**
     * Players whose ghost was killed while they were offline.
     * On rejoin they must be forced through Minecraft's death sequence
     * (so they respawn properly), but without losing another life.
     */
    private final Set<UUID> pendingGhostKills = new HashSet<>();

    public GhostManager(CoreLife plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "ghost_owner");
    }

    /**
     * Spawn a ghost for the disconnecting player.
     *
     * @param player          The player who logged out while combat-tagged.
     * @param moralityTierInt A snapshot of the player's current morality tier int
     *                        (supplied by MoralityEngine; 0 if ME is not present).
     */
    public void spawnGhost(Player player, int moralityTierInt) {
        if (!plugin.getConfigManager().isGhostEnabled()) {
            CoreLife.debug("Ghost disabled — treating combat logout as instant death for " + player.getName());
            // Ghost disabled: treat as instant death
            plugin.getDeathListener().processPlayerDeath(null, player.getUniqueId(), player.getLocation(), false);
            return;
        }

        Location loc = player.getLocation().clone();
        UUID playerUuid = player.getUniqueId();
        CoreLife.debug("Spawning combat-log ghost for " + player.getName()
                + " at " + (int) loc.getX() + "," + (int) loc.getY() + "," + (int) loc.getZ()
                + " moralityTier=" + moralityTierInt
                + " (despawns in " + plugin.getConfigManager().getGhostDurationSeconds() + "s)");

        boolean isBad = plugin.getMoralityProvider() != null
                && plugin.getMoralityProvider().isBadMorality(playerUuid);
        CoreLife.debug("Ghost entity type: " + (isBad ? "Pillager (bad morality)" : "Villager (good/neutral)"));

        LivingEntity ghost;
        if (isBad) {
            ghost = loc.getWorld().spawn(loc, Pillager.class, p -> {
                applyCommonGhostSettings(p, player, playerUuid, moralityTierInt);
                if (p.getEquipment() != null) p.getEquipment().clear();
            });
        } else {
            ghost = loc.getWorld().spawn(loc, Villager.class, v -> {
                applyCommonGhostSettings(v, player, playerUuid, moralityTierInt);
                v.setProfession(Villager.Profession.NONE);
                v.setVillagerType(Villager.Type.PLAINS);
            });
        }

        playerToGhost.put(playerUuid, ghost.getUniqueId());
        ghostToPlayer.put(ghost.getUniqueId(), playerUuid);

        // Schedule despawn after ghost-duration-seconds
        int durationSeconds = plugin.getConfigManager().getGhostDurationSeconds();
        new BukkitRunnable() {
            @Override
            public void run() {
                despawnGhost(playerUuid);
            }
        }.runTaskLater(plugin, 20L * durationSeconds);
    }

    private void applyCommonGhostSettings(LivingEntity entity, Player player, UUID playerUuid, int moralityTierInt) {
        entity.customName(Component.text(player.getName()));
        entity.setCustomNameVisible(true);
        entity.setAI(false);
        entity.setSilent(true);
        if (entity.getAttribute(Attribute.MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(player.getMaxHealth());
        }
        entity.setHealth(player.getHealth());
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerUuid.toString());
        entity.setMetadata(GHOST_OWNER_KEY, new FixedMetadataValue(plugin, playerUuid.toString()));
        entity.setMetadata(GHOST_MORALITY_KEY, new FixedMetadataValue(plugin, moralityTierInt));
    }

    /**
     * Despawn an active ghost without triggering the kill sequence.
     * Called when the player reconnects or the timer expires.
     */
    public void despawnGhost(UUID playerUuid) {
        UUID ghostUuid = playerToGhost.remove(playerUuid);
        if (ghostUuid == null) return;
        CoreLife.debug("Despawning ghost for player " + playerUuid + " (timer/reconnect)");
        ghostToPlayer.remove(ghostUuid);

        // Find and remove the entity
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(ghostUuid) && !entity.isDead()) {
                    entity.remove();
                    return;
                }
            }
        }
    }

    /** Called by DeathListener when a ghost is killed — cleans up tracking maps. */
    public void onGhostKilled(UUID ghostEntityUuid) {
        UUID playerUuid = ghostToPlayer.remove(ghostEntityUuid);
        if (playerUuid != null) {
            playerToGhost.remove(playerUuid);
        }
    }

    /**
     * Re-registers any ghost villagers that survived a server restart.
     * The PersistentDataContainer tag survives restarts; the in-memory maps do not.
     * Call this from onEnable() after the world is loaded.
     */
    public void restoreGhosts() {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity le)) continue;
                String raw = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (raw == null) continue;
                try {
                    UUID playerUuid = UUID.fromString(raw);
                    playerToGhost.put(playerUuid, le.getUniqueId());
                    ghostToPlayer.put(le.getUniqueId(), playerUuid);
                    count++;
                    CoreLife.debug("Restored orphaned ghost for player " + playerUuid);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (count > 0) {
            plugin.getLogger().info("CoreLife: Restored " + count + " orphaned ghost(s) after restart.");
        }
    }

    /** Mark that this player's ghost was killed while they were offline. */
    public void markPendingGhostKill(UUID playerUuid) {
        pendingGhostKills.add(playerUuid);
    }

    /** Returns true if the player has a pending ghost kill, consuming the flag. */
    public boolean consumePendingGhostKill(UUID playerUuid) {
        return pendingGhostKills.remove(playerUuid);
    }

    public boolean hasPendingGhostKill(UUID playerUuid) {
        return pendingGhostKills.contains(playerUuid);
    }

    public boolean hasActiveGhost(UUID playerUuid) {
        return playerToGhost.containsKey(playerUuid);
    }

    public boolean isGhost(LivingEntity entity) {
        return ghostToPlayer.containsKey(entity.getUniqueId());
    }

    public UUID getGhostOwner(LivingEntity ghost) {
        return ghostToPlayer.get(ghost.getUniqueId());
    }

    /** Returns a snapshot of all player UUIDs that currently have an active ghost. */
    public java.util.Set<UUID> getActiveGhostOwners() {
        return java.util.Set.copyOf(playerToGhost.keySet());
    }

    /** Returns the morality tier snapshot stored on the ghost, or 0 if absent. */
    public int getGhostMoralityTier(LivingEntity ghost) {
        if (!ghost.hasMetadata(GHOST_MORALITY_KEY)) return 0;
        return (int) ghost.getMetadata(GHOST_MORALITY_KEY).get(0).value();
    }
}
