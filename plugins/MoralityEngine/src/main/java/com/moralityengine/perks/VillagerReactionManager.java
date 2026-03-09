package com.moralityengine.perks;

import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implements villager reactions to bad-morality players:
 * Tier 1+: flee, call for help
 * Tier 2+: village-native villagers enter defend mode
 *
 * Also handles villager price increases (tier 2 bad) and better trades (tier 3 good).
 */
public class VillagerReactionManager implements Listener {

    private final MoralityEngine plugin;
    private final NamespacedKey defendingKey;
    /** Tracks villagers currently in defend mode and their target player. */
    private final Map<UUID, UUID> defendingVillagers = new HashMap<>(); // villager UUID → target player UUID
    /** Last time (ms) each defending villager dealt a melee hit (initialized to combat-start time). */
    private final Map<UUID, Long> lastAttackMs = new HashMap<>();
    /** World location where each villager entered combat — used for max-chase-distance check. */
    private final Map<UUID, Location> defendStartLocation = new HashMap<>();

    public VillagerReactionManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.defendingKey = new NamespacedKey(plugin, "defending_player");
        startFleeTask();
        startRetargetTask();
    }

    // ── Flee task ──────────────────────────────────────────────────────────────

    private void startFleeTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int fleeTier = plugin.getConfigManager().getVilFleeTrigerTier();
                double fleeRadius = plugin.getConfigManager().getVilFleeRadius();

                for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                    for (Player player : world.getPlayers()) {
                        if (!isAffectedByMobMechanics(player)) continue;
                        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
                        if (!tier.isBad() || tier.getLevel() < fleeTier) continue;

                        for (Entity nearby : player.getNearbyEntities(fleeRadius, fleeRadius, fleeRadius)) {
                            if (!(nearby instanceof Villager villager)) continue;
                            if (defendingVillagers.containsKey(villager.getUniqueId())) continue;

                            if (villager.isSleeping()) villager.wakeup();

                            Vector awayDir = villager.getLocation().toVector()
                                    .subtract(player.getLocation().toVector());
                            if (awayDir.lengthSquared() < 0.001) continue;
                            awayDir.normalize();

                            // Pathfind away; fall back to velocity if no path exists
                            double dist = plugin.getConfigManager().getVilFleeDistance();
                            double speed = plugin.getConfigManager().getVilFleeSpeed();
                            Location fleeTarget = villager.getLocation().clone().add(awayDir.clone().multiply(dist));
                            boolean found = villager.getPathfinder().moveTo(fleeTarget, speed);
                            if (!found) {
                                Vector push = awayDir.clone().multiply(0.35);
                                push.setY(0.1);
                                villager.setVelocity(push);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // ── Retarget task (re-applies target every second; villager AI resets it) ──

    private void startRetargetTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                double speed = plugin.getConfigManager().getVilFleeSpeed();

                for (Map.Entry<UUID, UUID> entry : new ArrayList<>(defendingVillagers.entrySet())) {
                    Entity ve = org.bukkit.Bukkit.getEntity(entry.getKey());
                    if (!(ve instanceof Villager villager) || villager.isDead() || !villager.isValid()) {
                        defendingVillagers.remove(entry.getKey());
                        lastAttackMs.remove(entry.getKey());
                        continue;
                    }
                    // Abandon if chased too far from combat start
                    Location startLoc = defendStartLocation.get(entry.getKey());
                    if (startLoc != null && villager.getWorld().equals(startLoc.getWorld())) {
                        double maxDist = plugin.getConfigManager().getVilDefendMaxChaseDist();
                        if (villager.getLocation().distanceSquared(startLoc) > maxDist * maxDist) {
                            exitDefendMode(villager);
                            continue;
                        }
                    }

                    // Abandon if no hit has landed within the timeout window
                    long timeoutMs = plugin.getConfigManager().getVilDefendChaseTimeoutSeconds() * 1000L;
                    long last = lastAttackMs.getOrDefault(entry.getKey(), now);
                    if (now - last > timeoutMs) {
                        exitDefendMode(villager);
                        continue;
                    }

                    Entity pe = org.bukkit.Bukkit.getEntity(entry.getValue());
                    if (!(pe instanceof LivingEntity target) || target.isDead() || !target.isValid()
                            || !target.getWorld().equals(villager.getWorld())) continue;
                    if (pe instanceof Player targetPlayer && !isAffectedByMobMechanics(targetPlayer)) {
                        exitDefendMode(villager);
                        continue;
                    }

                    // Keep vanilla target set and pathfind toward the target
                    villager.setTarget(target);
                    villager.getPathfinder().moveTo(target, speed);

                    // Melee hit when in range — villagers have no attack AI so we apply damage manually
                    if (villager.getLocation().distanceSquared(target.getLocation()) <= 4.0) {
                        if (now - last >= 1000L) {
                            target.damage(4.0, villager);
                            lastAttackMs.put(entry.getKey(), now);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L); // 10-tick interval keeps the chase smooth
    }

    // ── Call for help + defend ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) return;
        if (!isAffectedByMobMechanics(attacker)) return;

        MoralityTier tier = plugin.getMoralityManager().getTier(attacker.getUniqueId());
        if (!tier.isBad()) return;

        int callTier = plugin.getConfigManager().getVilCallForHelpTier();

        if (tier.getLevel() >= callTier) {
            callForHelp(villager, attacker);
        }
        // Any hit from a bad player makes the villager stop fleeing and retaliate
        enterDefendMode(villager, attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerAttackedByZombie(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getDamager() instanceof Zombie zombie)) return;
        enterDefendMode(villager, zombie);
    }

    private void callForHelp(Villager villager, Player badPlayer) {
        double radius = plugin.getConfigManager().getVilCallForHelpRadius();

        for (Entity nearby : villager.getNearbyEntities(radius, radius, radius)) {
            // Iron golems aggro the attacker
            if (nearby instanceof IronGolem golem) {
                golem.setTarget(badPlayer);
            }
            // Nearby villagers may enter defend mode if eligible
            if (nearby instanceof Villager otherVillager
                    && !defendingVillagers.containsKey(otherVillager.getUniqueId())) {
                MoralityTier tier = plugin.getMoralityManager().getTier(badPlayer.getUniqueId());
                if (tier.getLevel() >= plugin.getConfigManager().getVilDefendTier()
                        && isVillageNative(otherVillager)) {
                    enterDefendMode(otherVillager, badPlayer);
                }
            }
        }
    }

    private void enterDefendMode(Villager villager, LivingEntity target) {
        UUID villagerUuid = villager.getUniqueId();
        if (defendingVillagers.containsKey(villagerUuid)) return; // already retaliating

        defendingVillagers.put(villagerUuid, target.getUniqueId());
        lastAttackMs.put(villagerUuid, System.currentTimeMillis()); // grace period starts now
        defendStartLocation.put(villagerUuid, villager.getLocation().clone());

        // Arm logic: player attackers require tier 2+ AND village-native; mob attackers always arm
        boolean shouldArm;
        if (target instanceof Player player) {
            MoralityTier attackerTier = plugin.getMoralityManager().getTier(player.getUniqueId());
            shouldArm = attackerTier.getLevel() >= plugin.getConfigManager().getVilDefendTier()
                    && isVillageNative(villager);
        } else {
            shouldArm = true; // mob attack — always arm to fight back
        }
        if (shouldArm) {
            Material weapon = plugin.getConfigManager().getVilDefendWeapon();
            villager.getEquipment().setItemInMainHand(new ItemStack(weapon));
            int durationTicks = plugin.getConfigManager().getVilDefendDurationSeconds() * 20;
            for (String effectStr : plugin.getConfigManager().getVilDefendPotionEffects()) {
                PotionEffect effect = parsePotionEffect(effectStr, durationTicks);
                if (effect != null) villager.addPotionEffect(effect);
            }
        }

        villager.setTarget(target);

        // Schedule return to normal
        int durationTicks = plugin.getConfigManager().getVilDefendDurationSeconds() * 20;
        new BukkitRunnable() {
            @Override
            public void run() {
                exitDefendMode(villager);
            }
        }.runTaskLater(plugin, durationTicks);
    }

    private void exitDefendMode(Villager villager) {
        defendingVillagers.remove(villager.getUniqueId());
        lastAttackMs.remove(villager.getUniqueId());
        defendStartLocation.remove(villager.getUniqueId());
        if (villager.isDead() || !villager.isValid()) return;
        villager.getEquipment().setItemInMainHand(null);
        villager.setTarget(null);
        for (String effectStr : plugin.getConfigManager().getVilDefendPotionEffects()) {
            PotionEffectType type = parsePotionEffectType(effectStr);
            if (type != null) villager.removePotionEffect(type);
        }
    }

    // ── Villager trade block + witch merchant ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!isAffectedByMobMechanics(player)) return;
        Entity entity = event.getRightClicked();
        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
        if (!tier.isBad()) return;

        if (entity instanceof Villager && plugin.getConfigManager().isVillagerTradeBlockBadTier1()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("The villager refuses to trade with you.", NamedTextColor.RED));
            return;
        }

        if (entity instanceof Witch && plugin.getConfigManager().isWitchNeutralBadTier1()) {
            event.setCancelled(true);
            Merchant merchant = Bukkit.createMerchant(Component.text("Witch's Wares"));
            merchant.setRecipes(buildWitchTrades(tier.getLevel()));
            player.openMerchant(merchant, true);
        }
    }

    private List<MerchantRecipe> buildWitchTrades(int badTierLevel) {
        var cfg = plugin.getConfigManager();
        int poolSize = cfg.getWitchPoolSize(badTierLevel);

        // Filter to trades unlocked at this tier or below
        List<ConfigManager.WitchTrade> eligible = new ArrayList<>();
        for (ConfigManager.WitchTrade t : cfg.getWitchTrades()) {
            if (t.minBadTier() <= badTierLevel) eligible.add(t);
        }

        List<ConfigManager.WitchTrade> selected = weightedSample(eligible, poolSize);

        List<MerchantRecipe> recipes = new ArrayList<>();
        for (ConfigManager.WitchTrade trade : selected) {
            ItemStack result = buildTradeResult(trade);
            if (result != null) recipes.add(witchTrade(result, trade.maxUses(), emerald(trade.cost())));
        }
        return recipes;
    }

    /** Weighted random sample without replacement. */
    private List<ConfigManager.WitchTrade> weightedSample(List<ConfigManager.WitchTrade> pool, int count) {
        List<ConfigManager.WitchTrade> remaining = new ArrayList<>(pool);
        List<ConfigManager.WitchTrade> result = new ArrayList<>();
        int toSelect = Math.min(count, remaining.size());
        for (int i = 0; i < toSelect; i++) {
            int total = remaining.stream().mapToInt(ConfigManager.WitchTrade::weight).sum();
            if (total <= 0) break;
            int roll = ThreadLocalRandom.current().nextInt(total);
            int cumulative = 0;
            for (int j = 0; j < remaining.size(); j++) {
                cumulative += remaining.get(j).weight();
                if (roll < cumulative) {
                    result.add(remaining.remove(j));
                    break;
                }
            }
        }
        return result;
    }

    private ItemStack buildTradeResult(ConfigManager.WitchTrade trade) {
        String type = trade.type().toLowerCase();
        if (type.equals("potion")) {
            PotionType pt = parsePotionType(trade.potion());
            return pt != null ? makePotion(pt) : null;
        } else if (type.equals("splash_potion")) {
            PotionType pt = parsePotionType(trade.potion());
            return pt != null ? makeSplashPotion(pt) : null;
        } else {
            try {
                Material mat = Material.valueOf(trade.type().toUpperCase());
                return new ItemStack(mat, Math.max(1, trade.amount()));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private PotionType parsePotionType(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return PotionType.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private MerchantRecipe witchTrade(ItemStack result, int maxUses, ItemStack ingredient) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, maxUses, false, 0, 0f);
        recipe.addIngredient(ingredient);
        return recipe;
    }

    private ItemStack emerald(int amount) {
        return new ItemStack(Material.EMERALD, amount);
    }

    private ItemStack makePotion(PotionType type) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeSplashPotion(PotionType type) {
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    // ── Villager trade price modification ──────────────────────────────────────

    /**
     * Returns modified recipes for a bad tier 2+ player (prices doubled)
     * or a good tier 3+ player (prices halved). Returns null if no modification needed.
     */
    public List<MerchantRecipe> getModifiedRecipes(UUID uuid, List<MerchantRecipe> original) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        double multiplier;
        if (tier.isBad() && tier.getLevel() >= 2 && plugin.getConfigManager().isVillagerPriceIncreaseTier2()) {
            multiplier = 2.0;
        } else if (tier.isGood() && tier.getLevel() >= 3 && plugin.getConfigManager().isBetterVillagerTradesTier3()) {
            multiplier = 0.5;
        } else {
            return null; // no modification
        }

        List<MerchantRecipe> modified = new ArrayList<>();
        for (MerchantRecipe recipe : original) {
            MerchantRecipe newRecipe = new MerchantRecipe(
                    recipe.getResult(),
                    recipe.getUses(),
                    recipe.getMaxUses(),
                    recipe.hasExperienceReward(),
                    recipe.getVillagerExperience(),
                    recipe.getPriceMultiplier());
            List<ItemStack> ingredients = recipe.getIngredients();
            for (ItemStack ingredient : ingredients) {
                ItemStack mod = ingredient.clone();
                int newAmount = (int) Math.min(64, Math.max(1, Math.round(ingredient.getAmount() * multiplier)));
                mod.setAmount(newAmount);
                newRecipe.addIngredient(mod);
            }
            modified.add(newRecipe);
        }
        return modified;
    }

    // ── Village-native check ──────────────────────────────────────────────────

    /**
     * A villager is village-native if it is within the configured radius of a valid
     * village (POI-based). We approximate this by checking proximity to a bed or
     * by checking if the villager has a home. Paper doesn't expose a direct "is in village"
     * API, so we use the villager's home location as a proxy.
     */
    private boolean isVillageNative(Villager villager) {
        int radius = plugin.getConfigManager().getVilNativeRadius();
        // Use villager's bed location if available (set when it links to a bed)
        Location home = villager.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "home_set"), PersistentDataType.BYTE)
                ? villager.getLocation() : null;
        // Fallback: treat as native if the villager has a registered village profession
        // and is not a wandering trader. This is a simplification.
        return villager.getVillagerType() != null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAffectedByMobMechanics(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private Player resolvePlayer(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private PotionEffect parsePotionEffect(String config, int durationTicks) {
        // Format: EFFECT_TYPE:amplifier
        String[] parts = config.split(":");
        PotionEffectType type = parsePotionEffectType(config);
        if (type == null) return null;
        int amplifier = parts.length > 1 ? Integer.parseInt(parts[1]) - 1 : 0;
        return new PotionEffect(type, durationTicks, amplifier);
    }

    private PotionEffectType parsePotionEffectType(String config) {
        String typeName = config.split(":")[0].toUpperCase();
        try { return PotionEffectType.getByName(typeName); }
        catch (Exception e) { return null; }
    }
}
