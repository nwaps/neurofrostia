package com.zonespawn;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class InventoryCloseListener implements Listener {

    private final ZoneSpawn plugin;

    public InventoryCloseListener(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (!player.isInvulnerable()) return;

        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());

        if (!title.contains(plugin.getConfigManager().getZonePickerTitle())) return;

        // Check if a zone was picked — if so, the command has already assigned the group.
        // Remove invulnerability and we're done.
        if (plugin.getLuckPermsHook().getZoneForPlayer(player) != null) {
            plugin.getInvulnerabilityTracker().setInvulnerable(player, false);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] Removed invulnerability from " + player.getName() + " after zone pick");
            }
            return;
        }

        // No zone selected — player closed the menu without picking.
        // Reopen the menu after 1 tick so they can't skip zone selection.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            String command = plugin.getConfigManager().getZonePickerReopenCommand() + " " + player.getName();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] Reopened zone picker for " + player.getName() + " (closed without picking)");
            }
        }, 1L);
    }
}
