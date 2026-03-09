package com.moralityengine.items;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the Morality Compass:
 * - Craftable item, only functional for bad morality players.
 * - Basic (tiers 1-2): right-click to snapshot nearest good player location.
 * - Advanced (tiers 3-4): continuously tracks nearest good player.
 *
 * Durability is stored as remaining uses in the item's PDC rather than vanilla
 * item damage, since COMPASS has no vanilla durability system.
 */
public class MoralityCompassManager implements Listener {

    private final MoralityEngine plugin;
    private final NamespacedKey compassKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey usesKey;

    public MoralityCompassManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.compassKey = new NamespacedKey(plugin, "morality_compass");
        this.targetKey = new NamespacedKey(plugin, "compass_target");
        this.usesKey = new NamespacedKey(plugin, "compass_uses");
        registerRecipe();
        startAdvancedTrackingTask();
    }

    // ── Recipe registration ───────────────────────────────────────────────────

    private void registerRecipe() {
        ItemStack result = createCompass();
        ShapedRecipe recipe = new ShapedRecipe(compassKey, result);
        var cfg = plugin.getConfigManager();
        recipe.shape(cfg.getCompassRow1(), cfg.getCompassRow2(), cfg.getCompassRow3());

        Map<Character, Material> ingredientMap = new HashMap<>();
        ingredientMap.put('G', parseMaterial(cfg.getCompassIngredientG()));
        ingredientMap.put('C', parseMaterial(cfg.getCompassIngredientC()));
        ingredientMap.put('_', Material.AIR);

        ingredientMap.forEach((ch, mat) -> {
            if (mat != Material.AIR) recipe.setIngredient(ch, mat);
        });

        Bukkit.addRecipe(recipe);
    }

    // ── Item creation ─────────────────────────────────────────────────────────

    public ItemStack createCompass() {
        int maxUses = plugin.getConfigManager().getCompassMaxUses();
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Morality Compass", NamedTextColor.DARK_RED));
        meta.lore(List.of(
                Component.text("Right-click to locate the nearest good player.", NamedTextColor.GRAY),
                Component.text("Only functional for the morally corrupt.", NamedTextColor.DARK_GRAY),
                Component.text("Uses: " + maxUses + "/" + maxUses, NamedTextColor.DARK_RED)
        ));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, maxUses);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isMoralityCompass(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    // ── Basic compass right-click ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isMoralityCompass(item)) return;

        Player player = event.getPlayer();
        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());

        if (!tier.isBad()) {
            player.sendMessage(Component.text("The compass hums silently. It only works for the wicked.", NamedTextColor.GRAY));
            return;
        }

        if (tier.getLevel() >= plugin.getConfigManager().getCompassAdvancedTierMin()) {
            // Advanced compass — handled by periodic task
            player.sendMessage(Component.text("The compass pulses, tracking the righteous.", NamedTextColor.DARK_RED));
            return;
        }

        if (tier.getLevel() <= plugin.getConfigManager().getCompassBasicTierMax()) {
            event.setCancelled(true);
            Player target = findNearestGoodPlayer(player);
            if (target == null) {
                player.sendMessage(Component.text("No righteous souls detected in this dimension.", NamedTextColor.GRAY));
                return;
            }

            setCompassTarget(item, player, target.getLocation());

            int cost = plugin.getConfigManager().getCompassBasicUsesPerClick();
            if (consumeUse(player, item, cost)) {
                player.sendMessage(Component.text("The compass locks onto " + target.getName() + ".", NamedTextColor.RED));
            }
        }
    }

    // ── Advanced tracking task ────────────────────────────────────────────────

    private void startAdvancedTrackingTask() {
        int updateTicks = plugin.getConfigManager().getCompassAdvancedUpdateTicks();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
                    if (!tier.isBad() || tier.getLevel() < plugin.getConfigManager().getCompassAdvancedTierMin()) continue;

                    ItemStack held = player.getInventory().getItemInMainHand();
                    ItemStack offhand = player.getInventory().getItemInOffHand();
                    ItemStack compass = isMoralityCompass(held) ? held
                            : isMoralityCompass(offhand) ? offhand : null;
                    if (compass == null) continue;

                    Player target = findNearestGoodPlayer(player);
                    if (target != null) {
                        setCompassTarget(compass, player, target.getLocation());
                    }

                    int cost = plugin.getConfigManager().getCompassAdvancedUsesPerUpdate();
                    consumeUse(player, compass, cost);
                }
            }
        }.runTaskTimer(plugin, updateTicks, updateTicks);
    }

    // ── Durability helpers ────────────────────────────────────────────────────

    /**
     * Consumes {@code cost} uses from the compass's PDC counter.
     * Updates the lore to reflect the new count and sends a chat message.
     * If uses reach zero the item is removed and a break message is sent.
     *
     * @return true if the item survived, false if it broke
     */
    private boolean consumeUse(Player player, ItemStack compass, int cost) {
        ItemMeta meta = compass.getItemMeta();
        if (meta == null) return false;

        int maxUses = plugin.getConfigManager().getCompassMaxUses();
        int remaining = meta.getPersistentDataContainer()
                .getOrDefault(usesKey, PersistentDataType.INTEGER, maxUses);
        remaining -= cost;

        if (remaining <= 0) {
            compass.setAmount(0);
            player.sendMessage(Component.text("Your Morality Compass crumbles to dust.", NamedTextColor.DARK_RED));
            return false;
        }

        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, remaining);
        updateUsesLore(meta, remaining, maxUses);
        compass.setItemMeta(meta);

        player.sendMessage(Component.text("Morality Compass: " + remaining + "/" + maxUses + " uses remaining.", NamedTextColor.RED));
        return true;
    }

    private void updateUsesLore(ItemMeta meta, int remaining, int maxUses) {
        meta.lore(List.of(
                Component.text("Right-click to locate the nearest good player.", NamedTextColor.GRAY),
                Component.text("Only functional for the morally corrupt.", NamedTextColor.DARK_GRAY),
                Component.text("Uses: " + remaining + "/" + maxUses, NamedTextColor.DARK_RED)
        ));
    }

    // ── Compass pointing ──────────────────────────────────────────────────────

    private Player findNearestGoodPlayer(Player badPlayer) {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player other : badPlayer.getWorld().getPlayers()) {
            if (other.equals(badPlayer)) continue;
            if (!plugin.getMoralityManager().isGoodMorality(other.getUniqueId())) continue;
            double dist = badPlayer.getLocation().distanceSquared(other.getLocation());
            if (dist < nearestDist) { nearest = other; nearestDist = dist; }
        }
        return nearest;
    }

    private void setCompassTarget(ItemStack compass, Player holder, Location target) {
        if (!(compass.getItemMeta() instanceof CompassMeta meta)) return;
        meta.setLodestone(target);
        meta.setLodestoneTracked(false);
        compass.setItemMeta(meta);
    }

    private Material parseMaterial(String name) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return Material.AIR; }
    }
}
