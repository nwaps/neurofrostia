package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Enhances village-spawned and player-built iron golems with custom stats and a wind surge
 * ability that fires when the golem cannot pathfind to its current target.
 *
 * Intercepted spawn reasons:
 *   VILLAGE_DEFENSE — village-AI-spawned golems
 *   BUILD_IRONGOLEM — spawned by a player placing a pumpkin on an iron T-pattern
 *
 * Aggro model:
 *   When a player damages any tracked golem, ALL tracked golems within the configured
 *   aggro-radius are provoked to attack that player. Provoked state persists for
 *   PROVOKED_MS and is enforced via two mechanisms:
 *     (a) EntityTargetEvent listener blocks vanilla AI from clearing the target.
 *     (b) Fast enforce loop (every 3 ticks) re-applies setTarget + pathfinder.moveTo
 *         in case the event listener misses an edge case.
 *
 * Wind surge fires when the golem's path to the target is blocked (checked every 40 ticks).
 */
public class VillageGolemManager implements Listener {

    private final MoralityEngine plugin;
    private final NamespacedKey villageGolemKey;

    /** UUIDs of all enhanced golems currently being tracked. */
    private final Set<UUID> trackedGolems = new HashSet<>();

    /** Epoch-millis timestamp of the last wind surge per golem UUID. */
    private final Map<UUID, Long> surgeCooldowns = new HashMap<>();

    /**
     * When vanilla AI drops the target (e.g. player pillars up and golem loses aggro),
     * remember the last targeted player so wind surge can still fire in the window
     * immediately after the target is lost.
     */
    private final Map<UUID, UUID> lastKnownTarget     = new HashMap<>(); // golem → player
    private final Map<UUID, Long> lastKnownTargetTime = new HashMap<>(); // golem → epoch millis

    /**
     * Provoked targets set by the damage event. Vanilla AI clears setTarget() on the
     * next tick for player-friendly golems, so we block this via EntityTargetEvent and
     * re-apply via a fast enforce loop.
     * Entries expire after PROVOKED_MS so golems don't chase forever.
     */
    private final Map<UUID, UUID> provokedTarget     = new HashMap<>(); // golem → player
    private final Map<UUID, Long> provokedTargetTime = new HashMap<>(); // golem → epoch millis
    private static final long PROVOKED_MS = 30_000L;

    /** How long to keep firing at the last-known target after vanilla AI drops it. */
    private static final long TARGET_MEMORY_MS = 8_000L;

    private static final Set<CreatureSpawnEvent.SpawnReason> TRACKED_REASONS = Set.of(
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE, // village-AI-spawned golems
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM  // player-built (pumpkin + iron T)
    );

