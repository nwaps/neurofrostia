package com.moralityengine.progression;

import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import com.moralityengine.analytics.MoralityChangeReason;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Listens to in-game events and grants morality points per progression source.
 * Does NOT handle kill events from CoreLife — those go through CoreLifeEventListener.
 */
public class ProgressionManager implements Listener {

    private final MoralityEngine plugin;
    /** Tracks players who recently consumed rotten flesh — used for villager-meat detection. */
    private final Set<UUID> recentVillagerKillers = new HashSet<>();

    public ProgressionManager(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    // ── Mob kill events ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        UUID killerUuid = killer.getUniqueId();
        Entity victim = event.getEntity();

        // Kill undead → good morality
        if (plugin.getConfigManager().isGoodKillUndeadEnabled() && isUndead(victim)) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getGoodKillUndeadMode(),
                    cfg.getGoodKillUndeadPoints(), cfg.getGoodKillUndeadPercentage(),
                    plugin.getMoralityManager().getScore(killerUuid));
            MoralityEngine.debug("Undead kill by " + killer.getName() + " (" + victim.getType() + ")"
                    + " → +" + delta + " good pts");
            plugin.getMoralityManager().addScore(killerUuid, delta, MoralityChangeReason.KILL_UNDEAD);
            return;
        }

        // Kill regular iron golem (not a Golem Boss) → bad morality
        if (victim instanceof IronGolem golem
                && !plugin.getGolemBossManager().isGolemBoss(golem)
                && plugin.getConfigManager().isBadKillIronGolemEnabled()) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadKillIronGolemMode(),
                    cfg.getBadKillIronGolemPoints(), cfg.getBadKillIronGolemPercentage(),
                    plugin.getMoralityManager().getScore(killerUuid));
            MoralityEngine.debug("Iron golem killed by " + killer.getName() + " → -" + delta + " bad pts");
            plugin.getMoralityManager().addScore(killerUuid, -delta, MoralityChangeReason.KILL_IRON_GOLEM);
            return;
        }

        // Kill villager
        if (victim instanceof Villager) {
            // Immediate bad morality for killing a villager (no flesh required)
            if (plugin.getConfigManager().isBadKillVillagerEnabled()) {
                var cfg = plugin.getConfigManager();
                double delta = ConfigManager.resolveDelta(cfg.getBadKillVillagerMode(),
                        cfg.getBadKillVillagerPoints(), cfg.getBadKillVillagerPercentage(),
                        plugin.getMoralityManager().getScore(killerUuid));
                MoralityEngine.debug("Villager killed by " + killer.getName() + " → -" + delta + " bad pts");
                plugin.getMoralityManager().addScore(killerUuid, -delta, MoralityChangeReason.KILL_VILLAGER);
            }
            // Drop flesh and mark killer for the consume-check (no time limit)
            if (plugin.getConfigManager().isBadKillVillagerConsumeEnabled()) {
                int count = 0;
                double dropRate = plugin.getConfigManager().getBadKillVillagerConsumeFleshDropRate();
                for (int i = 0; i < plugin.getConfigManager().getBadKillVillagerConsumeFleshDropCount(); i++) {
                    if (Math.random() < dropRate) count++;
                }
                MoralityEngine.debug("Villager killed by " + killer.getName() + " — dropping " + count + " rotten flesh (rate=" + dropRate + "), marked for consume check (no time limit)");
                if (count > 0) event.getDrops().add(new ItemStack(Material.ROTTEN_FLESH, count));
                recentVillagerKillers.add(killerUuid);
            }
        }

        // Kill passive mob → bad morality
        if (plugin.getConfigManager().isBadKillPassiveMobEnabled()
                && plugin.getConfigManager().getBadKillPassiveMobs().contains(victim.getType())) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadKillPassiveMobMode(),
                    cfg.getBadKillPassiveMobPoints(), cfg.getBadKillPassiveMobPercentage(),
                    plugin.getMoralityManager().getScore(killerUuid));
            MoralityEngine.debug("Passive mob killed by " + killer.getName() + " (" + victim.getType() + ")"
                    + " → -" + delta + " bad pts");
            plugin.getMoralityManager().addScore(killerUuid, -delta, MoralityChangeReason.KILL_PASSIVE_MOB);
        }
    }

    // ── Villager meat consumption ─────────────────────────────────────────────
    // A "rotten flesh" item is what Villagers conceptually drop. We detect when
    // a player who recently killed a villager consumes rotten flesh.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.ROTTEN_FLESH) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (recentVillagerKillers.remove(uuid)) {
            // Player consumed rotten flesh shortly after killing a villager
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadKillVillagerConsumeMode(),
                    cfg.getBadKillVillagerConsumePoints(), cfg.getBadKillVillagerConsumePercentage(),
                    plugin.getMoralityManager().getScore(uuid));
            MoralityEngine.debug("Villager meat consumed by " + player.getName() + " → -" + delta + " bad pts");
            plugin.getMoralityManager().addScore(uuid, -delta, MoralityChangeReason.KILL_VILLAGER_CONSUME);
        } else if (plugin.getConfigManager().isBadEatRottenFleshEnabled()) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadEatRottenFleshMode(),
                    cfg.getBadEatRottenFleshPoints(), cfg.getBadEatRottenFleshPercentage(),
                    plugin.getMoralityManager().getScore(uuid));
            MoralityEngine.debug("Rotten flesh consumed by " + player.getName() + " → -" + delta + " bad pts");
            plugin.getMoralityManager().addScore(uuid, -delta, MoralityChangeReason.EAT_ROTTEN_FLESH);
        } else {
            MoralityEngine.debug("Rotten flesh consumed by " + player.getName() + " (not a recent villager killer — no bad pts)");
        }

        // No-rotten-flesh-penalty perk: cancel hunger/nausea for bad tier 1+
        if (plugin.getConfigManager().isNoRottenFleshPenaltyTier1()) {
            MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
            if (tier.isBad() && tier.getLevel() >= 1) {
                // Schedule removal of Hunger/Nausea on the next tick
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.removePotionEffect(PotionEffectType.HUNGER);
                    player.removePotionEffect(PotionEffectType.NAUSEA);
                }, 1L);
            }
        }
    }

    // ── Hero of the Village advancement ──────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfigManager().isGoodHeroOfVillageEnabled()) return;
        String key = event.getAdvancement().getKey().getKey();
        // The Hero of the Village advancement key
        if (key.equals("adventure/hero_of_the_village")) {
            UUID playerUuid = event.getPlayer().getUniqueId();
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getGoodHeroOfVillageMode(),
                    cfg.getGoodHeroOfVillagePoints(), cfg.getGoodHeroOfVillagePercentage(),
                    plugin.getMoralityManager().getScore(playerUuid));
            MoralityEngine.debug("Hero of the Village: " + event.getPlayer().getName() + " → +" + delta + " good pts");
            plugin.getMoralityManager().addScore(playerUuid, delta, MoralityChangeReason.HERO_OF_VILLAGE);
        }
    }

    // ── Morality reset on death ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isOnDeathEnabled()) return;
        UUID uuid = event.getEntity().getUniqueId();
        double score = plugin.getMoralityManager().getScore(uuid);
        if (score == 0.0) return;

        double newScore;
        if ("flat".equals(cfg.getOnDeathMode())) {
            // Move score toward 0 by the flat amount; don't overshoot
            double reduction = Math.min(cfg.getOnDeathFlat(), Math.abs(score));
            newScore = score > 0 ? score - reduction : score + reduction;
            MoralityEngine.debug("On-death flat: " + uuid + " " + String.format("%.2f", score)
                    + " → " + String.format("%.2f", newScore) + " (flat=" + cfg.getOnDeathFlat() + ")");
        } else {
            // Percentage: shrink magnitude toward 0
            double factor = Math.max(0.0, 1.0 - cfg.getOnDeathPercentage() / 100.0);
            newScore = score * factor;
            MoralityEngine.debug("On-death percentage: " + uuid + " " + String.format("%.2f", score)
                    + " → " + String.format("%.2f", newScore) + " (pct=" + cfg.getOnDeathPercentage() + "%)");
        }
        plugin.getMoralityManager().addScore(uuid, newScore - score, MoralityChangeReason.DEATH_DECAY);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    public boolean isUndead(Entity entity) {
        return switch (entity.getType()) {
            case ZOMBIE, ZOMBIE_VILLAGER, ZOMBIFIED_PIGLIN, DROWNED, HUSK,
                 SKELETON, STRAY, WITHER_SKELETON, PHANTOM, ZOGLIN,
                 WITHER, SKELETON_HORSE, ZOMBIE_HORSE, GIANT -> true;
            default -> false;
        };
    }
}
