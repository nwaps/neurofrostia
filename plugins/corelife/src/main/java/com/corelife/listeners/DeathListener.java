package com.corelife.listeners;

import com.corelife.CoreLife;
import com.corelife.api.events.PlayerKillEvent;
import com.corelife.combat.GhostManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DeathListener implements Listener {

    private final CoreLife plugin;
    /** Tracks the last player who damaged each ghost (ghost entity UUID → player UUID). */
    private final Map<UUID, UUID> lastGhostAttacker = new HashMap<>();

    public DeathListener(CoreLife plugin) {
        this.plugin = plugin;
    }

    // ── Ghost protection ─────────────────────────────────────────────────────

    /**
     * Cancel all damage to ghost villagers that does not come from a player.
     * This prevents mobs (zombie sieges, raids), environmental hazards (lava, lightning,
     * fall damage), and other non-player sources from killing the ghost.
     * When a player IS the source, cache them as the last attacker for getKiller() fallback.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGhostDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        if (!plugin.getGhostManager().isGhost(le)) return;

        if (event instanceof EntityDamageByEntityEvent ede) {
            Player attacker = resolvePlayer(ede.getDamager());
            if (attacker != null) {
                lastGhostAttacker.put(le.getUniqueId(), attacker.getUniqueId());
                return; // let the player's damage through
            }
        }
        event.setCancelled(true);
    }

    /**
     * Prevent the ghost villager from being converted to a ZombieVillager by zombie infection.
     * Conversion would assign a new UUID, breaking the ghost tracking maps.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGhostTransform(EntityTransformEvent event) {
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        if (plugin.getGhostManager().isGhost(le)) event.setCancelled(true);
    }

    /**
     * Prevent players from opening the villager trade GUI when right-clicking the ghost.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGhostInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LivingEntity le)) return;
        if (plugin.getGhostManager().isGhost(le)) event.setCancelled(true);
    }

    // ── Ghost kill ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity le = event.getEntity();
        GhostManager gm = plugin.getGhostManager();
        if (!gm.isGhost(le)) return;

        // Clear drops and XP — ghost loot is handled manually via processPlayerDeath
        event.getDrops().clear();
        event.setDroppedExp(0);

        UUID victimUuid = gm.getGhostOwner(le);

        // Prefer Bukkit's getKiller(); fall back to our cached last attacker
        Player killer = le.getKiller();
        UUID killerUuid;
        if (killer != null) {
            killerUuid = killer.getUniqueId();
            lastGhostAttacker.remove(le.getUniqueId());
        } else {
            killerUuid = lastGhostAttacker.remove(le.getUniqueId());
        }

        CoreLife.debug("Ghost killed: owner=" + victimUuid + " killer=" + (killerUuid != null ? killerUuid : "none"));
        gm.onGhostKilled(le.getUniqueId());
        processPlayerDeath(killerUuid, victimUuid, le.getLocation(), true);

        // If the victim is offline, flag them for a forced death on rejoin so they
        // actually respawn rather than loading in alive with a missing life.
        if (org.bukkit.Bukkit.getPlayer(victimUuid) == null) {
            CoreLife.debug("Ghost owner " + victimUuid + " is offline — marking pendingGhostKill for rejoin");
            gm.markPendingGhostKill(victimUuid);
        }
    }

    // ── Real player death ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // This death was forced on rejoin after the ghost was already killed offline.
        // Life removal already happened in the ghost-kill path — just let Minecraft's
        // normal death/respawn sequence run without deducting another life.
        if (plugin.getGhostManager().consumePendingGhostKill(victim.getUniqueId())) {
            CoreLife.debug("Skipping life removal for " + victim.getName() + " — forced death after offline ghost kill");
            plugin.getCombatTagManager().removeTag(victim.getUniqueId());
            return;
        }

        // getKiller() is the reliable Bukkit API — returns the last Player to deal damage.
        // getLastDamageCause() can be null or stale by the time this event fires in Paper 1.21.x.
        Player killer = victim.getKiller();
        boolean isPvp = killer != null;

        if (!isPvp && plugin.getConfigManager().isPvpOnly()) {
            CoreLife.debug("Non-PvP death for " + victim.getName() + " — pvp-only=true, skipping.");
            return;
        }

        if (isPvp) {
            CoreLife.debug("PvP death: " + victim.getName() + " killed by " + killer.getName());
            if (plugin.getConfigManager().isDropEnderchestOnPvpDeath()) {
                dropEnderchest(victim);
            }
        } else {
            CoreLife.debug("Non-PvP death: " + victim.getName() + " — pvp-only=false, removing life.");
        }

        UUID killerUuid = isPvp ? killer.getUniqueId() : null;
        processPlayerDeath(killerUuid, victim.getUniqueId(), victim.getLocation(), false);
    }

    // ── Core death processing ─────────────────────────────────────────────────

    /**
     * Central method for handling all PvP player-kill consequences.
     * Called for both real deaths and ghost kills.
     *
     * @param killerUuid  May be null if the killer is unknown (e.g., ghost survived timer? edge case).
     * @param victimUuid  The player who died / whose ghost was killed.
     * @param location    Location of the kill.
     * @param ghostKill   Whether this was a ghost kill.
     */
    public void processPlayerDeath(UUID killerUuid, UUID victimUuid, Location location, boolean ghostKill) {
        CoreLife.debug("processPlayerDeath: killer=" + killerUuid + " victim=" + victimUuid + " ghostKill=" + ghostKill
                + " loc=" + (int) location.getX() + "," + (int) location.getY() + "," + (int) location.getZ());
        // Fire API event so MoralityEngine can react and register XP multipliers
        PlayerKillEvent killEvent = new PlayerKillEvent(killerUuid, victimUuid, location, ghostKill);
        org.bukkit.Bukkit.getPluginManager().callEvent(killEvent);

        // XP drops
        if (killerUuid != null) {
            applyXp(killerUuid, victimUuid);
        }

        // Life removal — may trigger ban
        plugin.getLifeManager().removeLives(victimUuid, 1);

        // Remove combat tag from victim
        plugin.getCombatTagManager().removeTag(victimUuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private void dropEnderchest(Player victim) {
        List<ItemStack> toDrop = new ArrayList<>();
        for (ItemStack item : victim.getEnderChest().getContents()) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                toDrop.add(item.clone());
            }
        }
        CoreLife.debug("Enderchest drop for " + victim.getName() + ": " + toDrop.size() + " item(s)");
        victim.getEnderChest().clear();
        Location loc = victim.getLocation();
        for (ItemStack item : toDrop) {
            loc.getWorld().dropItemNaturally(loc, item);
        }
    }

    private void applyXp(UUID killerUuid, UUID victimUuid) {
        Player killer = org.bukkit.Bukkit.getPlayer(killerUuid);
        Player victim = org.bukkit.Bukkit.getPlayer(victimUuid);

        // Victim XP loss
        if (victim != null) {
            int victimLoss = plugin.getConfigManager().getVictimLossBase();
            int currentXp = victim.getTotalExperience();
            int newXp = Math.max(0, currentXp - victimLoss);
            CoreLife.debug("XP loss: " + victim.getName() + " " + currentXp + " → " + newXp + " (base loss=" + victimLoss + ")");
            victim.setTotalExperience(newXp);
            victim.setLevel(xpToLevel(victim.getTotalExperience()));
        }

        // Killer XP reward * morality multiplier
        if (killer != null) {
            double base = plugin.getConfigManager().getKillerRewardBase();
            double multiplier = plugin.getAPI().getKillXpMultiplier(killerUuid);
            int reward = (int) Math.round(base * multiplier);
            CoreLife.debug("XP reward: " + killer.getName() + " +" + reward + " (base=" + (int) base + ", multiplier=" + multiplier + ")");
            killer.giveExp(reward);
        }
    }

    /** Very rough approximation of level from total XP (Bukkit doesn't expose this directly). */
    private int xpToLevel(int totalXp) {
        // Reverse Minecraft level formula (approximate)
        if (totalXp < 0) return 0;
        if (totalXp <= 352) return (int) (Math.sqrt(totalXp + 9) - 3);
        if (totalXp <= 1507) return (int) ((Math.sqrt(40 * totalXp - 7839) + 9) / 20);
        return (int) ((Math.sqrt(72 * totalXp - 54215) + 325) / 18);
    }
}
