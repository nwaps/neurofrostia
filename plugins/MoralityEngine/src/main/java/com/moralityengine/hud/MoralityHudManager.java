package com.moralityengine.hud;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages:
 * - Action bar morality display (configurable tick refresh).
 * - Morality Mirror craftable item (glass pane that changes color with tier).
 */
public class MoralityHudManager implements Listener {

    private final MoralityEngine plugin;
    private final NamespacedKey mirrorKey;
    private final Map<UUID, Long> hudActiveUntil = new HashMap<>();

    public MoralityHudManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.mirrorKey = new NamespacedKey(plugin, "morality_mirror");
        if (plugin.getConfigManager().isHudMirrorEnabled()) registerMirrorRecipe();
        if (plugin.getConfigManager().isHudActionBarEnabled()) startActionBarTask();
    }

    // ── Action bar ─────────────────────────────────────────────────────────────

    private void startActionBarTask() {
        int ticks = plugin.getConfigManager().getHudActionBarUpdateTicks();
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateMirrorInInventory(player);
                    if (plugin.getConfigManager().isActionBarPermanent()) {
                        sendActionBar(player);
                    } else {
                        Long until = hudActiveUntil.get(player.getUniqueId());
                        if (until != null && System.currentTimeMillis() < until) {
                            sendActionBar(player);
                        } else {
                            hudActiveUntil.remove(player.getUniqueId());
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, ticks, ticks);
    }

    private void sendActionBar(Player player) {
        // Yield to CoreLife's combat-tag countdown when the player is tagged
        if (plugin.getCoreLifeHook()
                .map(api -> api.isPlayerCombatTagged(player.getUniqueId()))
                .orElse(false)) return;

        UUID uuid = player.getUniqueId();
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        double score = plugin.getMoralityManager().getScore(uuid);

        String livesStr = plugin.getCoreLifeHook()
                .map(api -> String.valueOf(api.getPlayerLives(uuid)))
                .orElse("?");

        String formatted = plugin.getConfigManager().getHudActionBarFormat()
                .replace("{tier}", tier.toString())
                .replace("{score}", String.format("%.1f", score))
                .replace("{next-threshold}", formatNextThreshold(uuid, tier, score))
                .replace("{lives}", livesStr);

        String colorName = plugin.getConfigManager().getActionBarTierColors().get(tier.name());
        TextColor color = colorName != null ? NamedTextColor.NAMES.value(colorName.toLowerCase()) : null;
        Component bar = color != null ? Component.text(formatted, color) : Component.text(formatted);
        player.sendActionBar(bar);
    }

    private String formatNextThreshold(UUID uuid, MoralityTier tier, double score) {
        if (tier.isBad()) {
            double dist = plugin.getMoralityManager().distanceToNextBadTier(uuid);
            return dist == 0 ? "MAX" : String.format("%.0f to next", dist);
        } else if (tier.isGood()) {
            double dist = plugin.getMoralityManager().distanceToNextGoodTier(uuid);
            return dist == 0 ? "MAX" : String.format("%.0f to next", dist);
        } else {
            // Neutral — show distance to bad tier 1 and good tier 1
            double toBad = Math.abs(score - plugin.getConfigManager().getBadThreshold(1));
            double toGood = plugin.getConfigManager().getGoodThreshold(1) - score;
            return String.format("%.0f bad / %.0f good", toBad, toGood);
        }
    }

    // ── Morality Mirror ───────────────────────────────────────────────────────

    private Material tierToGlassPaneMaterial(MoralityTier tier) {
        String colorName = plugin.getConfigManager().getActionBarTierColors()
                .getOrDefault(tier.name(), "WHITE");
        return switch (colorName.toUpperCase()) {
            case "DARK_RED"    -> Material.RED_STAINED_GLASS_PANE;
            case "RED"         -> Material.ORANGE_STAINED_GLASS_PANE;
            case "GOLD"        -> Material.YELLOW_STAINED_GLASS_PANE;
            case "YELLOW"      -> Material.YELLOW_STAINED_GLASS_PANE;
            case "GREEN"       -> Material.LIME_STAINED_GLASS_PANE;
            case "DARK_GREEN"  -> Material.GREEN_STAINED_GLASS_PANE;
            case "DARK_PURPLE" -> Material.PURPLE_STAINED_GLASS_PANE;
            case "AQUA"        -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "DARK_AQUA"   -> Material.CYAN_STAINED_GLASS_PANE;
            case "BLUE"        -> Material.BLUE_STAINED_GLASS_PANE;
            case "DARK_BLUE"   -> Material.BLUE_STAINED_GLASS_PANE;
            case "BLACK"       -> Material.BLACK_STAINED_GLASS_PANE;
            case "GRAY"        -> Material.GRAY_STAINED_GLASS_PANE;
            case "DARK_GRAY"   -> Material.GRAY_STAINED_GLASS_PANE;
            default            -> Material.WHITE_STAINED_GLASS_PANE;
        };
    }

    private void registerMirrorRecipe() {
        ItemStack result = createMirror(Material.WHITE_STAINED_GLASS_PANE);
        ShapedRecipe recipe = new ShapedRecipe(mirrorKey, result);
        var cfg = plugin.getConfigManager();
        recipe.shape(cfg.getMirrorRow1(), cfg.getMirrorRow2(), cfg.getMirrorRow3());

        Map<Character, Material> ingredients = new HashMap<>();
        ingredients.put('G', parseMaterial(cfg.getMirrorIngredientG()));
        ingredients.put('P', parseMaterial(cfg.getMirrorIngredientP()));
        ingredients.put('_', Material.AIR);
        ingredients.forEach((ch, mat) -> { if (mat != Material.AIR) recipe.setIngredient(ch, mat); });

        Bukkit.addRecipe(recipe);
    }

    public ItemStack createMirror(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Morality Mirror", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(Component.text("Right-click to view your morality.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(mirrorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void updateMirrorInInventory(Player player) {
        MoralityTier tier = plugin.getMoralityManager().getTier(player.getUniqueId());
        Material expected = tierToGlassPaneMaterial(tier);
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isMoralityMirror(item) && item.getType() != expected) {
                ItemStack updated = createMirror(expected);
                updated.setAmount(item.getAmount());
                inventory.setItem(i, updated);
            }
        }
    }

    public boolean isMoralityMirror(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mirrorKey, PersistentDataType.BYTE);
    }

    // Note: RIGHT_CLICK_AIR never fires for block items (glass pane) — the Minecraft client
    // does not send a "use item" packet when looking at air with a placeable item.
    // The mirror therefore requires a nearby surface to right-click against.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMoralityMirror(event.getItem())) return;

        // Cancel at the earliest possible priority so Paper immediately rolls back
        // the client-side predictive placement without any visible flash.
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        if (!plugin.getConfigManager().isActionBarPermanent()) {
            int seconds = plugin.getConfigManager().getMirrorDisplaySeconds();
            hudActiveUntil.put(event.getPlayer().getUniqueId(),
                    System.currentTimeMillis() + (seconds * 1000L));
            // Send immediately so there's no delay on first right-click
            sendActionBar(event.getPlayer());
        }
    }

    private Material parseMaterial(String name) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return Material.AIR; }
    }
}