    public VillageGolemManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.villageGolemKey = new NamespacedKey(plugin, "village_golem");
        startEnforceLoop();
        startWindSurgeLoop();
    }

    // ── Reattach after restart ───────────────────────────────────────────────

    /**
     * Called from onEnable — deferred 1 tick so worlds are fully loaded.
     * Scans all loaded entities for surviving enhanced golems and re-adds them to tracking.
     */
    public void reattachAfterRestart() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof IronGolem golem && isVillageGolem(golem)) {
                        if (trackedGolems.add(golem.getUniqueId())) {
                            golem.setPlayerCreated(false);
                            count++;
                        }
                    }
                }
            }
            if (count > 0) {
                plugin.getLogger().info("Reattached " + count + " village golem(s) after restart.");
            }
        });
    }

    /**
     * When a chunk loads its entities, check for any enhanced golems that aren't tracked yet
     * (e.g. golem was in an unloaded chunk at startup, player walks into range later).
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!plugin.getConfigManager().isVillageGolemEnabled()) return;
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof IronGolem golem)) continue;
            if (!isVillageGolem(golem)) continue;
            if (trackedGolems.add(golem.getUniqueId())) {
                golem.setPlayerCreated(false);
                MoralityEngine.debug("Reattached village golem " + golem.getUniqueId() + " from chunk load.");
            }
        }
    }

    // ── Spawn interception ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfigManager().isVillageGolemEnabled()) return;
        if (!(event.getEntity() instanceof IronGolem golem)) return;
        if (!TRACKED_REASONS.contains(event.getSpawnReason())) return;

        applyBossStats(golem);
        golem.setPlayerCreated(false); // allow vanilla AI to target players
        golem.getPersistentDataContainer().set(villageGolemKey, PersistentDataType.BYTE, (byte) 1);
        trackedGolems.add(golem.getUniqueId());
        MoralityEngine.debug("Tracked village golem " + golem.getUniqueId()
                + " (reason=" + event.getSpawnReason() + ")");
    }

    // ── Damage → provoke ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemDamaged(EntityDamageByEntityEvent event) {
        if (!plugin.getConfigManager().isVillageGolemEnabled()) return;
        if (!(event.getEntity() instanceof IronGolem hitGolem)) return;
        if (!trackedGolems.contains(hitGolem.getUniqueId())) return;

        Player attacker;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        } else {
            return;
        }

        long now = System.currentTimeMillis();
        MoralityEngine.debug("Golem " + hitGolem.getUniqueId() + " hit by " + attacker.getName()
                + " — provoking self + nearby");
        provoke(hitGolem, attacker, now);

        double radius = plugin.getConfigManager().getVillageGolemAggroRadius();
        if (radius <= 0) return;
        double radiusSq = radius * radius;

        for (UUID id : trackedGolems) {
            if (id.equals(hitGolem.getUniqueId())) continue;
            IronGolem nearby = findGolem(id);
            if (nearby == null || nearby.isDead()) continue;
            if (!nearby.getWorld().equals(hitGolem.getWorld())) continue;
            if (nearby.getLocation().distanceSquared(hitGolem.getLocation()) > radiusSq) continue;
            provoke(nearby, attacker, now);
        }
    }

    /**
     * Blocks vanilla AI from clearing a provoked target. Without this, the golem's
     * NearestAttackableTargetGoal or other AI goals override our setTarget() within
     * a single tick, making provoked aggro unreliable.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGolemRetarget(EntityTargetEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)) return;
        UUID golemId = golem.getUniqueId();
        if (!trackedGolems.contains(golemId)) return;

        UUID targetId = provokedTarget.get(golemId);
        if (targetId == null) return;

        long elapsed = System.currentTimeMillis() - provokedTargetTime.getOrDefault(golemId, 0L);
        if (elapsed > PROVOKED_MS) {
            // Provocation expired — let vanilla AI do what it wants
            provokedTarget.remove(golemId);
            provokedTargetTime.remove(golemId);
            return;
        }

        // If vanilla AI is trying to clear or change the target, block it
        Entity newTarget = event.getTarget();
        if (newTarget instanceof Player p && p.getUniqueId().equals(targetId)) {
            return; // already targeting the right player
        }

        Player provokedPlayer = Bukkit.getPlayer(targetId);
        if (provokedPlayer == null || !provokedPlayer.isOnline()) {
            provokedTarget.remove(golemId);
            provokedTargetTime.remove(golemId);
            return;
        }

        // Cancel the vanilla retarget and force our provoked target
        event.setCancelled(true);
        MoralityEngine.debug("Blocked retarget on golem " + golemId
                + " (vanilla wanted: " + (newTarget == null ? "null" : newTarget.getType())
                + ", forcing: " + provokedPlayer.getName() + ")");
    }

    private void provoke(IronGolem golem, Player attacker, long now) {
        golem.setTarget(attacker);
        golem.getPathfinder().moveTo(attacker, 1.2);
        provokedTarget.put(golem.getUniqueId(), attacker.getUniqueId());
        provokedTargetTime.put(golem.getUniqueId(), now);
        lastKnownTarget.put(golem.getUniqueId(), attacker.getUniqueId());
        lastKnownTargetTime.put(golem.getUniqueId(), now);
    }

    private void applyBossStats(IronGolem golem) {
        var cfg = plugin.getConfigManager();

        AttributeInstance maxHp = golem.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(cfg.getVillageGolemHealth());
        golem.setHealth(cfg.getVillageGolemHealth());

        AttributeInstance atk = golem.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(cfg.getVillageGolemAttackDamage());

        AttributeInstance spd = golem.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(cfg.getVillageGolemMovementSpeed());

        for (String effectStr : cfg.getVillageGolemEffects()) {
            PotionEffect effect = parsePotionEffect(effectStr, Integer.MAX_VALUE);
            if (effect != null) golem.addPotionEffect(effect);
        }
    }

    // ── Enforce loop (fast — every 3 ticks) ──────────────────────────────────

    /**
     * Fast loop that re-applies provoked targets and pathfinder movement.
     * This runs every 3 ticks (~150ms) as a safety net in case EntityTargetEvent
     * doesn't fire for some edge case. Also handles cleanup of dead/unloaded golems.
     */
    private void startEnforceLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfigManager().isVillageGolemEnabled()) return;

                Iterator<UUID> it = trackedGolems.iterator();
                while (it.hasNext()) {
                    UUID id = it.next();
                    IronGolem golem = findGolem(id);

                    if (golem == null || golem.isDead() || !golem.isValid()) {
                        it.remove();
                        surgeCooldowns.remove(id);
                        lastKnownTarget.remove(id);
                        lastKnownTargetTime.remove(id);
                        provokedTarget.remove(id);
                        provokedTargetTime.remove(id);
                        continue;
                    }

                    enforceProvokedTarget(golem);
                }
            }
        }.runTaskTimer(plugin, 3L, 3L);
    }

    // ── Targeting + wind surge loop (every 20 ticks / 1 second) ────────────

    private void startWindSurgeLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfigManager().isVillageGolemEnabled()) return;
                boolean ironGolemAggroEnabled = plugin.getConfigManager().isIronGolemAggroTier1();

                for (UUID id : trackedGolems) {
                    IronGolem golem = findGolem(id);
                    if (golem == null || golem.isDead() || !golem.isValid()) continue;

                    // Active morality targeting: if the golem has no provoked target,
                    // scan for nearby bad-morality players and target the nearest one.
                    if (ironGolemAggroEnabled && !provokedTarget.containsKey(id)) {
                        Player badTarget = findNearestBadPlayer(golem);
                        if (badTarget != null) {
                            golem.setTarget(badTarget);
                            golem.getPathfinder().moveTo(badTarget, 1.2);
                        }
                    }

                    Player player = resolveTarget(golem);
                    if (player == null) continue;
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) continue;

                    if (!canPathTo(golem, player)) {
                        tryWindSurge(golem, player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Finds the nearest bad-morality player (tier 1+) within the configured aggro radius.
     * Returns null if no eligible player is found.
     */
    private Player findNearestBadPlayer(IronGolem golem) {
        double range = plugin.getConfigManager().getVillageGolemAggroRadius();
        if (range <= 0) return null;

        Player nearest = null;
        double nearestDistSq = range * range;
        for (Entity nearby : golem.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Player player)) continue;
            GameMode gm = player.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
            MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
            if (!tier.isBad() || tier.getLevel() < 1) continue;
            double distSq = golem.getLocation().distanceSquared(player.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * If the golem has an active provoked target, re-apply setTarget() and pathfinder
     * movement so vanilla AI cannot silently clear it.
     */
    private void enforceProvokedTarget(IronGolem golem) {
        UUID golemId = golem.getUniqueId();
        UUID playerId = provokedTarget.get(golemId);
        if (playerId == null) return;

        long elapsed = System.currentTimeMillis() - provokedTargetTime.getOrDefault(golemId, 0L);
        if (elapsed > PROVOKED_MS) {
            provokedTarget.remove(golemId);
            provokedTargetTime.remove(golemId);
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            provokedTarget.remove(golemId);
            provokedTargetTime.remove(golemId);
            return;
        }

        // Re-apply every cycle
        golem.setTarget(player);
        golem.getPathfinder().moveTo(player, 1.2);
    }

    /**
     * Returns the player the golem should consider for wind surge.
     *
     * Priority:
     *   1. Live target from vanilla AI — always preferred and refreshes the memory.
     *   2. Provoked target from the damage event — persists for PROVOKED_MS.
     *   3. Last-known target within TARGET_MEMORY_MS — used when vanilla AI drops
     *      the target immediately after the player pillars up.
     */
    private Player resolveTarget(IronGolem golem) {
        UUID golemId = golem.getUniqueId();
        LivingEntity current = golem.getTarget();

        if (current instanceof Player p) {
            lastKnownTarget.put(golemId, p.getUniqueId());
            lastKnownTargetTime.put(golemId, System.currentTimeMillis());
            return p;
        }

        // Check provoked target (set by damage event, persists across vanilla AI resets)
        UUID provokedId = provokedTarget.get(golemId);
        if (provokedId != null) {
            long elapsed = System.currentTimeMillis() - provokedTargetTime.getOrDefault(golemId, 0L);
            if (elapsed <= PROVOKED_MS) {
                Player provoked = Bukkit.getPlayer(provokedId);
                if (provoked != null && provoked.isOnline()
                        && golem.getLocation().distanceSquared(provoked.getLocation()) <= 32.0 * 32.0) {
                    return provoked;
                }
            }
        }

        UUID lastId = lastKnownTarget.get(golemId);
        if (lastId == null) return null;
        if (System.currentTimeMillis() - lastKnownTargetTime.getOrDefault(golemId, 0L) > TARGET_MEMORY_MS) return null;

        Player last = Bukkit.getPlayer(lastId);
        if (last == null || !last.isOnline()) return null;
        if (golem.getLocation().distanceSquared(last.getLocation()) > 32.0 * 32.0) return null;
        return last;
    }

    /**
     * Returns true if the golem has a viable path to the player.
     */
    private boolean canPathTo(IronGolem golem, Player player) {
        var result = golem.getPathfinder().findPath(player);
        if (result == null) return false;

        Location finalPoint = result.getFinalPoint();
        if (finalPoint == null) return false;

        double threshold = plugin.getConfigManager().getVillageGolemWindSurgePathThreshold();
        if (finalPoint.distanceSquared(player.getLocation()) > threshold * threshold) return false;

        // Player is above the path endpoint (pillar-up scenario)
        if (player.getLocation().getY() > finalPoint.getY() + 1.5) return false;

        return true;
    }

    // ── Wind surge ────────────────────────────────────────────────────────────

    private void tryWindSurge(IronGolem golem, Player player) {
        long cooldownMs = plugin.getConfigManager().getVillageGolemWindSurgeCooldownSeconds() * 1000L;
        long lastSurge = surgeCooldowns.getOrDefault(golem.getUniqueId(), 0L);
        if (System.currentTimeMillis() - lastSurge < cooldownMs) return;

        surgeCooldowns.put(golem.getUniqueId(), System.currentTimeMillis());

        Vector direction = golem.getLocation().toVector()
                .subtract(player.getLocation().toVector())
                .normalize()
                .multiply(plugin.getConfigManager().getVillageGolemWindSurgePower())
                .add(new Vector(0, 0.15, 0));

        player.setVelocity(direction);

        Location eyeLoc = golem.getEyeLocation();
        World world = golem.getWorld();
        world.spawnParticle(Particle.CLOUD, eyeLoc, 20, 0.5, 0.4, 0.5, 0.08);
        world.spawnParticle(Particle.SWEEP_ATTACK, eyeLoc, 4, 0.4, 0.3, 0.4, 0.0);
        world.playSound(eyeLoc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.HOSTILE, 1.5f, 0.85f);
    }

    // ── Debug / admin helpers ─────────────────────────────────────────────────

    public boolean isVillageGolem(IronGolem golem) {
        return golem.getPersistentDataContainer().has(villageGolemKey, PersistentDataType.BYTE);
    }

    /** Returns tracked golem count. */
    public int getTrackedCount() {
        return trackedGolems.size();
    }

    /** Returns a snapshot of debug info for each tracked golem near the given location. */
    public List<String> getDebugInfo(Location origin, double radius) {
        List<String> lines = new ArrayList<>();
        double radiusSq = radius * radius;

        for (UUID id : trackedGolems) {
            IronGolem golem = findGolem(id);
            if (golem == null || golem.isDead()) {
                lines.add("  " + id.toString().substring(0, 8) + " — DEAD/UNLOADED");
                continue;
            }
            if (!golem.getWorld().equals(origin.getWorld())) continue;
            if (golem.getLocation().distanceSquared(origin) > radiusSq) continue;

            String shortId = id.toString().substring(0, 8);
            LivingEntity target = golem.getTarget();
            String targetStr = target instanceof Player p ? p.getName() : (target == null ? "none" : target.getType().name());

            UUID provId = provokedTarget.get(id);
            String provStr = "none";
            if (provId != null) {
                long elapsed = System.currentTimeMillis() - provokedTargetTime.getOrDefault(id, 0L);
                Player provPlayer = Bukkit.getPlayer(provId);
                provStr = (provPlayer != null ? provPlayer.getName() : "offline")
                        + " (" + (PROVOKED_MS - elapsed) / 1000 + "s left)";
            }

            double dist = Math.sqrt(golem.getLocation().distanceSquared(origin));
            lines.add(String.format("  %s — %.0fm away | hp=%.0f | target=%s | provoked=%s | playerCreated=%s",
                    shortId, dist, golem.getHealth(), targetStr, provStr, golem.isPlayerCreated()));
        }

        if (lines.isEmpty()) lines.add("  (no tracked golems within " + (int) radius + " blocks)");
        return lines;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private IronGolem findGolem(UUID id) {
        for (World world : Bukkit.getWorlds()) {
            Entity e = world.getEntity(id);
            if (e instanceof IronGolem golem) return golem;
        }
        return null;
    }

    private PotionEffect parsePotionEffect(String config, int durationTicks) {
        String[] parts = config.split(":");
        try {
            PotionEffectType type = PotionEffectType.getByName(parts[0].trim().toUpperCase());
            if (type == null) return null;
            int amplifier = parts.length > 1 ? Integer.parseInt(parts[1].trim()) - 1 : 0;
            return new PotionEffect(type, durationTicks, amplifier);
        } catch (Exception e) { return null; }
    }
}
