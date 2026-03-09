package com.moralityengine.perks;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets bad morality players shear a regular zombie with shears to harvest rotten flesh.
 * Once max-flesh-per-zombie items have been harvested the zombie converts into a skeleton,
 * inheriting whatever equipment the zombie was carrying.
 */
public class ZombieShearManager implements Listener {

    private final MoralityEngine plugin;
    /** entity UUID → cumulative flesh harvested so far */
    private final Map<UUID, Integer> fleshHarvested = new HashMap<>();

    public ZombieShearManager(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.getConfigManager().isZombieShearTier1()) return;
        // Only process the main-hand interaction to avoid firing twice
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Zombie zombie)) return;
        // Restrict to plain zombies; exclude husks, drowned, zombie villagers, etc.
        if (zombie.getType() != EntityType.ZOMBIE) return;

        Player player = event.getPlayer();
        ItemStack shears = player.getInventory().getItemInMainHand();
        if (shears.getType() != Material.SHEARS) return;

        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
        if (!tier.isBad() || tier.getLevel() < 1) return;

        event.setCancelled(true);

        UUID zombieId = zombie.getUniqueId();
        int harvested = fleshHarvested.getOrDefault(zombieId, 0);
        int maxFlesh = plugin.getConfigManager().getZombieShearMaxFlesh();

        if (harvested >= maxFlesh) {
            // Already at the limit — conversion should have happened; ignore
            return;
        }

        // Calculate how much flesh to drop this shear
        int fleshPerShear = plugin.getConfigManager().getZombieShearFleshPerShear();
        int drop = Math.min(fleshPerShear, maxFlesh - harvested);
        harvested += drop;
        fleshHarvested.put(zombieId, harvested);

        // Drop flesh at zombie's feet
        zombie.getWorld().dropItemNaturally(zombie.getLocation(),
                new ItemStack(Material.ROTTEN_FLESH, drop));

        // Play a shear-like sound so the interaction feels tangible
        zombie.getWorld().playSound(zombie.getLocation(),
                org.bukkit.Sound.ENTITY_SHEEP_SHEAR, 1.0f, 0.8f);

        // Damage the shears (respects Unbreaking via Damageable meta approach;
        // vanilla shear interactions cost 1 durability per use)
        damageShears(player, shears);

        MoralityEngine.debug("ZombieShear: " + player.getName()
                + " harvested " + harvested + "/" + maxFlesh + " flesh from zombie " + zombieId);

        if (harvested >= maxFlesh) {
            fleshHarvested.remove(zombieId);
            convertToSkeleton(zombie);
        }
    }

    /** Cleans up tracking when a zombie dies by other means before conversion. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        fleshHarvested.remove(event.getEntity().getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void damageShears(Player player, ItemStack shears) {
        if (!(shears.getItemMeta() instanceof Damageable meta)) return;
        int newDamage = meta.getDamage() + 1;
        if (newDamage >= shears.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            meta.setDamage(newDamage);
            shears.setItemMeta(meta);
        }
    }

    private void convertToSkeleton(Zombie zombie) {
        EntityEquipment ze = zombie.getEquipment();

        // Snapshot equipment and drop chances before removing the zombie
        ItemStack helmet   = ze != null ? copy(ze.getHelmet())         : null;
        ItemStack chest    = ze != null ? copy(ze.getChestplate())     : null;
        ItemStack legs     = ze != null ? copy(ze.getLeggings())       : null;
        ItemStack boots    = ze != null ? copy(ze.getBoots())          : null;
        ItemStack mainhand = ze != null ? copy(ze.getItemInMainHand()) : null;
        ItemStack offhand  = ze != null ? copy(ze.getItemInOffHand())  : null;

        float helmetChance    = ze != null ? ze.getHelmetDropChance()          : 0f;
        float chestChance     = ze != null ? ze.getChestplateDropChance()      : 0f;
        float legsChance      = ze != null ? ze.getLeggingsDropChance()        : 0f;
        float bootsChance     = ze != null ? ze.getBootsDropChance()           : 0f;
        float mainhandChance  = ze != null ? ze.getItemInMainHandDropChance()  : 0f;
        float offhandChance   = ze != null ? ze.getItemInOffHandDropChance()   : 0f;

        Location loc = zombie.getLocation().clone();
        zombie.remove();

        loc.getWorld().spawn(loc, Skeleton.class, sk -> {
            EntityEquipment se = sk.getEquipment();
            if (se == null) return;
            // Clear default skeleton bow
            se.setItemInMainHand(null);
            se.setItemInOffHand(null);
            se.setHelmet(null);
            se.setChestplate(null);
            se.setLeggings(null);
            se.setBoots(null);
            // Apply zombie's equipment
            se.setHelmet(helmet);              se.setHelmetDropChance(helmetChance);
            se.setChestplate(chest);           se.setChestplateDropChance(chestChance);
            se.setLeggings(legs);              se.setLeggingsDropChance(legsChance);
            se.setBoots(boots);                se.setBootsDropChance(bootsChance);
            se.setItemInMainHand(mainhand);    se.setItemInMainHandDropChance(mainhandChance);
            se.setItemInOffHand(offhand);      se.setItemInOffHandDropChance(offhandChance);
        });

        MoralityEngine.debug("ZombieShear: zombie at " + loc + " converted to skeleton");
    }

    private static ItemStack copy(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item.clone();
    }
}
