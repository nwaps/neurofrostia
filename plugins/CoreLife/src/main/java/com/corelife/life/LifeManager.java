package com.corelife.life;

import com.corelife.CoreLife;
import com.corelife.api.Phase;
import com.corelife.api.events.PlayerLifeLostEvent;
import com.corelife.data.PlayerData;
import com.corelife.data.PlayerDataManager;
import com.corelife.phase.PhaseManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class LifeManager {

    private final CoreLife plugin;
    private final PlayerDataManager dataManager;
    private final PhaseManager phaseManager;

    public LifeManager(CoreLife plugin, PlayerDataManager dataManager, PhaseManager phaseManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.phaseManager = phaseManager;
    }

    public int getLives(UUID uuid) {
        return dataManager.getData(uuid).getLives();
    }

    public void setLives(UUID uuid, int lives) {
        PlayerData data = dataManager.getData(uuid);
        int capped = cap(lives);
        data.setLives(capped);
        if (capped > 0 && data.isDeathBanned()) {
            data.setDeathBanned(false);
            CoreLife.debug("Death-ban cleared for " + uuid + " (lives set to " + capped + ")");
        }
        dataManager.save(data);
    }

    public void addLives(UUID uuid, int amount) {
        PlayerData data = dataManager.getData(uuid);
        int oldLives = data.getLives();
        int newLives = cap(oldLives + amount);
        data.setLives(newLives);
        if (newLives > 0 && data.isDeathBanned()) {
            data.setDeathBanned(false);
            CoreLife.debug("Death-ban cleared for " + uuid + " (lives added to " + newLives + ")");
        }
        CoreLife.debug("Lives added: " + uuid + " " + oldLives + " → " + newLives + " (+" + amount + ")");
        dataManager.save(data);
    }

    /**
     * Remove lives from victim. If lives reach 0, death-ban the player and return true.
     * Fires PlayerLifeLostEvent after each removal.
     */
    public boolean removeLives(UUID victimUuid, int amount) {
        PlayerData data = dataManager.getData(victimUuid);
        int oldLives = data.getLives();
        int newLives = Math.max(0, oldLives - amount);
        data.setLives(newLives);
        CoreLife.debug("Lives removed: " + victimUuid + " " + oldLives + " → " + newLives + " (removed " + amount + ")");

        // Fire life lost event
        PlayerLifeLostEvent event = new PlayerLifeLostEvent(victimUuid, newLives);
        Bukkit.getPluginManager().callEvent(event);

        if (newLives == 0) {
            applyDeathBan(victimUuid);
            dataManager.save(data);
            return true;
        }

        dataManager.save(data);
        return false;
    }

    private void applyDeathBan(UUID uuid) {
        CoreLife.debug("Applying death-ban to " + uuid + " (phase=" + phaseManager.getCurrentPhase() + ")");
        PlayerData data = dataManager.getData(uuid);
        data.setDeathBanned(true);
        // Reset playtime so this period doesn't carry stale time into the next rollover
        data.resetPlaytimeSinceRolloverMs();
        dataManager.save(data);

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            String msg = buildBanMessage();
            // Schedule kick for next tick — kicking during PlayerDeathEvent processing
            // causes a race condition in Paper 1.21.x (respawn packets haven't been sent yet).
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (online.isOnline()) online.kick(Component.text(msg));
            }, 1L);
        }
    }

    // ── Rollover ──────────────────────────────────────────────────────────────

    /**
     * Life rollover: unban death-banned players; give +1 life to eligible players.
     * Eligibility: player must be below the rollover cap AND have met the playtime
     * threshold (if configured). Death-banned players are always unbanned.
     */
    public void applyRollover() {
        int cap = effectiveRolloverCap();
        long thresholdMs = getPlaytimeThresholdMs();
        long now = System.currentTimeMillis();

        for (PlayerData data : dataManager.getAllPlayers()) {

            // Admin bans are never touched by rollover
            if (data.isAdminBanned()) continue;

            if (data.getLives() == 0 || data.isDeathBanned()) {
                // Always restore — no playtime check. Keyed off lives==0 rather than
                // the deathBanned flag alone, because PlayerQuitEvent accumulates session
                // time back onto playtimeSinceRolloverMs after applyDeathBan() resets it,
                // and state inconsistencies (e.g. flag cleared but lives still 0) would
                // otherwise leave the player silently skipped by both branches.
                data.setDeathBanned(false);
                data.setLives(1);
                data.resetPlaytimeSinceRolloverMs();
                Player online = Bukkit.getPlayer(data.getUuid());
                if (online != null) data.setLastLogin(now);
                dataManager.save(data);
                plugin.getLogger().info("[Rollover] Restored 1 life to " + data.getUuid());

            } else if (data.getLives() > 0) {

                // Include the current online session in the effective playtime total
                Player online = Bukkit.getPlayer(data.getUuid());
                long effectivePlaytime = data.getPlaytimeSinceRolloverMs()
                        + (online != null ? (now - data.getLastLogin()) : 0);

                // Already at or above the rollover cap — no life granted
                if (cap > 0 && data.getLives() >= cap) {
                    data.resetPlaytimeSinceRolloverMs();
                    if (online != null) data.setLastLogin(now);
                    dataManager.save(data);
                    continue;
                }

                // Playtime gate
                if (thresholdMs > 0 && effectivePlaytime < thresholdMs) {
                    long requiredMin = thresholdMs / 60_000L;
                    if (online != null) {
                        // Notify the player directly since they're online
                        String msg = substituteRolloverVars(
                                plugin.getConfigManager().getRolloverSkippedMessage(), requiredMin);
                        online.sendMessage(Component.text(colorize(msg)));
                        data.setLastLogin(now);
                    } else {
                        // Queue a one-time notification for their next login
                        data.setMissedLastRollover(true);
                    }
                    data.resetPlaytimeSinceRolloverMs();
                    dataManager.save(data);
                    continue;
                }

                // Grant rollover life
                data.setLives(data.getLives() + 1);
                data.resetPlaytimeSinceRolloverMs();
                if (online != null) data.setLastLogin(now);
                dataManager.save(data);
            }
        }

        Bukkit.getLogger().info("[CoreLife] Life rollover complete.");
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /**
     * Builds the appropriate ban message for the current phase and substitutes
     * the {next_rollover} placeholder with the formatted UTC rollover time.
     * Used by applyDeathBan() and PlayerJoinQuitListener.
     */
    public String buildBanMessage() {
        Phase phase = phaseManager.getCurrentPhase();
        String msg = (phase == Phase.END)
                ? plugin.getConfigManager().getBanMessageEndPhase()
                : plugin.getConfigManager().getBanMessage();
        return msg.replace("{next_rollover}", formatNextRollover());
    }

    /**
     * Returns the effective playtime threshold in milliseconds.
     * Percent (if > 0) takes priority over minutes.
     */
    public long getPlaytimeThresholdMs() {
        int percent = plugin.getConfigManager().getRolloverMinPlayPercent();
        if (percent > 0) {
            long intervalMs = (long) plugin.getConfigManager().getRolloverIntervalHours() * 3_600_000L;
            return (intervalMs * percent) / 100;
        }
        return (long) plugin.getConfigManager().getRolloverMinPlayMinutes() * 60_000L;
    }

    /** Formats the next rollover timestamp as "yyyy-MM-dd HH:mm UTC". */
    public String formatNextRollover() {
        LifeRolloverTask task = plugin.getRolloverTask();
        if (task == null) return "unknown";
        return Instant.ofEpochMilli(task.getNextRolloverEpochMillis())
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Effective rollover cap: rollover-max-lives if set, otherwise falls back to
     * max-lives, otherwise unlimited (0).
     */
    private int effectiveRolloverCap() {
        int rc = plugin.getConfigManager().getRolloverMaxLives();
        return (rc > 0) ? rc : plugin.getConfigManager().getMaxLives();
    }

    /** Apply the global max-lives cap (used for manual grants/sets). 0 = no cap. */
    private int cap(int lives) {
        int maxLives = plugin.getConfigManager().getMaxLives();
        if (maxLives > 0 && lives > maxLives) return maxLives;
        return Math.max(0, lives);
    }

    /** Replaces {required} and {next_rollover} in a rollover message template. */
    private String substituteRolloverVars(String msg, long requiredMin) {
        return msg.replace("{required}", String.valueOf(requiredMin))
                  .replace("{next_rollover}", formatNextRollover());
    }

    private static String colorize(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public boolean isDeathBanned(UUID uuid) {
        return dataManager.getData(uuid).isDeathBanned();
    }
}
