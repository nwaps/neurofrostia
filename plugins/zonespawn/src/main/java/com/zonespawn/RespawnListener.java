package com.zonespawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class RespawnListener implements Listener {

    private final ZoneSpawn plugin;

    public RespawnListener(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var player = event.getPlayer();

        // Bypass permission — let normal respawn logic handle it
        if (player.hasPermission("zonespawn.bypass")) {
            return;
        }

        // Respect bed and anchor spawns
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }

        ZoneConfig zone = plugin.getLuckPermsHook().getZoneForPlayer(player);

        if (zone == null) {
            // No zone assigned — open the zone picker menu 1 tick after respawn and make invulnerable.
            // The 1-tick delay is required: opening a GUI in the same tick as the respawn event fires
            // before the player has fully loaded in, causing the menu to silently fail.
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] " + player.getName() + " has no zone assigned, opening zone picker");
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getInvulnerabilityTracker().setInvulnerable(player, true);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "deluxemenus open ZonePicker " + player.getName());
            }, 1L);
            return;
        }

        Location location = plugin.getSafeLocationFinder().findSafeLocation(zone);

        if (location == null) {
            if (plugin.getConfigManager().isFallbackToGroupSpawn()) {
                // Let EssentialsX group spawn handle it — just return without setting location
                plugin.getLogger().warning("[ZoneSpawn] No safe location found for " + player.getName()
                        + " in zone '" + zone.getName() + "', falling back to group spawn");
                return;
            }

            player.sendMessage("§cCould not find a safe spawn location in your zone. Please contact an admin.");
            return;
        }

        event.setRespawnLocation(location);

        String respawnMsg = plugin.getConfigManager().formatPlayerMessage(
                plugin.getConfigManager().getMsgRespawnPlayer(), zone, zone.getMessageRespawn(), player.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(respawnMsg), 1L);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[ZoneSpawn] Respawning " + player.getName() + " in zone '" + zone.getName() + "' at " + location);
        }
    }
}
