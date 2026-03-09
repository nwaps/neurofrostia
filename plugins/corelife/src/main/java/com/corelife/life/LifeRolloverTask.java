package com.corelife.life;

import com.corelife.CoreLife;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Schedules and fires the life rollover on a configurable hourly interval.
 * Timing is epoch-aligned so it is predictable and consistent across restarts
 * (e.g. 24h always fires at midnight UTC; 168h always fires on the same weekday).
 * Persists the next-rollover timestamp so a server restart does not delay or skip
 * a due rollover.
 */
public class LifeRolloverTask extends BukkitRunnable {

    private static final String ROLLOVER_KEY = "nextRolloverEpochMillis";

    private final CoreLife plugin;
    private final File stateFile;
    private long nextRolloverEpochMillis;

    public LifeRolloverTask(CoreLife plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "rollover.yml");
        this.nextRolloverEpochMillis = loadOrCompute();
    }

    /** Schedule this task against the server scheduler. Returns itself for chaining. */
    public LifeRolloverTask schedule() {
        // Check every 60 seconds whether rollover is due
        this.runTaskTimer(plugin, 20L * 60, 20L * 60);
        return this;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        if (now < nextRolloverEpochMillis) return;

        plugin.getLogger().info("[Rollover] Firing life rollover.");
        plugin.getLifeManager().applyRollover();

        // Advance to the next rollover
        nextRolloverEpochMillis = advanceTimestamp(nextRolloverEpochMillis);
        persistTimestamp();
    }

    /** Load persisted timestamp from disk, or compute the first future rollover. */
    private long loadOrCompute() {
        if (stateFile.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(stateFile);
            long stored = yml.getLong(ROLLOVER_KEY, 0L);
            if (stored > 0) {
                if (stored <= System.currentTimeMillis()) {
                    plugin.getLogger().info("[Rollover] Missed rollover detected — will fire on next check.");
                    return System.currentTimeMillis(); // triggers on first run()
                }
                return stored;
            }
        }
        return computeFirstRollover();
    }

    /**
     * Compute the first rollover as the next epoch-aligned interval boundary.
     * For example, with a 24h interval this always lands on midnight UTC;
     * with 168h it lands on the same weekday each week.
     */
    private long computeFirstRollover() {
        long intervalMs = intervalMs();
        long now = System.currentTimeMillis();
        return ((now / intervalMs) + 1) * intervalMs;
    }

    private long advanceTimestamp(long from) {
        return from + intervalMs();
    }

    private long intervalMs() {
        int hours = plugin.getConfigManager().getRolloverIntervalHours();
        if (hours <= 0) hours = 168; // safety guard — never zero
        return (long) hours * 3_600_000L;
    }

    private void persistTimestamp() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set(ROLLOVER_KEY, nextRolloverEpochMillis);
        try {
            yml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save rollover state.", e);
        }
    }

    public long getNextRolloverEpochMillis() { return nextRolloverEpochMillis; }
}
