package com.corelife.listeners;

import com.corelife.CoreLife;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class CombatListener implements Listener {

    private final CoreLife plugin;

    public CombatListener(CoreLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player victim = resolvePlayer(event.getEntity());
        Player attacker = resolvePlayer(event.getDamager());

        if (victim == null || attacker == null) return;
        if (victim.equals(attacker)) return;

        // Don't tag against ghosts — ghost kills are handled separately
        if (plugin.getGhostManager().isGhost(victim)) return;

        CoreLife.debug("PvP hit: " + attacker.getName() + " → " + victim.getName() + " — tagging both");
        plugin.getCombatTagManager().tag(victim.getUniqueId());
        plugin.getCombatTagManager().tag(attacker.getUniqueId());
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
