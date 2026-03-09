package com.zonespawn;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinQuitListener implements Listener {

    private final ZoneSpawn plugin;

    public JoinQuitListener(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // If the player disconnects while the zone picker is open, remove invulnerability immediately.
        // A 2-tick delayed removal would reference a stale/offline player object and may silently fail,
        // and invulnerability is persisted to player data — leaving them permanently invulnerable on rejoin.
        if (!player.isInvulnerable()) return;
        if (plugin.getLuckPermsHook().getZoneForPlayer(player) != null) return;

        plugin.getInvulnerabilityTracker().setInvulnerable(player, false);
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[ZoneSpawn] Removed invulnerability from " + player.getName() + " on disconnect (no zone assigned)");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Clear any stale persisted invulnerability first (left over from a crash mid-flow).
        if (player.isInvulnerable()) {
            plugin.getInvulnerabilityTracker().setInvulnerable(player, false);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] Cleared stale invulnerability from " + player.getName() + " on join");
            }
        }

        // If the player still has no zone assigned, reopen the zone picker after 20 ticks
        // so they have time to fully load in before the menu opens.
        if (plugin.getLuckPermsHook().getZoneForPlayer(player) != null) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getInvulnerabilityTracker().setInvulnerable(player, true);
            String command = plugin.getConfigManager().getZonePickerReopenCommand() + " " + player.getName();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] Reopened zone picker for " + player.getName() + " on join (no zone assigned)");
            }
        }, 20L);
    }
}
