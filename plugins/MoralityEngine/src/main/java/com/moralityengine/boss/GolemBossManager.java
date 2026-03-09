package com.moralityengine.boss;

import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import com.moralityengine.analytics.MoralityChangeReason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Manages the Golem Boss — a powerful custom Iron Golem spawned via Wither-pattern placement:
 *
 * Pattern (Y+1 = skulls, Y+0 = base blocks):
 *   [H][H][H]   ← Villager heads placed one block above base blocks
 *   [B][B][B]   ← Base blocks (OBSIDIAN by default)
 *      [B]      ← Additional base block (the stem of the T)
 *
 * H = PLAYER_HEAD with custom skull data (we accept any PLAYER_HEAD as "villager head")
 * B = golem-boss.spawn-block
 *
 * Note: For a true T-shape matching vanilla Wither, we check 3 heads in a horizontal line
 * at the same Y, base blocks directly below each head, plus one more base block below the
 * middle head (the "stem"). We check all 4 horizontal orientations.
 *
 * Villager heads drop from villagers at the configured rate.
 */
public class GolemBossManager implements Listener {

    private final MoralityEngine plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey villagerHeadKey;

    /** UUID of the currently active boss, or null. */
    private UUID activeBossUuid = null;
    /** Epoch millis of last boss death (for cooldown). */
    private long lastBossDeath = 0L;
    /** Damage dealt per player UUID during the current fight (for kill-credit splitting). */
    private final Map<UUID, Double> damageLedger = new HashMap<>();

