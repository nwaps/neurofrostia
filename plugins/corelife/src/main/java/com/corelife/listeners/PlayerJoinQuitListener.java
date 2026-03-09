package com.corelife.listeners;

import com.corelife.CoreLife;
import com.corelife.api.Phase;
import com.corelife.data.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinQuitListener implements Listener {

    private final CoreLife plugin;

    public PlayerJoinQuitListener(CoreLife plugin) {
        this.plugin = plugin;
    }

    // ── Login gate ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        PlayerData loginData = plugin.getPlayerDataManager().getData(player.getUniqueId());
        CoreLife.debug("Login attempt: " + player.getName()
                + " deathBanned=" + loginData.isDeathBanned()
                + " adminBanned=" + loginData.isAdminBanned()
                + " serverLocked=" + plugin.getPhaseManager().isServerLocked());

        // Locked server: reject all non-admins
        if (plugin.getPhaseManager().isServerLocked()
                && !player.hasPermission("corelife.admin")) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Component.text(plugin.getConfigManager().getLockBroadcast()));
            return;
        }

        // Death ban gate
        PlayerData data = plugin.getPlayerDataManager().getData(player.getUniqueId());
        if (data.isDeathBanned() && !data.isAdminBanned()) {
            if (plugin.getConfigManager().isAllowSpectateOnBan()) {
                // Allow; spectator mode applied in onPlayerJoin
                return;
            }
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    Component.text(plugin.getLifeManager().buildBanMessage()));
            return;
        }

        // Admin ban is a separate flag — just block entry
        if (data.isAdminBanned()) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    Component.text("You are banned from this server."));
        }
    }

    // ── Join processing ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerDataManager().getData(player.getUniqueId());

        // Update last-login timestamp
        data.setLastLogin(System.currentTimeMillis());
        plugin.getPlayerDataManager().save(data);

        // If spectating due to ban, put in spectator mode
        if (data.isDeathBanned() && plugin.getConfigManager().isAllowSpectateOnBan()) {
            // Delay one tick to let the server finish login
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.sendMessage(Component.text(
                            plugin.getLifeManager().buildBanMessage() + " (Spectating)"));
                }
            }, 1L);
        }

        // Despawn any ghost that was active for this player
        boolean hadGhost = plugin.getGhostManager().hasActiveGhost(player.getUniqueId());
        if (hadGhost) {
            CoreLife.debug("Player " + player.getName() + " rejoined while ghost active — despawning ghost");
            plugin.getGhostManager().despawnGhost(player.getUniqueId());
        }

        // Ghost was killed while this player was offline — force a death so they respawn
        // properly instead of loading in alive with a missing life.
        if (plugin.getGhostManager().hasPendingGhostKill(player.getUniqueId())) {
            CoreLife.debug("Player " + player.getName() + " rejoining after offline ghost kill — forcing death in 2 ticks");
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setHealth(0);
                }
            }, 2L);
        }

        // Phase join broadcast
        if (plugin.getConfigManager().isJoinBroadcastEnabled()) {
            Phase phase = plugin.getPhaseManager().getCurrentPhase();
            String msg = switch (phase) {
                case OVERWORLD -> plugin.getConfigManager().getJoinBroadcastOverworld();
                case NETHER -> plugin.getConfigManager().getJoinBroadcastNether();
                case END -> plugin.getConfigManager().getJoinBroadcastEnd();
            };
            int lives = plugin.getLifeManager().getLives(player.getUniqueId());
            msg = msg.replace("{lives}", String.valueOf(lives));
            player.sendMessage(Component.text(msg));
        }

        // ── Rollover notifications ────────────────────────────────────────────

        // One-time missed-rollover message (cleared after display)
        if (data.hasMissedLastRollover()) {
            long requiredMin = plugin.getLifeManager().getPlaytimeThresholdMs() / 60_000L;
            String cfg = plugin.getConfigManager().getRolloverMissedMessage();
            if (!cfg.isBlank()) {
                String msg = cfg.replace("{required}", String.valueOf(requiredMin))
                               .replace("{next_rollover}", plugin.getLifeManager().formatNextRollover());
                player.sendMessage(Component.text(colorize(msg)));
            }
            data.setMissedLastRollover(false);
            plugin.getPlayerDataManager().save(data);
        }

        // Rollover progress — shown only when the player hasn't yet met the threshold
        long thresholdMs = plugin.getLifeManager().getPlaytimeThresholdMs();
        if (thresholdMs > 0 && !data.isDeathBanned()) {
            long playedMs = data.getPlaytimeSinceRolloverMs();
            if (playedMs < thresholdMs) {
                String cfg = plugin.getConfigManager().getRolloverStatusMessage();
                if (!cfg.isBlank()) {
                    long playedMin   = playedMs / 60_000L;
                    long requiredMin = thresholdMs / 60_000L;
                    String msg = cfg.replace("{played}", String.valueOf(playedMin))
                                   .replace("{required}", String.valueOf(requiredMin))
                                   .replace("{next_rollover}", plugin.getLifeManager().formatNextRollover());
                    player.sendMessage(Component.text(colorize(msg)));
                }
            }
        }
    }

    // ── Quit processing ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerDataManager().getData(player.getUniqueId());

        // Accumulate this session's playtime into the rollover period counter
        long sessionMs = System.currentTimeMillis() - data.getLastLogin();
        data.addPlaytimeSinceRolloverMs(sessionMs);
        plugin.getPlayerDataManager().save(data);

        // If player is combat-tagged and ghost is enabled, spawn a ghost.
        // We pass 0 for the morality tier snapshot; MoralityEngine reads its own persisted
        // data when the ghost-kill event fires, so the snapshot is naturally preserved.
        boolean isTagged = plugin.getCombatTagManager().isTagged(player.getUniqueId());
        CoreLife.debug("Quit: " + player.getName() + " combatTagged=" + isTagged);
        if (isTagged) {
            plugin.getCombatTagManager().removeTag(player.getUniqueId());
            plugin.getGhostManager().spawnGhost(player, 0);
        }
    }

    private static String colorize(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}
