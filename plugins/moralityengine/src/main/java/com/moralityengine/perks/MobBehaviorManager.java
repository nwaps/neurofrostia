package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implements mob AI behaviour overrides for bad morality players:
 * - Passive mob flee + aggro (tier 1+)
 * - Iron golem always hostile (tier 1+)
 * - Pillager neutral until provoked (tier 1+)
 * - Undead neutral until provoked (tier 1+)
 * - Zombie/skeleton horse conversion via Totem of Dying (tier 2+)
 */
public class MobBehaviorManager implements Listener {

    private final MoralityEngine plugin;
    /** Tracks pillagers/undead that were provoked by a specific bad player. */
    private final Set<UUID> provokedEntities = new HashSet<>();
    /** Rolling window (ms) for zombie reinforcement count tracking per bad player. */
    private static final long REINFORCEMENT_WINDOW_MS = 30_000L;
    /** Maps bad player UUID → timestamps of reinforcement spawns in the current window. */
    private final Map<UUID, ArrayDeque<Long>> reinforcementTimestamps = new HashMap<>();
    /** Tracks copper ingots that were dropped by bad players */
    private final Set<UUID> tradableCopper = new HashSet<>();
    /** Tracks drowned who're in a trade cooldown */
    private final Set<UUID> tradedDrowneds = new HashSet<>();

    public MobBehaviorManager(MoralityEngine plugin) {
        this.plugin = plugin;
        startFleeTask();
        startCopperTradeTask();
    }

    // ── Iron golem aggro ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player target)) return;
        if (!isAffectedByMobMechanics(target)) return;
        MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
        Entity attacker = event.getEntity();

        // Iron golems always aggro bad-tier-1+ players
        if (attacker instanceof IronGolem && tier.isBad() && tier.getLevel() >= 1
                && plugin.getConfigManager().isIronGolemAggroTier1()) {
            // Allow / force the aggro — do not cancel
            return;
        }

