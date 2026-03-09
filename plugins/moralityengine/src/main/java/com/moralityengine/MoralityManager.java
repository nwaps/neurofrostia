package com.moralityengine;

import com.moralityengine.analytics.MoralityChangeReason;
import com.moralityengine.data.MoralityDataManager;
import com.moralityengine.data.PlayerMorality;

import java.util.UUID;

/**
 * Central authority for morality score and tier evaluation.
 * Tier evaluation uses strict exclusive lower bounds (score must strictly exceed threshold).
 */
public class MoralityManager {

    private final MoralityEngine plugin;
    private final MoralityDataManager dataManager;

    public MoralityManager(MoralityEngine plugin, MoralityDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    // ── Score access ──────────────────────────────────────────────────────────

    public double getScore(UUID uuid) {
        return dataManager.getData(uuid).getScore();
    }

    public void setScore(UUID uuid, double score) {
        PlayerMorality data = dataManager.getData(uuid);
        MoralityTier oldTier = scoreToTier(data.getScore());
        data.setScore(clamp(score));
        MoralityTier newTier = scoreToTier(score);
        MoralityEngine.debug("Score set: " + uuid + " → " + String.format("%.2f", score)
                + " tier=" + newTier + (oldTier != newTier ? " (was " + oldTier + ")" : ""));
        dataManager.save(data);
        onScoreChange(uuid);
    }

    /** Add delta and record the change reason in analytics. */
    public void addScore(UUID uuid, double delta, MoralityChangeReason reason) {
        PlayerMorality data = dataManager.getData(uuid);
        double oldScore = data.getScore();
        MoralityTier oldTier = scoreToTier(oldScore);
        data.setScore(clamp(oldScore + delta));
        double newScore = data.getScore();
        MoralityTier newTier = scoreToTier(newScore);
        MoralityEngine.debug("Score delta: " + uuid + " " + String.format("%+.2f", delta)
                + " [" + reason + "]"
                + " → " + String.format("%.2f", newScore)
                + " tier=" + newTier + (oldTier != newTier ? " (was " + oldTier + ")" : ""));
        dataManager.save(data);
        onScoreChange(uuid);
        plugin.getAnalyticsManager().record(uuid, reason, delta, newScore);
    }

    /** Add delta without analytics tracking (admin/direct use). */
    public void addScore(UUID uuid, double delta) {
        PlayerMorality data = dataManager.getData(uuid);
        double oldScore = data.getScore();
        MoralityTier oldTier = scoreToTier(oldScore);
        data.setScore(clamp(oldScore + delta));
        double newScore = data.getScore();
        MoralityTier newTier = scoreToTier(newScore);
        MoralityEngine.debug("Score delta: " + uuid + " " + String.format("%+.2f", delta)
                + " → " + String.format("%.2f", newScore)
                + " tier=" + newTier + (oldTier != newTier ? " (was " + oldTier + ")" : ""));
        dataManager.save(data);
        onScoreChange(uuid);
    }

    // ── Tier evaluation ───────────────────────────────────────────────────────

    public MoralityTier getTier(UUID uuid) {
        return scoreToTier(getScore(uuid));
    }

    /** Integer tier value: negative = bad, 0 = neutral, positive = good. */
    public int getTierInt(UUID uuid) {
        return getTier(uuid).getValue();
    }

    /** Absolute tier level (1–4), or 0 for neutral. */
    public int getTierLevel(UUID uuid) {
        return getTier(uuid).getLevel();
    }

    public boolean isBadMorality(UUID uuid) {
        return getTier(uuid).isBad();
    }

    public boolean isGoodMorality(UUID uuid) {
        return getTier(uuid).isGood();
    }

    public boolean isNeutral(UUID uuid) {
        return getTier(uuid).isNeutral();
    }

    /**
     * Evaluate tier from score using exclusive lower bounds.
     * A score of exactly a threshold is still in the lower tier (e.g., -100.0 is still neutral).
     */
    public MoralityTier scoreToTier(double score) {
        ConfigManager cfg = plugin.getConfigManager();
        double neutral = cfg.getNeutralRange();

        // Bad tiers (score strictly below threshold)
        if (score < cfg.getBadThreshold(4)) return MoralityTier.BAD_4;
        if (score < cfg.getBadThreshold(3)) return MoralityTier.BAD_3;
        if (score < cfg.getBadThreshold(2)) return MoralityTier.BAD_2;
        if (score < cfg.getBadThreshold(1)) return MoralityTier.BAD_1;

        // Neutral range: |score| < neutral-range
        if (Math.abs(score) < neutral) return MoralityTier.NEUTRAL;

        // Good tiers (score strictly above threshold)
        if (score > cfg.getGoodThreshold(4)) return MoralityTier.GOOD_4;
        if (score > cfg.getGoodThreshold(3)) return MoralityTier.GOOD_3;
        if (score > cfg.getGoodThreshold(2)) return MoralityTier.GOOD_2;
        return MoralityTier.GOOD_1;
    }

    /**
     * Distance to the next bad threshold below current score (negative = how much further to go).
     * Returns 0 if already at max bad tier.
     */
    public double distanceToNextBadTier(UUID uuid) {
        double score = getScore(uuid);
        MoralityTier tier = scoreToTier(score);
        int level = tier.isBad() ? tier.getLevel() : 0;
        if (level >= 4) return 0;
        return score - plugin.getConfigManager().getBadThreshold(level + 1);
    }

    /**
     * Distance to the next good threshold above current score.
     * Returns 0 if already at max good tier.
     */
    public double distanceToNextGoodTier(UUID uuid) {
        double score = getScore(uuid);
        MoralityTier tier = scoreToTier(score);
        int level = tier.isGood() ? tier.getLevel() : 0;
        if (level >= 4) return 0;
        return plugin.getConfigManager().getGoodThreshold(level + 1) - score;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double clamp(double score) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isCapEnabled()) return score;
        return Math.max(cfg.getCapMaxBad(), Math.min(cfg.getCapMaxGood(), score));
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Called whenever a score changes. Updates XP multiplier registration with CoreLife. */
    private void onScoreChange(UUID uuid) {
        plugin.getPerkManager().updateXpMultiplier(uuid);
    }
}
