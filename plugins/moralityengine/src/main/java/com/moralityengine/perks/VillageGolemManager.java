package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Enhances village-spawned and player-built iron golems with boss-tier stats and a wind surge
 * ability that fires when the golem cannot pathfind to its current target.
 *
 * Intercepted spawn reasons:
 *   NATURAL         — village-AI-summoned golems
 *   BUILD_IRONGOLEM — spawned by a player placing a pumpkin on an iron T-pattern
 *
 * Wind surge fires when the golem's path to the target is blocked. Two conditions are
 * evaluated independently so that both deep walls and shallow pillars trigger correctly:
 *
 *   (a) Path is null or its terminal point is farther than wind-surge-path-threshold
 *       blocks from the player in 3D space (handles walls, gaps, deep elevation).
 *   (b) The path terminal point is more than ~1.5 blocks below the player (handles
 *       pillaring up just 2-3 blocks — golem reaches the base but can't melee upward).
 *
 * Target memory: vanilla AI drops the target when the player goes above melee range.
 * To handle this, the last active player target is remembered for TARGET_MEMORY_MS
 * milliseconds so the surge can still fire right after the target is dropped.
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
     * next tick for player-friendly golems, so the AI loop must re-apply it each cycle.
     * Entries expire after PROVOKED_MS so golems don't chase forever.
     */
    private final Map<UUID, UUID> provokedTarget     = new HashMap<>(); // golem → player
    private final Map<UUID, Long> provokedTargetTime = new HashMap<>(); // golem → epoch millis
    private static final long PROVOKED_MS = 30_000L;

    /** How long to keep firing at the last-known target after vanilla AI drops it. */
    private static final long TARGET_MEMORY_MS = 8_000L;

    private static final Set<CreatureSpawnEvent.SpawnReason> TRACKED_REASONS = Set.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,       // village-AI-summoned golems
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM // player-built (pumpkin + iron T)
    );

    public VillageGolemManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.villageGolemKey = new NamespacedKey(plugin, "village_golem");
        startAiLoop();
    }

    // ── Spawn interception ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfigManager().isVillageGolemEnabled()) return;
        if (!(event.getEntity() instanceof IronGolem golem)) return;
        if (!TRACKED_REASONS.contains(event.getSpawnReason())) return;
        if (plugin.getGolemBossManager().isGolemBoss(golem)) return;

        applyBossStats(golem);
        golem.getPersistentDataContainer().set(villageGolemKey, PersistentDataType.BYTE, (byte) 1);
        trackedGolems.add(golem.getUniqueId());
    }

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

    private void provoke(IronGolem golem, Player attacker, long now) {
        golem.setTarget(attacker);
        provokedTarget.put(golem.getUniqueId(), attacker.getUniqueId());
        provokedTargetTime.put(golem.getUniqueId(), now);
        lastKnownTarget.put(golem.getUniqueId(), attacker.getUniqueId());
        lastKnownTargetTime.put(golem.getUniqueId(), now);
    }

    private void applyBossStats(IronGolem golem) {
        var cfg = plugin.getConfigManager();

        AttributeInstance maxHp = golem.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(cfg.getGolemHealth());
        golem.setHealth(cfg.getGolemHealth());

        AttributeInstance atk = golem.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(cfg.getGolemAttackDamage());

        AttributeInstance spd = golem.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(cfg.getGolemMovementSpeed());

        for (String effectStr : cfg.getGolemEffects()) {
            PotionEffect effect = parsePotionEffect(effectStr, Integer.MAX_VALUE);
            if (effect != null) golem.addPotionEffect(effect);
        }
    }

    // ── AI loop ───────────────────────────────────────────────────────────────

    private void startAiLoop() {
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

                    // Re-apply provoked target every tick so vanilla AI can't clear it
                    enforceProvokedTarget(golem);

                    Player player = resolveTarget(golem);
                    if (player == null) continue;
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) continue;

                    if (!canPathTo(golem, player)) {
                        tryWindSurge(golem, player);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    /**
     * If the golem has an active provoked target, re-apply setTarget() so vanilla AI
     * cannot silently clear it. Expired or invalid provoked entries are cleaned up.
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

        // Re-apply every cycle — vanilla AI will have cleared it
        golem.setTarget(player);
    }

    /**
     * Returns the player the golem should consider for wind surge.
     *
     * Priority:
     *   1. Live target from vanilla AI — always preferred and refreshes the memory.
     *   2. Provoked target from the damage event — persists for PROVOKED_MS.
     *   3. Last-known target within TARGET_MEMORY_MS — used when vanilla AI drops
     *      the target immediately after the player pillars up, so we don't miss
     *      the first few surge windows while the golem is still underneath them.
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
        // Drop the memory if the player has wandered far enough that the golem
        // genuinely wouldn't care about them anymore.
        if (golem.getLocation().distanceSquared(last.getLocation()) > 32.0 * 32.0) return null;
        return last;
    }

    /**
     * Returns true if the golem has a viable path to the player — meaning the path
     * exists, terminates within the configured 3D distance threshold, AND the player
     * is not more than ~1.5 blocks above the path's terminal point.
     *
     * The height guard is evaluated independently from the distance threshold so that
     * a 2-3 block pillar (where the path endpoint is geometrically "close" in 3D but
     * still above melee reach) correctly returns false.  Iron golems cannot jump.
     */
    private boolean canPathTo(IronGolem golem, Player player) {
        var result = golem.getPathfinder().findPath(player);
        if (result == null) return false;

        Location finalPoint = result.getFinalPoint();
        if (finalPoint == null) return false;

        // Check 1 — path terminates too far away (wall, gap, deep elevation)
        double threshold = plugin.getConfigManager().getVillageGolemWindSurgePathThreshold();
        if (finalPoint.distanceSquared(player.getLocation()) > threshold * threshold) return false;

        // Check 2 — player is above the path endpoint (pillar-up scenario)
        // Golems cannot jump; anything more than ~1.5 blocks above the highest
        // reachable point is out of melee reach regardless of XZ proximity.
        if (player.getLocation().getY() > finalPoint.getY() + 1.5) return false;

        return true;
    }

    // ── Wind surge ────────────────────────────────────────────────────────────

    private void tryWindSurge(IronGolem golem, Player player) {
        long cooldownMs = plugin.getConfigManager().getVillageGolemWindSurgeCooldownSeconds() * 1000L;
        long lastSurge = surgeCooldowns.getOrDefault(golem.getUniqueId(), 0L);
        if (System.currentTimeMillis() - lastSurge < cooldownMs) return;

        surgeCooldowns.put(golem.getUniqueId(), System.currentTimeMillis());

        Vector direction = player.getLocation().toVector()
                .subtract(golem.getLocation().toVector())
                .normalize()
                .multiply(plugin.getConfigManager().getVillageGolemWindSurgePower())
                .add(new Vector(0, 0.3, 0));

        player.setVelocity(direction);

        Location eyeLoc = golem.getEyeLocation();
        World world = golem.getWorld();
        world.spawnParticle(Particle.CLOUD, eyeLoc, 20, 0.5, 0.4, 0.5, 0.08);
        world.spawnParticle(Particle.SWEEP_ATTACK, eyeLoc, 4, 0.4, 0.3, 0.4, 0.0);
        world.playSound(eyeLoc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.HOSTILE, 1.5f, 0.85f);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isVillageGolem(IronGolem golem) {
        return golem.getPersistentDataContainer().has(villageGolemKey, PersistentDataType.BYTE);
    }

    /**
     * Adds an already-spawned golem (e.g. the Golem Boss) to the wind surge tracking loop
     * without applying stats or the village_golem PDC key.
     */
    public void trackGolem(IronGolem golem) {
        trackedGolems.add(golem.getUniqueId());
    }

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
