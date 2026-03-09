package com.moralityengine;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigManager {

    /** One entry in the witch-neutral trade pool. */
    public record WitchTrade(
            String type,       // "potion", "splash_potion", or a Bukkit Material name
            String potion,     // PotionType name; only used when type is potion/splash_potion
            int amount,        // result stack size (ignored for potions, which are always 1)
            int cost,          // emerald cost
            int maxUses,       // uses before the trade locks
            int minBadTier,    // lowest bad tier that can see this trade (1–4)
            int weight         // relative selection weight; higher = more common
    ) {}

    private final MoralityEngine plugin;

    private boolean debug;

    // Morality thresholds
    private double neutralRange;
    private boolean capEnabled;
    private double capMaxGood;
    private double capMaxBad;
    private boolean onDeathEnabled;
    private String onDeathMode;
    private double onDeathPercentage;
    private double onDeathFlat;
    private double[] badThresholds = new double[4];  // index 0 = tier1, ...
    private double[] goodThresholds = new double[4];

    // Bad progression
    private double badKillNeutralGoodPoints;
    private String badKillNeutralGoodMode;
    private double badKillNeutralGoodPercentage;
    private boolean badKillNeutralGoodEnabled;
    private double badKillGolemBossPoints;
    private String badKillGolemBossMode;
    private double badKillGolemBossPercentage;
    private boolean badKillGolemBossEnabled;
    private double badKillIronGolemPoints;
    private String badKillIronGolemMode;
    private double badKillIronGolemPercentage;
    private boolean badKillIronGolemEnabled;
    private double badKillVillagerPoints;
    private String badKillVillagerMode;
    private double badKillVillagerPercentage;
    private boolean badKillVillagerEnabled;
    private double badKillVillagerConsumePoints;
    private String badKillVillagerConsumeMode;
    private double badKillVillagerConsumePercentage;
    private boolean badKillVillagerConsumeEnabled;
    private double badKillVillagerConsumeFleshDropRate;
    private int badKillVillagerConsumeFleshDropCount;
    private double badEatRottenFleshPoints;
    private String badEatRottenFleshMode;
    private double badEatRottenFleshPercentage;
    private boolean badEatRottenFleshEnabled;
    private double badKillPassiveMobPoints;
    private String badKillPassiveMobMode;
    private double badKillPassiveMobPercentage;
    private boolean badKillPassiveMobEnabled;
    private Set<EntityType> badKillPassiveMobs;
    private double badPopTotemPoints;
    private String badPopTotemMode;
    private double badPopTotemPercentage;
    private boolean badPopTotemEnabled;

    // Good progression
    private double goodKillBadPlayerPoints;
    private String goodKillBadPlayerMode;
    private double goodKillBadPlayerPercentage;
    private boolean goodKillBadPlayerEnabled;
    private double goodKillUndeadPoints;
    private String goodKillUndeadMode;
    private double goodKillUndeadPercentage;
    private boolean goodKillUndeadEnabled;
    private double goodNearGoodPoints;
    private String goodNearGoodMode;
    private double goodNearGoodPercentage;
    private int goodNearGoodIntervalSeconds;
    private int goodNearGoodRangeBlocks;
    private boolean goodNearGoodEnabled;
    private double goodHeroOfVillagePoints;
    private String goodHeroOfVillageMode;
    private double goodHeroOfVillagePercentage;
    private boolean goodHeroOfVillageEnabled;

    // Perks
    private boolean noRottenFleshPenaltyTier1;
    private double bonusKillXpTier1;
    private double bonusKillXpTier2;
    private double bonusKillXpTier3;
    private double bonusKillXpTier4;
    private Set<EntityType> passiveMobFleeAggroMobs;
    private boolean passiveMobFleeAggroTier1;
    private double passiveMobFleeSpeed;
    private double passiveMobFleeDistance;
    private boolean ironGolemAggroTier1;
    private boolean pillagerNeutralTier1;
    private boolean pillagerGrantsGoodOnKill;
    private boolean pillagerSuppressVanillaXp;
    private boolean pillagerSuppressCorelifeXp;
    private boolean undeadNeutralTier1;
    private boolean undeadGrantsGoodOnKill;
    private boolean undeadSuppressVanillaXp;
    private boolean undeadSuppressCorelifeXp;
    private boolean zombieReinforcementsEnabled;
    private int[] zombieReinforcementsMaxCount = new int[4]; // index 0 = tier1, ...
    private boolean zombieShearTier1;
    private int zombieShearFleshPerShear;
    private int zombieShearMaxFlesh;
    private boolean zombieHorseConversionTier2;
    private boolean villagerPriceIncreaseTier2;
    private boolean villagerTradeBlockBadTier1;
    private boolean witchNeutralBadTier1;
    private Map<Integer, Integer> witchPoolSizes; // bad tier → pool-size
    private List<WitchTrade> witchTrades;
    private int bonusHusbandrySpawnsTier1;
    private int bonusHusbandrySpawnsTier2;
    private double bonusHusbandryXpTier2;
    private double bonusHusbandryXpTier3;
    private boolean betterVillagerTradesTier3;

    // Wraith follower
    private boolean wraithEnabled;
    private int wraithMinBadTier;
    private double wraithAttackRangeBlocks;
    private double wraithTeleportRangeBlocks;
    private int wraithMinSleepDeprivationTicks;

    // Villager reactions
    private int vilFleeTrigerTier;
    private int vilFleeRadius;
    private double vilFleeSpeed;
    private double vilFleeDistance;
    private int vilCallForHelpTier;
    private int vilCallForHelpRadius;
    private int vilDefendTier;
    private Material vilDefendWeapon;
    private List<String> vilDefendPotionEffects;
    private int vilDefendDurationSeconds;
    private int vilDefendChaseTimeoutSeconds;
    private int vilDefendMaxChaseDist;
    private int vilNativeRadius;

    // Totem of dying
    private int totemDyingCustomModelData;
    private double totemDyingDropRate;
    private int totemDyingTransformMinutes;
    private double totemDyingRestoreHealth;
    private String totemDyingCombineMethod;
    private String totemDyingCombinedName;
    private String totemDyingCombinedLore;
    private int totemDyingAnvilCost;

    // Totem of undying
    private double totemUndyingDropRate;
    private int totemUndyingDropCount;
    private boolean totemUndyingDisableVanilla;
    private boolean totemUndyingBadUnusable;
    private boolean totemUndyingNoDropOnBadKill;

    // Village golem enhancement
    private boolean villageGolemEnabled;
    private double villageGolemWindSurgePower;
    private int villageGolemWindSurgeCooldownSeconds;
    private double villageGolemWindSurgePathThreshold;

    // Golem boss
    private boolean golemBossEnabled;
    private Material golemSpawnBlock;
    private double golemHealth;
    private double golemAttackDamage;
    private double golemMovementSpeed;
    private List<String> golemEffects;
    private double golemTotemDropRate;
    private int golemRespawnCooldownMinutes;
    private int golemAggroRangeBlocks;
    private double villagerHeadDropRate;
    private String villagerHeadTextureHash;

    // Compass
    private int compassBasicTierMax;
    private int compassAdvancedTierMin;
    private int compassMaxUses;
    private int compassBasicUsesPerClick;
    private int compassAdvancedUsesPerUpdate;
    private int compassAdvancedUpdateTicks;
    private String compassRow1, compassRow2, compassRow3;
    private String compassIngredientG, compassIngredientC;

    // Locator bar
    private int locatorUpdateTicks;
    private int locatorRangeVeryClose;
    private int locatorRangeNearby;
    private int locatorRangeFar;
    private String locatorLabelVeryClose;
    private String locatorLabelNearby;
    private String locatorLabelFar;

    // Frost
    private List<String> frostEnabledPhases;
    private double frostStartThreshold;
    private double frostMaxThreshold;
    private int frostUpdateTicks;

    // HUD
    private boolean hudActionBarEnabled;
    private int hudActionBarUpdateTicks;
    private String hudActionBarFormat;
    private Map<String, String> actionBarTierColors;
    private boolean actionBarPermanent;
    private boolean hudMirrorEnabled;
    private int mirrorDisplaySeconds;
    private String mirrorRow1, mirrorRow2, mirrorRow3;
    private String mirrorIngredientG, mirrorIngredientP;

    // Commands
    private List<String> moralityAliases;

    public ConfigManager(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        var c = plugin.getConfig();

        debug = c.getBoolean("debug", false);

        neutralRange = c.getDouble("morality.neutral-range", 100.0);
        capEnabled = c.getBoolean("morality.cap.enabled", false);
        capMaxGood = c.getDouble("morality.cap.max-good", 2000.0);
        capMaxBad = c.getDouble("morality.cap.max-bad", -2000.0);
        onDeathEnabled = c.getBoolean("morality.on-death.enabled", false);
        onDeathMode = c.getString("morality.on-death.mode", "percentage").toLowerCase();
        onDeathPercentage = c.getDouble("morality.on-death.percentage", 50.0);
        onDeathFlat = c.getDouble("morality.on-death.flat", 100.0);
        badThresholds[0] = c.getDouble("morality.bad.tier1-threshold", -100.0);
        badThresholds[1] = c.getDouble("morality.bad.tier2-threshold", -250.0);
        badThresholds[2] = c.getDouble("morality.bad.tier3-threshold", -500.0);
        badThresholds[3] = c.getDouble("morality.bad.tier4-threshold", -1000.0);
        goodThresholds[0] = c.getDouble("morality.good.tier1-threshold", 100.0);
        goodThresholds[1] = c.getDouble("morality.good.tier2-threshold", 250.0);
        goodThresholds[2] = c.getDouble("morality.good.tier3-threshold", 500.0);
        goodThresholds[3] = c.getDouble("morality.good.tier4-threshold", 1000.0);

        badKillNeutralGoodPoints = c.getDouble("bad-progress.kill-neutral-good-player.points", 80.0);
        badKillNeutralGoodMode = c.getString("bad-progress.kill-neutral-good-player.mode", "flat").toLowerCase();
        badKillNeutralGoodPercentage = c.getDouble("bad-progress.kill-neutral-good-player.percentage", 10.0);
        badKillNeutralGoodEnabled = c.getBoolean("bad-progress.kill-neutral-good-player.enabled", true);
        badKillGolemBossPoints = c.getDouble("bad-progress.kill-golem-boss.points", 40.0);
        badKillGolemBossMode = c.getString("bad-progress.kill-golem-boss.mode", "flat").toLowerCase();
        badKillGolemBossPercentage = c.getDouble("bad-progress.kill-golem-boss.percentage", 5.0);
        badKillGolemBossEnabled = c.getBoolean("bad-progress.kill-golem-boss.enabled", true);
        badKillIronGolemPoints = c.getDouble("bad-progress.kill-iron-golem.points", 20.0);
        badKillIronGolemMode = c.getString("bad-progress.kill-iron-golem.mode", "flat").toLowerCase();
        badKillIronGolemPercentage = c.getDouble("bad-progress.kill-iron-golem.percentage", 5.0);
        badKillIronGolemEnabled = c.getBoolean("bad-progress.kill-iron-golem.enabled", false);
        badKillVillagerPoints = c.getDouble("bad-progress.kill-villager.points", 10.0);
        badKillVillagerMode = c.getString("bad-progress.kill-villager.mode", "flat").toLowerCase();
        badKillVillagerPercentage = c.getDouble("bad-progress.kill-villager.percentage", 10.0);
        badKillVillagerEnabled = c.getBoolean("bad-progress.kill-villager.enabled", true);
        badKillVillagerConsumePoints = c.getDouble("bad-progress.kill-villager-consume.points", 15.0);
        badKillVillagerConsumeMode = c.getString("bad-progress.kill-villager-consume.mode", "flat").toLowerCase();
        badKillVillagerConsumePercentage = c.getDouble("bad-progress.kill-villager-consume.percentage", 5.0);
        badKillVillagerConsumeEnabled = c.getBoolean("bad-progress.kill-villager-consume.enabled", true);
        badKillVillagerConsumeFleshDropRate = c.getDouble("bad-progress.kill-villager-consume.flesh-drop-rate", 1.0);
        badKillVillagerConsumeFleshDropCount = c.getInt("bad-progress.kill-villager-consume.flesh-drop-count", 1);
        badEatRottenFleshPoints = c.getDouble("bad-progress.eat-rotten-flesh.points", 5.0);
        badEatRottenFleshMode = c.getString("bad-progress.eat-rotten-flesh.mode", "flat").toLowerCase();
        badEatRottenFleshPercentage = c.getDouble("bad-progress.eat-rotten-flesh.percentage", 2.0);
        badEatRottenFleshEnabled = c.getBoolean("bad-progress.eat-rotten-flesh.enabled", false);
        badKillPassiveMobPoints = c.getDouble("bad-progress.kill-passive-mob.points", 10.0);
        badKillPassiveMobMode = c.getString("bad-progress.kill-passive-mob.mode", "flat").toLowerCase();
        badKillPassiveMobPercentage = c.getDouble("bad-progress.kill-passive-mob.percentage", 5.0);
        badKillPassiveMobEnabled = c.getBoolean("bad-progress.kill-passive-mob.enabled", false);
        badKillPassiveMobs = new java.util.HashSet<>();
        for (String mob : c.getStringList("bad-progress.kill-passive-mob.mobs")) {
            try { badKillPassiveMobs.add(EntityType.valueOf(mob.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        badPopTotemPoints = c.getDouble("bad-progress.pop-totem-of-dying.points", 40.0);
        badPopTotemMode = c.getString("bad-progress.pop-totem-of-dying.mode", "flat").toLowerCase();
        badPopTotemPercentage = c.getDouble("bad-progress.pop-totem-of-dying.percentage", 5.0);
        badPopTotemEnabled = c.getBoolean("bad-progress.pop-totem-of-dying.enabled", true);

        goodKillBadPlayerPoints = c.getDouble("good-progress.kill-bad-player.points", 80.0);
        goodKillBadPlayerMode = c.getString("good-progress.kill-bad-player.mode", "flat").toLowerCase();
        goodKillBadPlayerPercentage = c.getDouble("good-progress.kill-bad-player.percentage", 10.0);
        goodKillBadPlayerEnabled = c.getBoolean("good-progress.kill-bad-player.enabled", true);
        goodKillUndeadPoints = c.getDouble("good-progress.kill-undead.points", 15.0);
        goodKillUndeadMode = c.getString("good-progress.kill-undead.mode", "flat").toLowerCase();
        goodKillUndeadPercentage = c.getDouble("good-progress.kill-undead.percentage", 3.0);
        goodKillUndeadEnabled = c.getBoolean("good-progress.kill-undead.enabled", true);
        goodNearGoodPoints = c.getDouble("good-progress.near-good-players.points", 5.0);
        goodNearGoodMode = c.getString("good-progress.near-good-players.mode", "flat").toLowerCase();
        goodNearGoodPercentage = c.getDouble("good-progress.near-good-players.percentage", 1.0);
        goodNearGoodIntervalSeconds = c.getInt("good-progress.near-good-players.interval-seconds", 60);
        goodNearGoodRangeBlocks = c.getInt("good-progress.near-good-players.range-blocks", 30);
        goodNearGoodEnabled = c.getBoolean("good-progress.near-good-players.enabled", true);
        goodHeroOfVillagePoints = c.getDouble("good-progress.hero-of-village.points", 80.0);
        goodHeroOfVillageMode = c.getString("good-progress.hero-of-village.mode", "flat").toLowerCase();
        goodHeroOfVillagePercentage = c.getDouble("good-progress.hero-of-village.percentage", 10.0);
        goodHeroOfVillageEnabled = c.getBoolean("good-progress.hero-of-village.enabled", true);

        noRottenFleshPenaltyTier1 = c.getBoolean("bad-perks.no-rotten-flesh-penalty.tiers.1.enabled", true);
        bonusKillXpTier1 = c.getDouble("bad-perks.bonus-player-kill-xp.tiers.1.multiplier", 1.5);
        bonusKillXpTier2 = c.getDouble("bad-perks.bonus-player-kill-xp.tiers.2.multiplier", 2.0);
        bonusKillXpTier3 = c.getDouble("bad-perks.bonus-player-kill-xp.tiers.3.multiplier", 2.5);
        bonusKillXpTier4 = c.getDouble("bad-perks.bonus-player-kill-xp.tiers.4.multiplier", 3.0);
        passiveMobFleeAggroTier1 = c.getBoolean("bad-perks.passive-mob-flee-aggro.tiers.1.enabled", true);
        passiveMobFleeSpeed = c.getDouble("bad-perks.passive-mob-flee-aggro.flee-speed", 1.2);
        passiveMobFleeDistance = c.getDouble("bad-perks.passive-mob-flee-aggro.flee-distance-blocks", 8.0);
        passiveMobFleeAggroMobs = new java.util.HashSet<>();
        for (String mob : c.getStringList("bad-perks.passive-mob-flee-aggro.mobs")) {
            try { passiveMobFleeAggroMobs.add(EntityType.valueOf(mob.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        ironGolemAggroTier1 = c.getBoolean("bad-perks.iron-golem-aggro.tiers.1.enabled", true);
        pillagerNeutralTier1 = c.getBoolean("bad-perks.pillager-neutral.tiers.1.enabled", true);
        pillagerGrantsGoodOnKill = c.getBoolean("bad-perks.pillager-neutral.grants-good-morality-on-kill", false);
        pillagerSuppressVanillaXp = c.getBoolean("bad-perks.pillager-neutral.suppress-vanilla-xp", true);
        pillagerSuppressCorelifeXp = c.getBoolean("bad-perks.pillager-neutral.suppress-corelife-xp", true);
        undeadNeutralTier1 = c.getBoolean("bad-perks.undead-neutral.tiers.1.enabled", true);
        undeadGrantsGoodOnKill = c.getBoolean("bad-perks.undead-neutral.grants-good-morality-on-kill", false);
        undeadSuppressVanillaXp = c.getBoolean("bad-perks.undead-neutral.suppress-vanilla-xp", true);
        undeadSuppressCorelifeXp = c.getBoolean("bad-perks.undead-neutral.suppress-corelife-xp", true);
        zombieReinforcementsEnabled = c.getBoolean("bad-perks.zombie-reinforcements.enabled", true);
        int[] reinforcementDefaults = {0, 2, 4, -1}; // tier 1–4 defaults
        for (int t = 1; t <= 4; t++) {
            zombieReinforcementsMaxCount[t - 1] = c.getInt(
                    "bad-perks.zombie-reinforcements.tiers." + t + ".max-count",
                    reinforcementDefaults[t - 1]);
        }
        zombieShearTier1 = c.getBoolean("bad-perks.zombie-shear.tiers.1.enabled", true);
        zombieShearFleshPerShear = c.getInt("bad-perks.zombie-shear.flesh-per-shear", 1);
        zombieShearMaxFlesh = c.getInt("bad-perks.zombie-shear.max-flesh-per-zombie", 3);
        zombieHorseConversionTier2 = c.getBoolean("bad-perks.zombie-horse-conversion.tiers.2.enabled", true);
        villagerPriceIncreaseTier2 = c.getBoolean("bad-perks.villager-price-increase.tiers.2.enabled", true);
        villagerTradeBlockBadTier1 = c.getBoolean("bad-perks.villager-trade-blocked.tiers.1.enabled", true);
        witchNeutralBadTier1 = c.getBoolean("bad-perks.witch-neutral.tiers.1.enabled", true);

        witchPoolSizes = new HashMap<>();
        for (int t = 1; t <= 4; t++) {
            int defaultSize = 6 + (t - 1); // 6, 7, 8, 8
            if (t == 4) defaultSize = 8;
            int size = c.getInt("bad-perks.witch-neutral.tiers." + t + ".pool-size", defaultSize);
            witchPoolSizes.put(t, size);
        }
        witchTrades = new ArrayList<>();
        var tradesList = c.getMapList("bad-perks.witch-neutral.trades");
        for (var rawEntry : tradesList) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) rawEntry;
                String type      = String.valueOf(raw.getOrDefault("type", "GUNPOWDER"));
                String potion    = String.valueOf(raw.getOrDefault("potion", ""));
                int amount       = ((Number) raw.getOrDefault("amount",       1)).intValue();
                int cost         = ((Number) raw.getOrDefault("cost",         2)).intValue();
                int maxUses      = ((Number) raw.getOrDefault("max-uses",    12)).intValue();
                int minBadTier   = ((Number) raw.getOrDefault("min-bad-tier", 1)).intValue();
                int weight       = ((Number) raw.getOrDefault("weight",      10)).intValue();
                witchTrades.add(new WitchTrade(type, potion, amount, cost, maxUses, minBadTier, weight));
            } catch (Exception ignored) {}
        }

        bonusHusbandrySpawnsTier1 = c.getInt("good-perks.bonus-husbandry-spawns.tiers.1.bonus-count", 1);
        bonusHusbandrySpawnsTier2 = c.getInt("good-perks.bonus-husbandry-spawns.tiers.2.bonus-count", 2);
        bonusHusbandryXpTier2 = c.getDouble("good-perks.bonus-husbandry-xp.tiers.2.multiplier", 1.5);
        bonusHusbandryXpTier3 = c.getDouble("good-perks.bonus-husbandry-xp.tiers.3.multiplier", 2.0);
        betterVillagerTradesTier3 = c.getBoolean("good-perks.better-villager-trades.tiers.3.enabled", true);

        wraithEnabled = c.getBoolean("bad-perks.wraith-follower.enabled", true);
        wraithMinBadTier = c.getInt("bad-perks.wraith-follower.min-bad-tier", 1);
        wraithAttackRangeBlocks = c.getDouble("bad-perks.wraith-follower.attack-range-blocks", 16.0);
        wraithTeleportRangeBlocks = c.getDouble("bad-perks.wraith-follower.teleport-range-blocks", 48.0);
        wraithMinSleepDeprivationTicks = c.getInt("bad-perks.wraith-follower.min-sleep-deprivation-ticks", 72000);

        vilFleeTrigerTier = c.getInt("villager-reactions.flee-trigger-tier", 1);
        vilFleeRadius = c.getInt("villager-reactions.flee-radius-blocks", 16);
        vilFleeSpeed = c.getDouble("villager-reactions.flee-speed", 1.2);
        vilFleeDistance = c.getDouble("villager-reactions.flee-distance-blocks", 8.0);
        vilCallForHelpTier = c.getInt("villager-reactions.call-for-help-trigger-tier", 1);
        vilCallForHelpRadius = c.getInt("villager-reactions.call-for-help-radius-blocks", 32);
        vilDefendTier = c.getInt("villager-reactions.defend-trigger-tier", 2);
        vilDefendWeapon = parseMaterial(c.getString("villager-reactions.defend-weapon", "IRON_SWORD"));
        vilDefendPotionEffects = c.getStringList("villager-reactions.defend-potion-effects");
        if (vilDefendPotionEffects.isEmpty()) vilDefendPotionEffects = List.of("STRENGTH:1", "REGENERATION:0");
        vilDefendDurationSeconds = c.getInt("villager-reactions.defend-duration-seconds", 30);
        vilDefendChaseTimeoutSeconds = c.getInt("villager-reactions.defend-chase-timeout-seconds", 15);
        vilDefendMaxChaseDist = c.getInt("villager-reactions.defend-max-chase-distance", 24);
        vilNativeRadius = c.getInt("villager-reactions.village-native-radius-blocks", 64);

        totemDyingCustomModelData = c.getInt("totem-of-dying.custom-model-data", 0);
        totemDyingDropRate = c.getDouble("totem-of-dying.drop-rate", 0.25);
        totemDyingTransformMinutes = c.getInt("totem-of-dying.transformation-duration-minutes", 40);
        totemDyingRestoreHealth = c.getDouble("totem-of-dying.restore-health", 4.0);
        totemDyingCombineMethod = c.getString("totem-of-dying.combine-method", "anvil");
        totemDyingCombinedName = c.getString("totem-of-dying.combined-item-name", "Cursed Armour");
        totemDyingCombinedLore = c.getString("totem-of-dying.combined-item-lore", "Death is not the end.");
        totemDyingAnvilCost = c.getInt("totem-of-dying.anvil-repair-cost", 5);

        totemUndyingDropRate = c.getDouble("totem-of-undying.drop-rate", 1.0);
        totemUndyingDropCount = c.getInt("totem-of-undying.drop-count", 1);
        totemUndyingDisableVanilla = c.getBoolean("totem-of-undying.disable-vanilla-drop", true);
        totemUndyingBadUnusable = c.getBoolean("totem-of-undying.bad-morality-unusable", true);
        totemUndyingNoDropOnBadKill = c.getBoolean("totem-of-undying.no-drop-on-bad-morality-kill", true);

        villageGolemEnabled = c.getBoolean("village-golem.enabled", true);
        villageGolemWindSurgePower = c.getDouble("village-golem.wind-surge-power", 2.5);
        villageGolemWindSurgeCooldownSeconds = c.getInt("village-golem.wind-surge-cooldown-seconds", 8);
        villageGolemWindSurgePathThreshold = c.getDouble("village-golem.wind-surge-path-threshold", 3.0);

        golemBossEnabled = c.getBoolean("golem-boss.enabled", true);
        golemSpawnBlock = parseMaterial(c.getString("golem-boss.spawn-block", "OBSIDIAN"));
        golemHealth = c.getDouble("golem-boss.health", 200.0);
        golemAttackDamage = c.getDouble("golem-boss.attack-damage", 15.0);
        golemMovementSpeed = c.getDouble("golem-boss.movement-speed", 0.35);
        golemEffects = c.getStringList("golem-boss.effects");
        golemTotemDropRate = c.getDouble("golem-boss.totem-drop-rate", 1.0);
        golemRespawnCooldownMinutes = c.getInt("golem-boss.respawn-cooldown-minutes", 60);
        golemAggroRangeBlocks = c.getInt("golem-boss.aggro-range-blocks", 48);
        villagerHeadDropRate = c.getDouble("villager.head-drop-rate", 0.05);
        villagerHeadTextureHash = c.getString("villager.head-texture-hash", "");

        compassBasicTierMax = c.getInt("compass.basic-tier-max", 2);
        compassAdvancedTierMin = c.getInt("compass.advanced-tier-min", 3);
        compassMaxUses = c.getInt("compass.max-uses", 100);
        compassBasicUsesPerClick = c.getInt("compass.basic-uses-per-click", 10);
        compassAdvancedUsesPerUpdate = c.getInt("compass.advanced-uses-per-update", 1);
        compassAdvancedUpdateTicks = c.getInt("compass.advanced-update-ticks", 20);
        compassRow1 = c.getString("compass.recipe.row1", "_G_");
        compassRow2 = c.getString("compass.recipe.row2", "GCG");
        compassRow3 = c.getString("compass.recipe.row3", "_G_");
        compassIngredientG = c.getString("compass.recipe.ingredients.G", "GOLD_INGOT");
        compassIngredientC = c.getString("compass.recipe.ingredients.C", "COMPASS");

        locatorUpdateTicks = c.getInt("locator-bar.update-ticks", 20);
        locatorRangeVeryClose = c.getInt("locator-bar.range-very-close-blocks", 20);
        locatorRangeNearby = c.getInt("locator-bar.range-nearby-blocks", 80);
        locatorRangeFar = c.getInt("locator-bar.range-far-blocks", 200);
        locatorLabelVeryClose = c.getString("locator-bar.label-very-close", "Very Close");
        locatorLabelNearby = c.getString("locator-bar.label-nearby", "Nearby");
        locatorLabelFar = c.getString("locator-bar.label-far", "Far");

        frostEnabledPhases = c.getStringList("frost.enabled-phases");
        frostStartThreshold = c.getDouble("frost.start-threshold", 0.5);
        frostMaxThreshold = c.getDouble("frost.max-threshold", 1.0);
        frostUpdateTicks = c.getInt("frost.update-ticks", 100);

        hudActionBarEnabled = c.getBoolean("morality-hud.action-bar.enabled", true);
        hudActionBarUpdateTicks = c.getInt("morality-hud.action-bar.update-ticks", 20);
        hudActionBarFormat = c.getString("morality-hud.action-bar.format", "Morality: {tier} ({score})");
        actionBarTierColors = new java.util.HashMap<>();
        var colorsSection = c.getConfigurationSection("morality-hud.action-bar.tier-colors");
        if (colorsSection != null) {
            for (String key : colorsSection.getKeys(false)) {
                actionBarTierColors.put(key.toUpperCase(), colorsSection.getString(key, "WHITE").toUpperCase());
            }
        }
        actionBarPermanent = c.getBoolean("morality-hud.action-bar.permanent", true);
        hudMirrorEnabled = c.getBoolean("morality-hud.morality-mirror.enabled", true);
        mirrorDisplaySeconds = c.getInt("morality-hud.morality-mirror.display-seconds", 5);
        mirrorRow1 = c.getString("morality-hud.morality-mirror.recipe.row1", "_G_");
        mirrorRow2 = c.getString("morality-hud.morality-mirror.recipe.row2", "GPG");
        mirrorRow3 = c.getString("morality-hud.morality-mirror.recipe.row3", "_G_");
        mirrorIngredientG = c.getString("morality-hud.morality-mirror.recipe.ingredients.G", "GOLD_INGOT");
        mirrorIngredientP = c.getString("morality-hud.morality-mirror.recipe.ingredients.P", "GLASS_PANE");

        moralityAliases = c.getStringList("commands.morality-aliases");
        if (moralityAliases.isEmpty()) moralityAliases = List.of("morality");
    }

    private Material parseMaterial(String name) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return Material.AIR; }
    }

    /**
     * Resolves the unsigned point magnitude for a progression source based on its mode.
     * <p>
     * If {@code mode} is {@code "percentage"}, returns {@code abs(currentScore) * percentage / 100}.
     * Otherwise (flat), returns {@code flatPoints}.
     * The caller is responsible for applying the correct sign when passing the delta to addScore.
     */
    public static double resolveDelta(String mode, double flatPoints, double percentage, double currentScore) {
        if ("percentage".equals(mode)) {
            return Math.abs(currentScore) * percentage / 100.0;
        }
        return flatPoints;
    }

    // Getters
    public boolean isDebug() { return debug; }
    public double getNeutralRange() { return neutralRange; }
    public boolean isCapEnabled() { return capEnabled; }
    public double getCapMaxGood() { return capMaxGood; }
    public double getCapMaxBad() { return capMaxBad; }
    public boolean isOnDeathEnabled() { return onDeathEnabled; }
    public String getOnDeathMode() { return onDeathMode; }
    public double getOnDeathPercentage() { return onDeathPercentage; }
    public double getOnDeathFlat() { return onDeathFlat; }
    public double getBadThreshold(int tier) { return badThresholds[Math.min(tier - 1, 3)]; }
    public double getGoodThreshold(int tier) { return goodThresholds[Math.min(tier - 1, 3)]; }
    public double getBadKillNeutralGoodPoints() { return badKillNeutralGoodPoints; }
    public String getBadKillNeutralGoodMode() { return badKillNeutralGoodMode; }
    public double getBadKillNeutralGoodPercentage() { return badKillNeutralGoodPercentage; }
    public boolean isBadKillNeutralGoodEnabled() { return badKillNeutralGoodEnabled; }
    public double getBadKillGolemBossPoints() { return badKillGolemBossPoints; }
    public String getBadKillGolemBossMode() { return badKillGolemBossMode; }
    public double getBadKillGolemBossPercentage() { return badKillGolemBossPercentage; }
    public boolean isBadKillGolemBossEnabled() { return badKillGolemBossEnabled; }
    public double getBadKillIronGolemPoints() { return badKillIronGolemPoints; }
    public String getBadKillIronGolemMode() { return badKillIronGolemMode; }
    public double getBadKillIronGolemPercentage() { return badKillIronGolemPercentage; }
    public boolean isBadKillIronGolemEnabled() { return badKillIronGolemEnabled; }
    public double getBadKillVillagerPoints() { return badKillVillagerPoints; }
    public String getBadKillVillagerMode() { return badKillVillagerMode; }
    public double getBadKillVillagerPercentage() { return badKillVillagerPercentage; }
    public boolean isBadKillVillagerEnabled() { return badKillVillagerEnabled; }
    public double getBadKillVillagerConsumePoints() { return badKillVillagerConsumePoints; }
    public String getBadKillVillagerConsumeMode() { return badKillVillagerConsumeMode; }
    public double getBadKillVillagerConsumePercentage() { return badKillVillagerConsumePercentage; }
    public boolean isBadKillVillagerConsumeEnabled() { return badKillVillagerConsumeEnabled; }
    public double getBadKillVillagerConsumeFleshDropRate() { return badKillVillagerConsumeFleshDropRate; }
    public int getBadKillVillagerConsumeFleshDropCount() { return badKillVillagerConsumeFleshDropCount; }
    public double getBadEatRottenFleshPoints() { return badEatRottenFleshPoints; }
    public String getBadEatRottenFleshMode() { return badEatRottenFleshMode; }
    public double getBadEatRottenFleshPercentage() { return badEatRottenFleshPercentage; }
    public boolean isBadEatRottenFleshEnabled() { return badEatRottenFleshEnabled; }
    public double getBadKillPassiveMobPoints() { return badKillPassiveMobPoints; }
    public String getBadKillPassiveMobMode() { return badKillPassiveMobMode; }
    public double getBadKillPassiveMobPercentage() { return badKillPassiveMobPercentage; }
    public boolean isBadKillPassiveMobEnabled() { return badKillPassiveMobEnabled; }
    public Set<EntityType> getBadKillPassiveMobs() { return badKillPassiveMobs; }
    public double getBadPopTotemPoints() { return badPopTotemPoints; }
    public String getBadPopTotemMode() { return badPopTotemMode; }
    public double getBadPopTotemPercentage() { return badPopTotemPercentage; }
    public boolean isBadPopTotemEnabled() { return badPopTotemEnabled; }
    public double getGoodKillBadPlayerPoints() { return goodKillBadPlayerPoints; }
    public String getGoodKillBadPlayerMode() { return goodKillBadPlayerMode; }
    public double getGoodKillBadPlayerPercentage() { return goodKillBadPlayerPercentage; }
    public boolean isGoodKillBadPlayerEnabled() { return goodKillBadPlayerEnabled; }
    public double getGoodKillUndeadPoints() { return goodKillUndeadPoints; }
    public String getGoodKillUndeadMode() { return goodKillUndeadMode; }
    public double getGoodKillUndeadPercentage() { return goodKillUndeadPercentage; }
    public boolean isGoodKillUndeadEnabled() { return goodKillUndeadEnabled; }
    public double getGoodNearGoodPoints() { return goodNearGoodPoints; }
    public String getGoodNearGoodMode() { return goodNearGoodMode; }
    public double getGoodNearGoodPercentage() { return goodNearGoodPercentage; }
    public int getGoodNearGoodIntervalSeconds() { return goodNearGoodIntervalSeconds; }
    public int getGoodNearGoodRangeBlocks() { return goodNearGoodRangeBlocks; }
    public boolean isGoodNearGoodEnabled() { return goodNearGoodEnabled; }
    public double getGoodHeroOfVillagePoints() { return goodHeroOfVillagePoints; }
    public String getGoodHeroOfVillageMode() { return goodHeroOfVillageMode; }
    public double getGoodHeroOfVillagePercentage() { return goodHeroOfVillagePercentage; }
    public boolean isGoodHeroOfVillageEnabled() { return goodHeroOfVillageEnabled; }
    public boolean isNoRottenFleshPenaltyTier1() { return noRottenFleshPenaltyTier1; }
    public double getBonusKillXpTier1() { return bonusKillXpTier1; }
    public double getBonusKillXpTier2() { return bonusKillXpTier2; }
    public double getBonusKillXpTier3() { return bonusKillXpTier3; }
    public double getBonusKillXpTier4() { return bonusKillXpTier4; }
    public Set<EntityType> getPassiveMobFleeAggroMobs() { return passiveMobFleeAggroMobs; }
    public boolean isPassiveMobFleeAggroTier1() { return passiveMobFleeAggroTier1; }
    public double getPassiveMobFleeSpeed() { return passiveMobFleeSpeed; }
    public double getPassiveMobFleeDistance() { return passiveMobFleeDistance; }
    public boolean isIronGolemAggroTier1() { return ironGolemAggroTier1; }
    public boolean isPillagerNeutralTier1() { return pillagerNeutralTier1; }
    public boolean isPillagerGrantsGoodOnKill() { return pillagerGrantsGoodOnKill; }
    public boolean isPillagerSuppressVanillaXp() { return pillagerSuppressVanillaXp; }
    public boolean isPillagerSuppressCorelifeXp() { return pillagerSuppressCorelifeXp; }
    public boolean isUndeadNeutralTier1() { return undeadNeutralTier1; }
    public boolean isUndeadGrantsGoodOnKill() { return undeadGrantsGoodOnKill; }
    public boolean isUndeadSuppressVanillaXp() { return undeadSuppressVanillaXp; }
    public boolean isUndeadSuppressCorelifeXp() { return undeadSuppressCorelifeXp; }
    public boolean isZombieReinforcementsEnabled() { return zombieReinforcementsEnabled; }
    /** Max reinforcements per bad player in the rolling 30-second window. -1 = unlimited, 0 = none. */
    public int getZombieReinforcementsMaxCount(int tier) { return zombieReinforcementsMaxCount[Math.min(tier - 1, 3)]; }
    public boolean isZombieShearTier1() { return zombieShearTier1; }
    public int getZombieShearFleshPerShear() { return zombieShearFleshPerShear; }
    public int getZombieShearMaxFlesh() { return zombieShearMaxFlesh; }
    public boolean isZombieHorseConversionTier2() { return zombieHorseConversionTier2; }
    public boolean isVillagerPriceIncreaseTier2() { return villagerPriceIncreaseTier2; }
    public boolean isVillagerTradeBlockBadTier1() { return villagerTradeBlockBadTier1; }
    public boolean isWitchNeutralBadTier1() { return witchNeutralBadTier1; }
    /** How many trades to draw from the pool when the player is at the given bad tier. */
    public int getWitchPoolSize(int tier) { return witchPoolSizes.getOrDefault(tier, 6); }
    public List<WitchTrade> getWitchTrades() { return witchTrades; }
    public int getBonusHusbandrySpawnsTier1() { return bonusHusbandrySpawnsTier1; }
    public int getBonusHusbandrySpawnsTier2() { return bonusHusbandrySpawnsTier2; }
    public double getBonusHusbandryXpTier2() { return bonusHusbandryXpTier2; }
    public double getBonusHusbandryXpTier3() { return bonusHusbandryXpTier3; }
    public boolean isBetterVillagerTradesTier3() { return betterVillagerTradesTier3; }
    public boolean isWraithEnabled() { return wraithEnabled; }
    public int getWraithMinBadTier() { return wraithMinBadTier; }
    public double getWraithAttackRangeBlocks() { return wraithAttackRangeBlocks; }
    public double getWraithTeleportRangeBlocks() { return wraithTeleportRangeBlocks; }
    public int getWraithMinSleepDeprivationTicks() { return wraithMinSleepDeprivationTicks; }
    public int getVilFleeTrigerTier() { return vilFleeTrigerTier; }
    public int getVilFleeRadius() { return vilFleeRadius; }
    public double getVilFleeSpeed() { return vilFleeSpeed; }
    public double getVilFleeDistance() { return vilFleeDistance; }
    public int getVilCallForHelpTier() { return vilCallForHelpTier; }
    public int getVilCallForHelpRadius() { return vilCallForHelpRadius; }
    public int getVilDefendTier() { return vilDefendTier; }
    public Material getVilDefendWeapon() { return vilDefendWeapon; }
    public List<String> getVilDefendPotionEffects() { return vilDefendPotionEffects; }
    public int getVilDefendDurationSeconds() { return vilDefendDurationSeconds; }
    public int getVilDefendChaseTimeoutSeconds() { return vilDefendChaseTimeoutSeconds; }
    public int getVilDefendMaxChaseDist() { return vilDefendMaxChaseDist; }
    public int getVilNativeRadius() { return vilNativeRadius; }
    public int getTotemDyingCustomModelData() { return totemDyingCustomModelData; }
    public double getTotemDyingDropRate() { return totemDyingDropRate; }
    public int getTotemDyingTransformMinutes() { return totemDyingTransformMinutes; }
    public double getTotemDyingRestoreHealth() { return totemDyingRestoreHealth; }
    public String getTotemDyingCombineMethod() { return totemDyingCombineMethod; }
    public String getTotemDyingCombinedName() { return totemDyingCombinedName; }
    public String getTotemDyingCombinedLore() { return totemDyingCombinedLore; }
    public int getTotemDyingAnvilCost() { return totemDyingAnvilCost; }
    public double getTotemUndyingDropRate() { return totemUndyingDropRate; }
    public int getTotemUndyingDropCount() { return totemUndyingDropCount; }
    public boolean isTotemUndyingDisableVanilla() { return totemUndyingDisableVanilla; }
    public boolean isTotemUndyingBadUnusable() { return totemUndyingBadUnusable; }
    public boolean isTotemUndyingNoDropOnBadKill() { return totemUndyingNoDropOnBadKill; }
    public boolean isVillageGolemEnabled() { return villageGolemEnabled; }
    public double getVillageGolemWindSurgePower() { return villageGolemWindSurgePower; }
    public int getVillageGolemWindSurgeCooldownSeconds() { return villageGolemWindSurgeCooldownSeconds; }
    public double getVillageGolemWindSurgePathThreshold() { return villageGolemWindSurgePathThreshold; }
    public boolean isGolemBossEnabled() { return golemBossEnabled; }
    public Material getGolemSpawnBlock() { return golemSpawnBlock; }
    public double getGolemHealth() { return golemHealth; }
    public double getGolemAttackDamage() { return golemAttackDamage; }
    public double getGolemMovementSpeed() { return golemMovementSpeed; }
    public List<String> getGolemEffects() { return golemEffects; }
    public double getGolemTotemDropRate() { return golemTotemDropRate; }
    public int getGolemRespawnCooldownMinutes() { return golemRespawnCooldownMinutes; }
    public int getGolemAggroRangeBlocks() { return golemAggroRangeBlocks; }
    public double getVillagerHeadDropRate() { return villagerHeadDropRate; }
    public String getVillagerHeadTextureHash() { return villagerHeadTextureHash; }
    public int getCompassBasicTierMax() { return compassBasicTierMax; }
    public int getCompassAdvancedTierMin() { return compassAdvancedTierMin; }
    public int getCompassMaxUses() { return compassMaxUses; }
    public int getCompassBasicUsesPerClick() { return compassBasicUsesPerClick; }
    public int getCompassAdvancedUsesPerUpdate() { return compassAdvancedUsesPerUpdate; }
    public int getCompassAdvancedUpdateTicks() { return compassAdvancedUpdateTicks; }
    public String getCompassRow1() { return compassRow1; }
    public String getCompassRow2() { return compassRow2; }
    public String getCompassRow3() { return compassRow3; }
    public String getCompassIngredientG() { return compassIngredientG; }
    public String getCompassIngredientC() { return compassIngredientC; }
    public int getLocatorUpdateTicks() { return locatorUpdateTicks; }
    public int getLocatorRangeVeryClose() { return locatorRangeVeryClose; }
    public int getLocatorRangeNearby() { return locatorRangeNearby; }
    public int getLocatorRangeFar() { return locatorRangeFar; }
    public String getLocatorLabelVeryClose() { return locatorLabelVeryClose; }
    public String getLocatorLabelNearby() { return locatorLabelNearby; }
    public String getLocatorLabelFar() { return locatorLabelFar; }
    public List<String> getFrostEnabledPhases() { return frostEnabledPhases; }
    public double getFrostStartThreshold() { return frostStartThreshold; }
    public double getFrostMaxThreshold() { return frostMaxThreshold; }
    public int getFrostUpdateTicks() { return frostUpdateTicks; }
    public boolean isHudActionBarEnabled() { return hudActionBarEnabled; }
    public int getHudActionBarUpdateTicks() { return hudActionBarUpdateTicks; }
    public String getHudActionBarFormat() { return hudActionBarFormat; }
    public Map<String, String> getActionBarTierColors() { return actionBarTierColors; }
    public boolean isActionBarPermanent() { return actionBarPermanent; }
    public boolean isHudMirrorEnabled() { return hudMirrorEnabled; }
    public int getMirrorDisplaySeconds() { return mirrorDisplaySeconds; }
    public String getMirrorRow1() { return mirrorRow1; }
    public String getMirrorRow2() { return mirrorRow2; }
    public String getMirrorRow3() { return mirrorRow3; }
    public String getMirrorIngredientG() { return mirrorIngredientG; }
    public String getMirrorIngredientP() { return mirrorIngredientP; }
    public List<String> getMoralityAliases() { return moralityAliases; }
}
