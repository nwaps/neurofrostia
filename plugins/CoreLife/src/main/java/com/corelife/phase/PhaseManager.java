package com.corelife.phase;

import com.corelife.CoreLife;
import com.corelife.api.Phase;
import com.corelife.api.events.PhaseChangeEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class PhaseManager {

    private final CoreLife plugin;
    private final File stateFile;

    private Phase currentPhase = Phase.OVERWORLD;
    private long phaseStartEpochMillis = System.currentTimeMillis();
    private boolean serverLocked = false;
    private BukkitTask autoAdvanceTask = null;

    public PhaseManager(CoreLife plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "phase.yml");
        load();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        if (!stateFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(stateFile);
        String phaseName = yml.getString("phase", "OVERWORLD");
        try {
            currentPhase = Phase.valueOf(phaseName);
        } catch (IllegalArgumentException e) {
            currentPhase = Phase.OVERWORLD;
        }
        phaseStartEpochMillis = yml.getLong("phaseStartEpochMillis", System.currentTimeMillis());
        serverLocked = yml.getBoolean("serverLocked", false);
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("phase", currentPhase.name());
        yml.set("phaseStartEpochMillis", phaseStartEpochMillis);
        yml.set("serverLocked", serverLocked);
        try {
            yml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save phase state.", e);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public Phase getCurrentPhase() { return currentPhase; }

    public boolean isServerLocked() { return serverLocked; }

    /**
     * Advance to the next phase. Fires PhaseChangeEvent and broadcasts.
     * The End phase can only be set manually — auto-advance never sets it.
     */
    public void setPhase(Phase newPhase) {
        Phase old = currentPhase;
        currentPhase = newPhase;
        phaseStartEpochMillis = System.currentTimeMillis();
        save();
        CoreLife.debug("Phase changed: " + old + " → " + newPhase);

        PhaseChangeEvent event = new PhaseChangeEvent(old, newPhase);
        Bukkit.getPluginManager().callEvent(event);

        String broadcast = switch (newPhase) {
            case NETHER -> plugin.getConfigManager().getBroadcastNetherOpen();
            case END -> plugin.getConfigManager().getBroadcastEndOpen();
            default -> null;
        };
        if (broadcast != null) {
            Bukkit.broadcast(Component.text(broadcast));
        }

        // Cancel any existing auto-advance task and reschedule for new phase
        cancelAutoAdvance();
        scheduleAutoAdvance();
    }

    /**
     * Schedule automatic OVERWORLD → NETHER transition if auto-advance is enabled.
     * Called on startup and after each phase change.
     */
    public void scheduleAutoAdvance() {
        if (!plugin.getConfigManager().isAutoAdvance()) return;

        long durationDays;
        Phase targetPhase;

        if (currentPhase == Phase.OVERWORLD) {
            durationDays = plugin.getConfigManager().getOverworldDurationDays();
            targetPhase = Phase.NETHER;
        } else {
            // Auto-advance only applies to OVERWORLD→NETHER; END is manual only
            return;
        }

        long elapsedMs = System.currentTimeMillis() - phaseStartEpochMillis;
        long durationMs = durationDays * 24L * 60 * 60 * 1000;
        long remainingMs = durationMs - elapsedMs;

        if (remainingMs <= 0) {
            CoreLife.debug("Auto-advance: " + currentPhase + " → " + targetPhase + " overdue, advancing immediately");
            // Already past — advance immediately
            setPhase(targetPhase);
            return;
        }

        long remainingTicks = (remainingMs / 1000) * 20L;
        CoreLife.debug("Auto-advance scheduled: " + currentPhase + " → " + targetPhase
                + " in " + (remainingMs / 1000 / 60) + "m " + (remainingMs / 1000 % 60) + "s");
        Phase finalTarget = targetPhase;
        autoAdvanceTask = new BukkitRunnable() {
            @Override
            public void run() {
                setPhase(finalTarget);
            }
        }.runTaskLater(plugin, remainingTicks);
    }

    public void cancelAutoAdvance() {
        if (autoAdvanceTask != null && !autoAdvanceTask.isCancelled()) {
            autoAdvanceTask.cancel();
            autoAdvanceTask = null;
        }
    }

    /**
     * Lock the server — no new connections accepted.
     * Broadcasts lock message and persists state.
     */
    public void lockServer() {
        serverLocked = true;
        save();
        Bukkit.broadcast(Component.text(plugin.getConfigManager().getLockBroadcast()));
        plugin.getLogger().info("[CoreLife] Server is now locked. No new connections will be accepted.");
    }

    /**
     * Trigger the wind-down period. Broadcasts the wind-down message and schedules server lock.
     */
    public void beginWindDown() {
        int windDownDays = plugin.getConfigManager().getWindDownDays();
        Bukkit.broadcast(Component.text(plugin.getConfigManager().getWindDownBroadcast()));
        long ticks = (long) windDownDays * 24 * 60 * 60 * 20;
        new BukkitRunnable() {
            @Override
            public void run() {
                lockServer();
            }
        }.runTaskLater(plugin, ticks);
        plugin.getLogger().info("[CoreLife] Wind-down started. Server locks in " + windDownDays + " day(s).");
    }

    /** Called by PlayerJoinQuitListener to handle locked-server rejections. */
    public void handleLoginForLock(PlayerLoginEvent event) {
        if (serverLocked && !event.getPlayer().hasPermission("corelife.admin")) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Component.text(plugin.getConfigManager().getLockBroadcast()));
        }
    }

    public long getPhaseStartEpochMillis() { return phaseStartEpochMillis; }

    /** Remove the server lock (admin debug use only). */
    public void unlockServer() {
        serverLocked = false;
        save();
    }
}