    public GolemBossManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "golem_boss");
        this.villagerHeadKey = new NamespacedKey(plugin, "villager_head");
        registerVillagerHeadDropListener();
    }

    // ── Villager head drop ────────────────────────────────────────────────────

    private void registerVillagerHeadDropListener() {
        // Handled as part of this Listener via onVillagerDeath
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager)) return;
        double rate = plugin.getConfigManager().getVillagerHeadDropRate();
        if (Math.random() < rate) {
            event.getDrops().add(createVillagerHead());
        }
    }

    public ItemStack createVillagerHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.displayName(Component.text("Villager Head", NamedTextColor.GREEN));
        meta.getPersistentDataContainer().set(villagerHeadKey, PersistentDataType.BYTE, (byte) 1);

        String hash = plugin.getConfigManager().getVillagerHeadTextureHash();
        if (hash != null && !hash.isEmpty()) {
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(java.util.UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(new java.net.URL("http://textures.minecraft.net/texture/" + hash));
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            } catch (java.net.MalformedURLException e) {
                plugin.getLogger().warning("Invalid villager head texture hash in config: " + hash);
            }
        }

        head.setItemMeta(meta);
        return head;
    }

    public boolean isVillagerHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(villagerHeadKey, PersistentDataType.BYTE);
    }

    // ── Spawn pattern detection ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfigManager().isGolemBossEnabled()) return;
        if (!isVillagerHead(event.getItemInHand())) return;
        Location placed = event.getBlock().getLocation();
        checkSpawnPattern(placed);
    }

    /**
     * Checks if a Wither-style T-pattern exists that includes the newly-placed head.
     * The placed head may be the left, centre, or right skull of the 3-skull row.
     * Base block is one Y below each skull; stem is one more Y below the middle base.
     * We check 2 axis orientations × 3 possible positions for loc = 6 unique checks.
     */
    private void checkSpawnPattern(Location loc) {
        Material baseBlock = plugin.getConfigManager().getGolemSpawnBlock();
        if (baseBlock == Material.AIR) return;

        // 2 unique axis orientations (X and Z)
        int[][] directions = {{1, 0}, {0, 1}};

        for (int[] dir : directions) {
            int dx = dir[0], dz = dir[1];

            // Try loc as each of the 3 skull positions: left (offset=-1), centre (0), right (+1)
            for (int offset = -1; offset <= 1; offset++) {
                // Derive the centre skull location from loc and current offset
                Location centre = loc.clone().add(offset * (-dx), 0, offset * (-dz));
                Location left   = centre.clone().add(-dx, 0, -dz);
                Location right  = centre.clone().add( dx, 0,  dz);

                if (!isVillagerHeadBlock(left) || !isVillagerHeadBlock(centre) || !isVillagerHeadBlock(right)) continue;

                // Base blocks directly below each skull
                Location baseL = left.clone().add(0, -1, 0);
                Location baseM = centre.clone().add(0, -1, 0);
                Location baseR = right.clone().add(0, -1, 0);
                // Stem block — one further below the middle base
                Location stem  = baseM.clone().add(0, -1, 0);

                if (baseL.getBlock().getType() != baseBlock) continue;
                if (baseM.getBlock().getType() != baseBlock) continue;
                if (baseR.getBlock().getType() != baseBlock) continue;
                if (stem.getBlock().getType() != baseBlock) continue;

                // Check cooldown BEFORE consuming the pattern
                if (!canSpawn()) {
                    long remaining = (plugin.getConfigManager().getGolemRespawnCooldownMinutes() * 60_000L)
                            - (System.currentTimeMillis() - lastBossDeath);
                    Bukkit.broadcast(Component.text(
                            "The Golem Boss is on cooldown for " + (remaining / 60000) + " more minute(s).", NamedTextColor.RED));
                    // Spawn a smoke particle burst at the centre head to signal the failed attempt
                    if (centre.getWorld() != null) {
                        centre.getWorld().spawnParticle(Particle.LARGE_SMOKE, centre, 30, 0.5, 0.5, 0.5, 0.05);
                    }
                    return;
                }

                // Pattern matched — consume blocks and spawn boss
                consumePattern(left, centre, right, baseL, baseM, baseR, stem);
                spawnBoss(centre.clone());
                return;
            }
        }
    }

    private boolean isVillagerHeadBlock(Location loc) {
        if (loc.getBlock().getType() != Material.PLAYER_HEAD) return false;
        // The actual block doesn't carry ItemMeta, so we just check block type.
        // In a real scenario you'd use TileEntityState or stored PDC.
        return true;
    }

    private void consumePattern(Location... locations) {
        for (Location loc : locations) {
            loc.getBlock().setType(Material.AIR);
        }
    }

    // ── Boss spawning ─────────────────────────────────────────────────────────

    public boolean canSpawn() {
        if (activeBossUuid != null) {
            Entity boss = findBossEntity();
            if (boss != null && !boss.isDead()) return false;
            activeBossUuid = null;
        }
        int cooldownMinutes = plugin.getConfigManager().getGolemRespawnCooldownMinutes();
        if (cooldownMinutes <= 0) return true;
        long cooldownMs = cooldownMinutes * 60_000L;
        return System.currentTimeMillis() - lastBossDeath >= cooldownMs;
    }

    private void spawnBoss(Location loc) {
        IronGolem boss = loc.getWorld().spawn(loc, IronGolem.class, g -> {
            // Health
            AttributeInstance maxHp = g.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(plugin.getConfigManager().getGolemHealth());
            g.setHealth(plugin.getConfigManager().getGolemHealth());

            // Attack damage
            AttributeInstance atk = g.getAttribute(Attribute.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(plugin.getConfigManager().getGolemAttackDamage());

            // Movement speed
            AttributeInstance spd = g.getAttribute(Attribute.MOVEMENT_SPEED);
            if (spd != null) spd.setBaseValue(plugin.getConfigManager().getGolemMovementSpeed());

            // Potion effects
            for (String effectStr : plugin.getConfigManager().getGolemEffects()) {
                PotionEffect effect = parsePotionEffect(effectStr, Integer.MAX_VALUE);
                if (effect != null) g.addPotionEffect(effect);
            }

            g.customName(Component.text("Golem Boss", NamedTextColor.DARK_RED));
            g.setCustomNameVisible(true);
            g.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            g.setPlayerCreated(true);
        });

        activeBossUuid = boss.getUniqueId();
        damageLedger.clear();
        Bukkit.broadcast(Component.text("The Golem Boss has awakened!", NamedTextColor.DARK_RED));

        // Track damage for kill-credit splitting
        registerDamageTracking(boss);

        // Register the boss in VillageGolemManager's AI loop so wind surge fires
        plugin.getVillageGolemManager().trackGolem(boss);
    }

    private void registerDamageTracking(IronGolem boss) {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (boss.isDead() || !boss.isValid()) {
                    cancel();
                    return;
                }
                // Force boss to target the nearest bad morality player within the configured range.
                // Always call setTarget unconditionally: Paper's getTarget() can still return the
                // pre-death Player object after a kill (same instance persists across respawn),
                // which would make an equality guard silently skip re-engagement after the player
                // dies and returns. Calling setTarget every second is harmless.
                Player nearest = findNearestBadPlayer(boss, plugin.getConfigManager().getGolemAggroRangeBlocks());
                boss.setTarget(nearest); // null clears the target when no bad players are in range
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private Player findNearestBadPlayer(IronGolem boss, double range) {
        Player nearest = null;
        double nearestDistSq = range * range;
        for (Entity nearby : boss.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Player player)) continue;
            GameMode gm = player.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
            MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
            if (!tier.isBad()) continue;
            double distSq = boss.getLocation().distanceSquared(player.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)) return;
        if (!isGolemBoss(golem)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) return;
        damageLedger.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    // ── Boss death ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)) return;
        if (!isGolemBoss(golem)) return;

        activeBossUuid = null;
        lastBossDeath = System.currentTimeMillis();

        // Drop totem of dying
        double dropRate = plugin.getConfigManager().getGolemTotemDropRate();
        if (Math.random() < dropRate) {
            event.getDrops().add(plugin.getTotemOfDyingManager().createTotemOfDying());
        }

        // Apply bad morality progress split among all damage contributors
        if (plugin.getConfigManager().isBadKillGolemBossEnabled()) {
            var cfg = plugin.getConfigManager();
            double totalDamage = damageLedger.values().stream().mapToDouble(Double::doubleValue).sum();
            if (totalDamage > 0) {
                for (Map.Entry<UUID, Double> entry : damageLedger.entrySet()) {
                    double damageShare = entry.getValue() / totalDamage;
                    double totalPoints = ConfigManager.resolveDelta(cfg.getBadKillGolemBossMode(),
                            cfg.getBadKillGolemBossPoints(), cfg.getBadKillGolemBossPercentage(),
                            plugin.getMoralityManager().getScore(entry.getKey()));
                    double share = damageShare * totalPoints;
                    plugin.getMoralityManager().addScore(entry.getKey(), -share, MoralityChangeReason.KILL_GOLEM_BOSS);
                }
            }
        }
        damageLedger.clear();

        Bukkit.broadcast(Component.text("The Golem Boss has been defeated!", NamedTextColor.GOLD));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isGolemBoss(IronGolem golem) {
        return golem.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
    }

    /** Returns true if a boss is currently alive. */
    public boolean isActive() {
        Entity boss = findBossEntity();
        return boss != null && !boss.isDead();
    }

    /** Returns the remaining cooldown in milliseconds, or 0 if the boss can be spawned now. */
    public long cooldownRemainingMs() {
        int cooldownMs = plugin.getConfigManager().getGolemRespawnCooldownMinutes() * 60_000;
        return Math.max(0L, cooldownMs - (System.currentTimeMillis() - lastBossDeath));
    }

    /** Force-spawn the boss at the given location, bypassing the cooldown and any active boss check. */
    public void forceSpawn(Location loc) {
        if (!plugin.getConfigManager().isGolemBossEnabled()) return;
        activeBossUuid = null;
        lastBossDeath = 0L;
        spawnBoss(loc);
    }

    /** Reset the respawn cooldown so the boss can be spawned immediately via normal or forced means. */
    public void resetCooldown() {
        lastBossDeath = 0L;
    }

    /**
     * Called from onEnable — deferred 1 tick so worlds are fully settled.
     * Scans all loaded entities for a surviving Golem Boss and reattaches the aggro runnable.
     */
    public void reattachAfterRestart() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof IronGolem golem && isGolemBoss(golem)) {
                        reattach(golem);
                        return; // only one boss expected
                    }
                }
            }
        });
    }

    /**
     * Fires when a chunk (and its entities) is loaded after the initial world load,
     * e.g. a player walks into an area where the boss was sleeping across a restart.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof IronGolem golem)) continue;
            if (!isGolemBoss(golem)) continue;
            reattach(golem);
        }
    }

    private void reattach(IronGolem golem) {
        if (activeBossUuid != null && activeBossUuid.equals(golem.getUniqueId())) return;
        activeBossUuid = golem.getUniqueId();
        registerDamageTracking(golem);
        plugin.getVillageGolemManager().trackGolem(golem);
        plugin.getLogger().info("Reattached Golem Boss behavior to " + golem.getUniqueId());
    }

    private Entity findBossEntity() {
        if (activeBossUuid == null) return null;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            Entity e = world.getEntity(activeBossUuid);
            if (e != null) return e;
        }
        return null;
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) return p;
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
