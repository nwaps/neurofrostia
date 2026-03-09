package com.moralityengine.progression;

import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.analytics.MoralityChangeReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodic task that grants good morality points to players who are near good-morality players.
 * Runs at the configured interval.
 */
public class ProximityTask extends BukkitRunnable {

    private final MoralityEngine plugin;

    public ProximityTask(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfigManager().isGoodNearGoodEnabled()) return;
        int intervalSeconds = plugin.getConfigManager().getGoodNearGoodIntervalSeconds();
        this.runTaskTimer(plugin, 20L * intervalSeconds, 20L * intervalSeconds);
    }

    @Override
    public void run() {
        double range = plugin.getConfigManager().getGoodNearGoodRangeBlocks();
        var cfg = plugin.getConfigManager();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Any morality player benefits if near a good player
            boolean nearGood = player.getNearbyEntities(range, range, range).stream()
                    .filter(e -> e instanceof Player)
                    .map(e -> (Player) e)
                    .anyMatch(nearby -> plugin.getMoralityManager().isGoodMorality(nearby.getUniqueId()));

            if (nearGood) {
                double delta = ConfigManager.resolveDelta(cfg.getGoodNearGoodMode(),
                        cfg.getGoodNearGoodPoints(), cfg.getGoodNearGoodPercentage(),
                        plugin.getMoralityManager().getScore(player.getUniqueId()));
                MoralityEngine.debug("Proximity bonus: " + player.getName() + " near good player → +" + delta + " good pts");
                plugin.getMoralityManager().addScore(player.getUniqueId(), delta, MoralityChangeReason.PROXIMITY_BONUS);
            }
        }
    }
}
