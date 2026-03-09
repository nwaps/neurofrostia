package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;

import java.util.UUID;

/**
 * Manages the application of tier perks. Called whenever a player's morality score changes.
 */
public class PerkManager {

    private final MoralityEngine plugin;

    public PerkManager(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    /**
     * Called by MoralityManager whenever a player's score changes.
     * Updates CoreLife XP multiplier registration for this player.
     */
    public void updateXpMultiplier(UUID uuid) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        double multiplier = getXpMultiplierForTier(tier);
        MoralityEngine.debug("XP multiplier update: " + uuid + " tier=" + tier + " multiplier=" + multiplier);
        plugin.getCoreLifeHook().ifPresent(api -> api.registerKillXpMultiplier(uuid, multiplier));
    }

    public double getXpMultiplierForTier(MoralityTier tier) {
        if (!tier.isBad()) return 1.0;
        return switch (tier.getLevel()) {
            case 1 -> plugin.getConfigManager().getBonusKillXpTier1();
            case 2 -> plugin.getConfigManager().getBonusKillXpTier2();
            case 3 -> plugin.getConfigManager().getBonusKillXpTier3();
            case 4 -> plugin.getConfigManager().getBonusKillXpTier4();
            default -> 1.0;
        };
    }

    /** Returns the bonus husbandry spawn count for this player's tier. */
    public int getBonusHusbandrySpawns(UUID uuid) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        if (!tier.isGood()) return 0;
        return switch (tier.getLevel()) {
            case 1 -> plugin.getConfigManager().getBonusHusbandrySpawnsTier1();
            case 2 -> plugin.getConfigManager().getBonusHusbandrySpawnsTier2();
            default -> plugin.getConfigManager().getBonusHusbandrySpawnsTier2();
        };
    }

    /** Returns the husbandry XP multiplier for this player's good tier. */
    public double getBonusHusbandryXpMultiplier(UUID uuid) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        if (!tier.isGood()) return 1.0;
        return switch (tier.getLevel()) {
            case 2 -> plugin.getConfigManager().getBonusHusbandryXpTier2();
            case 3 -> plugin.getConfigManager().getBonusHusbandryXpTier3();
            default -> 1.0;
        };
    }

    /** Whether better villager trades apply to this player. */
    public boolean hasBetterVillagerTrades(UUID uuid) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        return tier.isGood() && tier.getLevel() >= 3
                && plugin.getConfigManager().isBetterVillagerTradesTier3();
    }
}
