package com.moralityengine.items;

import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.analytics.MoralityChangeReason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the Totem of Dying custom item.
 *
 * Flow:
 * 1. Admin drops a Totem of Dying from the GolemBossManager.
 * 2. Player combines it with an armour piece via anvil (or crafting table).
 * 3. The resulting item is "cursed armour" — tagged with PDC.
 * 4. When the player would die while wearing cursed armour, death is prevented,
 *    health is restored, morality points are applied, and undead transformation starts.
 */
public class TotemOfDyingManager implements Listener {

    private static final String TOTEM_KEY = "totem_of_dying";
    private static final String CURSED_ARMOUR_KEY = "cursed_armour";
    private static final byte MARKER = 1;

    private final MoralityEngine plugin;
    private final NamespacedKey totemKey;
    private final NamespacedKey cursedKey;
    /** Players currently in undead transformation: UUID → task cancel handle */
    private final Map<UUID, BukkitRunnable> activeTransformations = new HashMap<>();

    public TotemOfDyingManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.totemKey = new NamespacedKey(plugin, TOTEM_KEY);
        this.cursedKey = new NamespacedKey(plugin, CURSED_ARMOUR_KEY);
    }

    // ── Item creation ─────────────────────────────────────────────────────────

    /** Create a Totem of Dying item (dropped by golem boss). */
    public ItemStack createTotemOfDying() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Totem of Dying", NamedTextColor.DARK_RED));
        meta.lore(List.of(Component.text("Combine with armour to create Cursed Armour.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(totemKey, PersistentDataType.BYTE, MARKER);
        int cmd = plugin.getConfigManager().getTotemDyingCustomModelData();
        if (cmd != 0) meta.setCustomModelData(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTotemOfDying(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(totemKey, PersistentDataType.BYTE);
    }

    /** Create a "cursed armour" piece from an armour item. */
    private ItemStack createCursedArmour(ItemStack armour) {
        ItemStack result = armour.clone();
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(plugin.getConfigManager().getTotemDyingCombinedName(), NamedTextColor.DARK_PURPLE));
        meta.lore(List.of(Component.text(plugin.getConfigManager().getTotemDyingCombinedLore(), NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(cursedKey, PersistentDataType.BYTE, MARKER);
        result.setItemMeta(meta);
        return result;
    }

    public boolean isCursedArmour(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(cursedKey, PersistentDataType.BYTE);
    }

    // ── Anvil combination ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!"anvil".equals(plugin.getConfigManager().getTotemDyingCombineMethod())) return;

        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);

        ItemStack totem = null;
        ItemStack armour = null;
        if (isTotemOfDying(first) && isArmour(second)) {
            totem = first; armour = second;
        } else if (isTotemOfDying(second) && isArmour(first)) {
            totem = second; armour = first;
        }

        if (totem == null || armour == null) return;
        event.setResult(createCursedArmour(armour));
        inv.setRepairCost(plugin.getConfigManager().getTotemDyingAnvilCost());
    }

    // ── Block vanilla resurrection when Totem of Dying is held in hand ────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isTotemOfDying(main) || isTotemOfDying(off)) {
            event.setCancelled(true);
        }
    }

    // ── Death interception ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getHealth() - event.getFinalDamage() > 0) return; // not lethal

        // Look for cursed armour in equipment slots
        ItemStack[] armourContents = player.getInventory().getArmorContents();
        int cursedSlot = -1;
        for (int i = 0; i < armourContents.length; i++) {
            if (isCursedArmour(armourContents[i])) {
                cursedSlot = i; break;
            }
        }
        if (cursedSlot == -1) return;

        // Prevent death
        event.setCancelled(true);
        player.setHealth(Math.min(plugin.getConfigManager().getTotemDyingRestoreHealth(),
                player.getMaxHealth()));

        // Remove the cursed armour piece
        ItemStack consumed = armourContents[cursedSlot].clone();
        armourContents[cursedSlot] = null;
        player.getInventory().setArmorContents(armourContents);

        // Play totem animation showing the consumed armour piece
        ItemStack previousMain = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(consumed);
        player.playEffect(EntityEffect.TOTEM_RESURRECT);
        player.getInventory().setItemInMainHand(previousMain);

        // Grant bad morality points
        if (plugin.getConfigManager().isBadPopTotemEnabled()) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadPopTotemMode(),
                    cfg.getBadPopTotemPoints(), cfg.getBadPopTotemPercentage(),
                    plugin.getMoralityManager().getScore(player.getUniqueId()));
            plugin.getMoralityManager().addScore(player.getUniqueId(), -delta, MoralityChangeReason.TOTEM_OF_DYING);
        }

        // Begin or reset undead transformation
        beginTransformation(player);
    }

    // ── Undead transformation ─────────────────────────────────────────────────

    private void beginTransformation(Player player) {
        UUID uuid = player.getUniqueId();
        int durationTicks = plugin.getConfigManager().getTotemDyingTransformMinutes() * 60 * 20;

        // Cancel any existing transformation so the timer resets to the full duration
        BukkitRunnable existing = activeTransformations.remove(uuid);
        if (existing != null && !existing.isCancelled()) existing.cancel();

        // Apply transformation effects
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, durationTicks, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, durationTicks, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, durationTicks, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, durationTicks, 0, false, false));

        player.sendMessage(Component.text("You have been cursed with undead transformation!", NamedTextColor.DARK_RED));

        BukkitRunnable endTask = new BukkitRunnable() {
            @Override
            public void run() {
                endTransformation(uuid);
            }
        };
        endTask.runTaskLater(plugin, durationTicks);
        activeTransformations.put(uuid, endTask);
    }

    public void endTransformation(UUID uuid) {
        BukkitRunnable task = activeTransformations.remove(uuid);
        if (task != null && !task.isCancelled()) task.cancel();

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        // Remove transformation effects
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.HUNGER);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
        player.removePotionEffect(PotionEffectType.HASTE);
        player.sendMessage(Component.text("Your undead transformation has ended.", NamedTextColor.GRAY));
    }

    public boolean isTransforming(UUID uuid) {
        return activeTransformations.containsKey(uuid);
    }

    /**
     * Check if player should take extra damage from Smite, cannot use Regen/Poison, or burns.
     * Called from a separate listener that handles combat damage and potion effects.
     */
    public boolean shouldBurnInSunlight(UUID uuid) {
        return isTransforming(uuid);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static final Set<Material> ARMOUR_TYPES = Set.of(
            Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
            Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.TURTLE_HELMET
    );

    private boolean isArmour(ItemStack item) {
        return item != null && ARMOUR_TYPES.contains(item.getType());
    }
}