        // Pillager neutral: cancel target if bad player and pillager not provoked
        if (attacker instanceof Pillager && plugin.getConfigManager().isPillagerNeutralTier1()
                && tier.isBad() && tier.getLevel() >= 1
                && !provokedEntities.contains(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // Undead neutral: cancel target if bad player and undead not provoked
        if (isUndead(attacker) && plugin.getConfigManager().isUndeadNeutralTier1()
                && tier.isBad() && tier.getLevel() >= 1
                && !provokedEntities.contains(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // Witch neutral: witches never aggro bad players
        if (attacker instanceof Witch && plugin.getConfigManager().isWitchNeutralBadTier1()
                && tier.isBad() && tier.getLevel() >= 1) {
            event.setCancelled(true);
        }
    }

    // ── Drowned Trading ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemThrown(ItemSpawnEvent event) {
        Item thrown = event.getEntity();
        if (thrown.getItemStack().getType() != Material.COPPER_INGOT) return;
        UUID thrower = thrown.getThrower();
        if (thrower == null) return;
        Entity ent = Bukkit.getEntity(thrower);
        if (!(ent instanceof Player)) return;
        MoralityTier tier = plugin.getMoralityManager().getTier(thrower);
        if (!tier.isBad() || tier.getLevel() < 1) return;

        // Make copper non-tradable after 30s
        UUID copperUUID = thrown.getUniqueId();
        tradableCopper.add(copperUUID);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                tradableCopper.remove(copperUUID), 20L * 10);

        // Drowned copper attraction range, same as piglin attraction to gold
        double range = 16.0;
        for (Entity nearby : thrown.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Mob mob) || nearby.getType() != EntityType.DROWNED) continue;

            // Pathfind to thrown copper
            mob.getPathfinder().moveTo(thrown.getLocation());
        }
    }

    private void startCopperTradeTask() {
        new BukkitRunnable() {
            public void run() {
                for (UUID copper : tradableCopper) {
                    Entity ent = Bukkit.getEntity(copper);
                    if (ent == null) continue;
                    for (Entity nearby : ent.getNearbyEntities(1.0, 1.0, 1.0)) {
                        if (!(nearby instanceof Mob) || nearby.getType() != EntityType.DROWNED) continue;
                        if (!(ent instanceof Item item)) continue;

                        // Trade cooldown
                        UUID drownedUUID = nearby.getUniqueId();
                        if (tradedDrowneds.contains(drownedUUID)) continue;
                        tradedDrowneds.add(drownedUUID);
                        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                                tradedDrowneds.remove(drownedUUID),
                                plugin.getConfigManager().getDrownedTradeCooldownTicks());

                        MoralityEngine.debug("Conducting trade");
                        item.getItemStack().add(-1);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // Run every 1s
    }

    // ── Provocation (pillager/undead attacks bad player) ──────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // If a bad player attacks a neutral mob, mark it as provoked
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!isAffectedByMobMechanics(attacker)) return;
        MoralityTier tier = plugin.getMoralityManager().getTier(attacker.getUniqueId());
        if (!tier.isBad() || tier.getLevel() < 1) return;

        Entity victim = event.getEntity();
        if ((victim instanceof Pillager && plugin.getConfigManager().isPillagerNeutralTier1())
                || (isUndead(victim) && plugin.getConfigManager().isUndeadNeutralTier1())) {
            provokedEntities.add(victim.getUniqueId());
            // Remove provocation after the entity dies or after 30s
            UUID entityUuid = victim.getUniqueId();
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                    provokedEntities.remove(entityUuid), 20L * 30);
        }
    }

    // ── Iron golem seeks out bad players periodically ─────────────────────────

    // Handled by EntityTargetLivingEntityEvent — golems naturally target
    // after being told to. We can also force periodic targeting here.

    // ── Zombie/skeleton horse conversion ──────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.getConfigManager().isZombieHorseConversionTier2()) return;
        Player player = event.getPlayer();
        if (!isAffectedByMobMechanics(player)) return;
        if (!(event.getRightClicked() instanceof Horse horse)) return;

        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
        if (!tier.isBad() || tier.getLevel() < 2) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TOTEM_OF_UNDYING) {
            // Also check for our custom Totem of Dying item
            if (!plugin.getTotemOfDyingManager().isTotemOfDying(hand)) return;
        }

        // Convert horse to zombie horse
        event.setCancelled(true);
        Location loc = horse.getLocation();
        horse.remove();
        ZombieHorse zombieHorse = loc.getWorld().spawn(loc, ZombieHorse.class, zh -> {
            zh.setTamed(true);
            zh.setOwner(player);
        });
        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
    }

    // ── Passive mob flee task ─────────────────────────────────────────────────

    private void startFleeTask() {
        if (!plugin.getConfigManager().isPassiveMobFleeAggroTier1()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                    for (Player player : world.getPlayers()) {
                        if (!isAffectedByMobMechanics(player)) continue;
                        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
                        if (!tier.isBad() || tier.getLevel() < 1) continue;

                        double range = 16.0; // flee detection range
                        for (Entity nearby : player.getNearbyEntities(range, range, range)) {
                            if (!plugin.getConfigManager().getPassiveMobFleeAggroMobs()
                                    .contains(nearby.getType())) continue;
                            if (!(nearby instanceof Mob mob)) continue;

                            Vector awayDir = mob.getLocation().toVector()
                                    .subtract(player.getLocation().toVector());
                            if (awayDir.lengthSquared() < 0.001) continue;
                            awayDir.normalize();

                            // Pathfind away; fall back to velocity if no path exists
                            double dist = plugin.getConfigManager().getPassiveMobFleeDistance();
                            double speed = plugin.getConfigManager().getPassiveMobFleeSpeed();
                            Location fleeTarget = mob.getLocation().clone().add(awayDir.clone().multiply(dist));
                            boolean found = mob.getPathfinder().moveTo(fleeTarget, speed);
                            if (!found) {
                                Vector push = awayDir.clone().multiply(0.4);
                                push.setY(0.1);
                                mob.setVelocity(push);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L); // every 0.5 seconds
    }

    // ── Husbandry spawn bonus ─────────────────────────────────────────────────
    // Handled by a separate listener in PerkEventListener since it needs
    // access to EntityBreedEvent and PlayerInteractEntityEvent context.

    // ── Zombie reinforcement control ──────────────────────────────────────────

    /**
     * Intercepts vanilla zombie reinforcement spawns.
     * If the nearest bad morality player is within 32 blocks, applies the configured
     * per-tier cap. A max-count of 0 cancels all reinforcements; -1 allows unlimited.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReinforcementSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;

        Player target = findNearestBadPlayer(event.getLocation(), 32.0);
        if (target == null) return;

        MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
        if (!tier.isBad()) return;

        if (!plugin.getConfigManager().isZombieReinforcementsEnabled()) {
            event.setCancelled(true);
            return;
        }

        int maxCount = plugin.getConfigManager().getZombieReinforcementsMaxCount(tier.getLevel());
        if (maxCount == 0) {
            event.setCancelled(true);
            return;
        }
        if (maxCount == -1) return; // unlimited — vanilla behaviour

        // Rolling window cap
        UUID uuid = target.getUniqueId();
        long now = System.currentTimeMillis();
        ArrayDeque<Long> timestamps = reinforcementTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        timestamps.removeIf(t -> now - t > REINFORCEMENT_WINDOW_MS);

        if (timestamps.size() >= maxCount) {
            event.setCancelled(true);
            return;
        }
        timestamps.add(now);
    }

    private Player findNearestBadPlayer(Location loc, double range) {
        Player nearest = null;
        double nearestDistSq = range * range;
        for (Player p : loc.getWorld().getPlayers()) {
            if (!isAffectedByMobMechanics(p)) continue;
            MoralityTier tier = plugin.getMoralityManager().getTier(p.getUniqueId());
            if (!tier.isBad()) continue;
            double distSq = p.getLocation().distanceSquared(loc);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = p;
            }
        }
        return nearest;
    }

    // ── Undead XP suppression for bad players ─────────────────────────────────

    /**
     * Bad morality players do not gain vanilla XP from killing undead mobs.
     * Player kills are their primary XP source instead.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUndeadDeath(EntityDeathEvent event) {
        if (!isUndead(event.getEntity())) return;
        if (!plugin.getConfigManager().isUndeadNeutralTier1()) return;
        if (!plugin.getConfigManager().isUndeadSuppressVanillaXp()) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (!isAffectedByMobMechanics(killer)) return;
        MoralityTier tier = plugin.getMoralityManager().getTier(killer.getUniqueId());
        if (!tier.isBad()) return;
        MoralityEngine.debug("Suppressing undead XP drop for bad player " + killer.getName()
                + " (tier=" + tier + ", dropped=" + event.getDroppedExp() + ")");
        event.setDroppedExp(0);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private boolean isAffectedByMobMechanics(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private boolean isUndead(Entity entity) {
        return switch (entity.getType()) {
            case ZOMBIE, ZOMBIE_VILLAGER, ZOMBIFIED_PIGLIN, DROWNED, HUSK,
                 SKELETON, STRAY, WITHER_SKELETON, PHANTOM, ZOGLIN,
                 WITHER, SKELETON_HORSE, ZOMBIE_HORSE, GIANT -> true;
            default -> false;
        };
    }
}
