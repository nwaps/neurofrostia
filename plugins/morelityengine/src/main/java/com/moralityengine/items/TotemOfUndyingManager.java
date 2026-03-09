package com.moralityengine.items;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import org.bukkit.Material;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Manages the Totem of Undying overrides:
 * - Controls drop rate from Evokers (optionally replacing vanilla drop).
 * - Prevents bad-morality players from activating the totem.
 * - Suppresses drops when the killing player has bad morality.
 */
public class TotemOfUndyingManager implements Listener {

    private final MoralityEngine plugin;
    private final Random random = new Random();

    public TotemOfUndyingManager(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    // ── Evoker drop control ───────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEvokerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Evoker)) return;

        Player killer = event.getEntity().getKiller();

        // Suppress drops when killed by a bad morality player
        if (killer != null && plugin.getConfigManager().isTotemUndyingNoDropOnBadKill()) {
            MoralityTier killerTier = plugin.getMoralityManager().getTier(killer.getUniqueId());
            if (killerTier.isBad()) {
                if (plugin.getConfigManager().isTotemUndyingDisableVanilla()) {
                    event.getDrops().removeIf(item -> item.getType() == Material.TOTEM_OF_UNDYING);
                }
                return;
            }
        }

        if (!plugin.getConfigManager().isTotemUndyingDisableVanilla()) return;

        // Remove vanilla totem drop
        event.getDrops().removeIf(item -> item.getType() == Material.TOTEM_OF_UNDYING);

        // Roll our custom drop
        int dropCount = plugin.getConfigManager().getTotemUndyingDropCount();
        double dropRate = plugin.getConfigManager().getTotemUndyingDropRate();
        for (int i = 0; i < dropCount; i++) {
            if (random.nextDouble() < dropRate) {
                event.getDrops().add(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
        }
    }

    // ── Bad morality cannot activate totem ───────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!plugin.getConfigManager().isTotemUndyingBadUnusable()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
        if (!tier.isBad()) return;

        // Check if the hand item is a vanilla totem (not our custom Totem of Dying)
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack offhand = player.getInventory().getItemInOffHand();

        boolean usingVanillaTotem = (hand.getType() == Material.TOTEM_OF_UNDYING
                && !plugin.getTotemOfDyingManager().isTotemOfDying(hand))
                || (offhand.getType() == Material.TOTEM_OF_UNDYING
                && !plugin.getTotemOfDyingManager().isTotemOfDying(offhand));

        if (usingVanillaTotem) {
            // Cancel resurrection — totem is not consumed
            event.setCancelled(true);
        }
    }
}
