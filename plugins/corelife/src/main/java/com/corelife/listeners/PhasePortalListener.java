package com.corelife.listeners;

import com.corelife.CoreLife;
import com.corelife.api.Phase;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

public class PhasePortalListener implements Listener {

    private final CoreLife plugin;

    public PhasePortalListener(CoreLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Phase phase = plugin.getPhaseManager().getCurrentPhase();
        TeleportCause cause = event.getCause();

        if (cause == TeleportCause.NETHER_PORTAL && phase == Phase.OVERWORLD) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text(plugin.getConfigManager().getPortalBlockedNether()));
            CoreLife.debug("Blocked nether portal for " + event.getPlayer().getName() + " (phase=OVERWORLD)");
        } else if (cause == TeleportCause.END_PORTAL && phase != Phase.END) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text(plugin.getConfigManager().getPortalBlockedEnd()));
            CoreLife.debug("Blocked end portal for " + event.getPlayer().getName() + " (phase=" + phase + ")");
        }
    }
}
