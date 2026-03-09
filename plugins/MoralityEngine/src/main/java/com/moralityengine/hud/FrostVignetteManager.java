package com.moralityengine.hud;

import com.moralityengine.MoralityEngine;
import com.moralityengine.data.PlayerMorality;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Implements the frost vignette — a screen overlay effect during the End phase.
 *
 * The freeze/powder-snow overlay effect is used as the frost vignette.
 * Intensity scales linearly between the configured start and max bad-player ratios.
 *
 * Active only during phases listed in frost.enabled-phases (default: END only).
 */
public class FrostVignetteManager {

    private final MoralityEngine plugin;

    public FrostVignetteManager(MoralityEngine plugin) {
        this.plugin = plugin;
        startTask();
    }

    private void startTask() {
        int updateTicks = plugin.getConfigManager().getFrostUpdateTicks();
        new BukkitRunnable() {
            @Override
            public void run() {
                update();
            }
        }.runTaskTimer(plugin, updateTicks, updateTicks);
    }

    private void update() {
        // Check if current phase is in the enabled list
        if (!isActivePhase()) {
            // Remove freeze effect from all players
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                // Freeze effect is applied via FREEZE_TICKS, not a potion
                player.setFreezeTicks(0);
            }
            return;
        }

        double ratio = computeBadRatio();
        double start = plugin.getConfigManager().getFrostStartThreshold();
        double max = plugin.getConfigManager().getFrostMaxThreshold();

        double intensity = 0;
        if (ratio > start) {
            intensity = Math.min(1.0, (ratio - start) / (max - start));
        }

        // Apply freeze effect scaled by intensity
        // Maximum freeze ticks in Minecraft is 140 (7 seconds of powder snow)
        int freezeTicks = (int) (intensity * 140);

        int updateTicks = plugin.getConfigManager().getFrostUpdateTicks();
        // We refresh on each update tick so keep the freeze active between updates
        int targetFreezeTicks = freezeTicks + updateTicks + 20;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (freezeTicks <= 0) {
                player.setFreezeTicks(0);
            } else {
                // Only increase freeze ticks, don't reduce below current (avoids flickering)
                if (player.getFreezeTicks() < targetFreezeTicks) {
                    player.setFreezeTicks(targetFreezeTicks);
                }
            }
        }
    }

    private boolean isActivePhase() {
        if (plugin.getCoreLifeHook().isEmpty()) return false;
        String currentPhase = plugin.getCoreLifeHook().get().getCurrentPhase().name();
        return plugin.getConfigManager().getFrostEnabledPhases().stream()
                .anyMatch(p -> p.equalsIgnoreCase(currentPhase));
    }

    private double computeBadRatio() {
        int activeWindowDays = 7; // mirror CoreLife default; ideally read from CoreLife config
        long cutoff = System.currentTimeMillis() - (long) activeWindowDays * 24 * 3600 * 1000;

        Collection<PlayerMorality> allData = plugin.getMoralityDataManager().getAllPlayers();
        long total = 0;
        long bad = 0;

        for (PlayerMorality pm : allData) {
            Player player = Bukkit.getPlayer(pm.getUuid());
            if (player == null) continue; // only count online players for vignette
            total++;
            if (plugin.getMoralityManager().isBadMorality(pm.getUuid())) bad++;
        }

        return total == 0 ? 0 : (double) bad / total;
    }
}
