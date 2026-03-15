package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.Statistic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Manages wraith (Phantom) companions for bad-morality players.
 *
 * Behaviour:
 * - Bad tier 1+ players get a wraith that follows them around.
 * - The wraith will NOT attack its owner or any other bad/neutral player.
 * - When a good-morality player comes within attack range the wraith targets them.
 * - Wraith despawns when the owner logs out or their morality improves.
 */
public class WraithManager implements Listener {

    private final MoralityEngine plugin;
    /** Maps owner player UUID → wraith entity UUID. */
    private final Map<UUID, UUID> ownerToWraith = new HashMap<>();
    /** Reverse lookup: wraith entity UUID → owner player UUID. */
    private final Map<UUID, UUID> wraithToOwner = new HashMap<>();

    public WraithManager(MoralityEngine plugin) {
        this.plugin = plugin;
        startTickTask();
    }

    // ── Tick task ─────────────────────────────────────────────────────────────

    private void startTickTask() {
        if (!plugin.getConfigManager().isWraithEnabled()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                tickExistingWraiths();
                spawnMissingWraiths();
            }
        }.runTaskTimer(plugin, 20L, 10L); // every 0.5 s
    }

    private void tickExistingWraiths() {
        Iterator<Map.Entry<UUID, UUID>> it = ownerToWraith.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            UUID ownerUuid = entry.getKey();
            UUID wraithUuid = entry.getValue();

            Player owner = org.bukkit.Bukkit.getPlayer(ownerUuid);
            if (owner == null) {
                despawnWraith(wraithUuid);
                it.remove();
                continue;
            }

            if (!isAffectedByMobMechanics(owner)) {
                despawnWraith(wraithUuid);
                it.remove();
                continue;
            }

            MoralityTier tier = plugin.getMoralityManager().getTier(ownerUuid);
            if (!tier.isBad() || tier.getLevel() < plugin.getConfigManager().getWraithMinBadTier()) {
                despawnWraith(wraithUuid);
                it.remove();
                continue;
            }

            // Despawn at dawn or when the player has slept (like vanilla phantoms)
            if (!meetsSpawnConditions(owner)) {
                despawnWraith(wraithUuid);
                it.remove();
                MoralityEngine.debug("Wraith despawned for " + owner.getName() + " — spawn conditions no longer met");
                continue;
            }

            // Locate the wraith entity
            Entity wraithEntity = owner.getWorld().getEntity(wraithUuid);
            if (wraithEntity == null || !wraithEntity.isValid()) {
                // Check other worlds (owner may have teleported)
                wraithEntity = findEntityAcrossWorlds(wraithUuid);
            }
            if (wraithEntity == null || !wraithEntity.isValid()) {
                it.remove();
                wraithToOwner.remove(wraithUuid);
                MoralityEngine.debug("Wraith for " + owner.getName() + " is gone — will respawn next tick");
                continue;
            }

            if (!(wraithEntity instanceof Mob wraith)) continue;

            // Find nearest good player within attack range
            double attackRange = plugin.getConfigManager().getWraithAttackRangeBlocks();
            Player goodTarget = findNearestGoodPlayer(wraith, attackRange, owner);

            if (goodTarget != null) {
                // Engage the good player
                if (!goodTarget.equals(wraith.getTarget())) {
                    wraith.setTarget(goodTarget);
                }
            } else {
                // No good target — follow owner
                wraith.setTarget(null);
                double distSq = wraith.getLocation().distanceSquared(owner.getLocation());
                double teleportRange = plugin.getConfigManager().getWraithTeleportRangeBlocks();

                if (distSq > teleportRange * teleportRange) {
                    // If the owner is deep underground, despawn instead of teleporting
                    int depth = owner.getWorld().getHighestBlockYAt(owner.getLocation()) - owner.getLocation().getBlockY();
                    if (depth >= 10) {
                        despawnWraith(wraithUuid);
                        it.remove();
                        wraithToOwner.remove(wraithUuid);
                        MoralityEngine.debug("Wraith despawned for " + owner.getName() + " — owner is underground");
                        continue;
                    }
                    wraith.teleport(owner.getLocation().clone().add(0, 8, 0));
                } else if (distSq > 9.0) { // more than 3 blocks
                    wraith.getPathfinder().moveTo(owner.getLocation(), 1.2);
                }
            }
        }
    }

    private void spawnMissingWraiths() {
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (Player player : world.getPlayers()) {
                UUID uuid = player.getUniqueId();
                if (ownerToWraith.containsKey(uuid)) continue;
                if (!isAffectedByMobMechanics(player)) continue;

                MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
                if (!tier.isBad() || tier.getLevel() < plugin.getConfigManager().getWraithMinBadTier()) continue;
                if (!meetsSpawnConditions(player)) continue;

                spawnWraith(player);
            }
        }
    }

    // ── Spawn / despawn helpers ───────────────────────────────────────────────

    private void spawnWraith(Player owner) {
        org.bukkit.Location spawnLoc = owner.getLocation().clone().add(0, 3, 0);
        Phantom wraith = owner.getWorld().spawn(spawnLoc, Phantom.class, ph -> {
            ph.customName(Component.text("Wraith"));
            ph.setCustomNameVisible(false);
            ph.setPersistent(true);
            ph.setRemoveWhenFarAway(false);
            ph.setSize(0); // smallest phantom
        });

        ownerToWraith.put(owner.getUniqueId(), wraith.getUniqueId());
        wraithToOwner.put(wraith.getUniqueId(), owner.getUniqueId());
        MoralityEngine.debug("Wraith spawned for " + owner.getName());
    }

    private void despawnWraith(UUID wraithUuid) {
        wraithToOwner.remove(wraithUuid);
        Entity e = findEntityAcrossWorlds(wraithUuid);
        if (e != null) e.remove();
    }

    private Entity findEntityAcrossWorlds(UUID uuid) {
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            Entity e = world.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    /**
     * Returns true when natural wraith-spawn conditions are satisfied:
     * the player is in the Overworld, it is night or a thunderstorm is active,
     * and the player has not slept for at least the configured number of ticks.
     */
    private boolean meetsSpawnConditions(Player player) {
        World world = player.getWorld();
        // Phantoms (and by extension wraiths) are an Overworld phenomenon
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        // Night: ticks 12542–23460 in the day cycle, or during a thunderstorm
        long time = world.getTime();
        boolean isNight = time >= 12542 && time <= 23460;
        if (!isNight && !world.isThundering()) return false;
        // Sleep deprivation check (mirrors vanilla phantom logic)
        int minTicks = plugin.getConfigManager().getWraithMinSleepDeprivationTicks();
        if (minTicks > 0 && player.getStatistic(Statistic.TIME_SINCE_REST) < minTicks) return false;
        // Only block spawning if the player is deep underground (10+ blocks below surface)
        int depth = world.getHighestBlockYAt(player.getLocation()) - player.getLocation().getBlockY();
        if (depth >= 10) return false;
        return true;
    }

    private Player findNearestGoodPlayer(Mob wraith, double range, Player owner) {
        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Entity nearby : wraith.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Player p)) continue;
            if (p.equals(owner)) continue;
            if (!isAffectedByMobMechanics(p)) continue;
            MoralityTier tier = plugin.getMoralityManager().getTier(p.getUniqueId());
            if (!tier.isGood()) continue;
            double distSq = wraith.getLocation().distanceSquared(p.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = p;
            }
        }
        return nearest;
    }

    // ── Gamemode guard ────────────────────────────────────────────────────────

    private boolean isAffectedByMobMechanics(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    // ── Suppress vanilla phantom spawning ─────────────────────────────────────
    // The wraith system replaces vanilla phantom behaviour entirely. Without this,
    // naturally-spawned Phantoms attack any sleep-deprived player regardless of
    // their morality tier — including good players who should never see phantoms.

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom)) return;
        // Only suppress naturally-spawned Phantoms — allow wraith spawns through
        // (those are triggered via World#spawn, which fires with SpawnReason.CUSTOM).
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        event.setCancelled(true);
    }

    // ── Prevent wraith from targeting its owner or non-good players ───────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        UUID wraithUuid = event.getEntity().getUniqueId();
        if (!wraithToOwner.containsKey(wraithUuid)) return;
        if (!(event.getTarget() instanceof Player target)) return;

        UUID ownerUuid = wraithToOwner.get(wraithUuid);
        // Cancel if target is owner, not in a targetable gamemode, or not a good player
        if (target.getUniqueId().equals(ownerUuid)) {
            event.setCancelled(true);
            return;
        }
        if (!isAffectedByMobMechanics(target)) {
            event.setCancelled(true);
            return;
        }
        MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
        if (!tier.isGood()) {
            event.setCancelled(true);
            return;
        }
        // Vanilla phantom AI independently targets sleep-deprived players anywhere in
        // render distance, ignoring our configured attack range. Enforce the range here
        // so wraiths cannot be pulled across the map toward good players who haven't slept.
        if (!event.getEntity().getWorld().equals(target.getWorld())) {
            event.setCancelled(true);
            return;
        }
        double attackRange = plugin.getConfigManager().getWraithAttackRangeBlocks();
        if (event.getEntity().getLocation().distanceSquared(target.getLocation()) > attackRange * attackRange) {
            event.setCancelled(true);
        }
    }

    // ── Prevent wraith from damaging its owner (safety net) ──────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Entity damager)) return;
        UUID wraithUuid = damager.getUniqueId();
        UUID ownerUuid = wraithToOwner.get(wraithUuid);
        if (ownerUuid == null) return;

        if (event.getEntity() instanceof Player victim && victim.getUniqueId().equals(ownerUuid)) {
            event.setCancelled(true);
        }
    }

    // ── Wraith death: clear drops and tracking ────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        UUID ownerUuid = wraithToOwner.remove(uuid);
        if (ownerUuid == null) return;

        ownerToWraith.remove(ownerUuid);
        event.getDrops().clear();
        event.setDroppedExp(0);
        MoralityEngine.debug("Wraith died for owner " + ownerUuid + " — will respawn next tick");
    }

    // ── Dimension change: despawn immediately, respawn in new world next tick ──

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        UUID wraithUuid = ownerToWraith.remove(playerUuid);
        if (wraithUuid == null) return;

        despawnWraith(wraithUuid);
        MoralityEngine.debug("Wraith despawned on world change for " + event.getPlayer().getName());
    }

    // ── Owner logout: despawn immediately ────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        UUID wraithUuid = ownerToWraith.remove(playerUuid);
        if (wraithUuid == null) return;

        despawnWraith(wraithUuid);
        MoralityEngine.debug("Wraith despawned on logout for " + event.getPlayer().getName());
    }
}
