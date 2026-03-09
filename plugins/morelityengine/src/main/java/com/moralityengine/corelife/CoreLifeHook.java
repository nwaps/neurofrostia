package com.moralityengine.corelife;

import com.corelife.CoreLife;
import com.corelife.api.CoreLifeAPI;
import com.corelife.api.Phase;
import com.corelife.api.events.PhaseChangeEvent;
import com.corelife.api.events.PlayerKillEvent;
import com.corelife.api.events.PlayerLifeLostEvent;
import com.moralityengine.ConfigManager;
import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import com.moralityengine.analytics.MoralityChangeReason;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Connects MoralityEngine to CoreLife.
 *
 * On startup:
 * 1. Resolves the CoreLife plugin instance (if present).
 * 2. Registers MoralityEngine's MoralityProvider with CoreLife.
 * 3. Listens to CoreLife's custom API events.
 *
 * If CoreLife is not installed, all hooks are no-ops and progression sources
 * that depend on CoreLife (kill attribution) are skipped gracefully.
 */
public class CoreLifeHook implements Listener {

    private final MoralityEngine plugin;
    private CoreLifeAPI api;

    public CoreLifeHook(MoralityEngine plugin) {
        this.plugin = plugin;
        connectToCoreLife();
    }

    private void connectToCoreLife() {
        Plugin coreLifePlugin = plugin.getServer().getPluginManager().getPlugin("CoreLife");
        if (!(coreLifePlugin instanceof CoreLife cl)) {
            plugin.getLogger().warning("CoreLife not found — kill attribution and XP multipliers will be disabled.");
            return;
        }
        api = cl.getAPI();

        // Register our morality provider so CoreLife can query bad-morality status
        // and tier names (used by the combat HUD action bar)
        api.registerMoralityProvider(new CoreLifeAPI.MoralityProvider() {
            @Override
            public boolean isBadMorality(UUID uuid) {
                return plugin.getMoralityManager().isBadMorality(uuid);
            }

            @Override
            public String getTierName(UUID uuid) {
                return plugin.getMoralityManager().getTier(uuid).name();
            }
        });

        plugin.getLogger().info("Hooked into CoreLife successfully.");
    }

    public Optional<CoreLifeAPI> get() {
        return Optional.ofNullable(api);
    }

    public boolean isPresent() { return api != null; }

    // ── CoreLife event listeners ───────────────────────────────────────────────

    /**
     * Fired on every PvP kill (including ghost kills).
     * Attributes morality progress and registers the killer's XP multiplier.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerKill(PlayerKillEvent event) {
        UUID killerUuid = event.getKillerUuid();
        UUID victimUuid = event.getVictimUuid();
        if (killerUuid == null) return;

        MoralityTier victimTier = plugin.getMoralityManager().getTier(victimUuid);
        MoralityTier killerTier = plugin.getMoralityManager().getTier(killerUuid);
        MoralityEngine.debug("Kill event received: killer=" + killerUuid + " (" + killerTier + ")"
                + " victim=" + victimUuid + " (" + victimTier + ")"
                + " ghostKill=" + event.isGhostKill());

        // Killer kills a good/neutral player → bad morality progress
        if (!victimTier.isBad() && plugin.getConfigManager().isBadKillNeutralGoodEnabled()) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getBadKillNeutralGoodMode(),
                    cfg.getBadKillNeutralGoodPoints(), cfg.getBadKillNeutralGoodPercentage(),
                    plugin.getMoralityManager().getScore(killerUuid));
            MoralityEngine.debug("Applying bad progress (kill neutral/good): -" + delta + " to " + killerUuid);
            plugin.getMoralityManager().addScore(killerUuid, -delta, MoralityChangeReason.KILL_NEUTRAL_GOOD_PLAYER);
        }

        // Killer kills a bad player → good morality progress
        if (victimTier.isBad() && plugin.getConfigManager().isGoodKillBadPlayerEnabled()) {
            var cfg = plugin.getConfigManager();
            double delta = ConfigManager.resolveDelta(cfg.getGoodKillBadPlayerMode(),
                    cfg.getGoodKillBadPlayerPoints(), cfg.getGoodKillBadPlayerPercentage(),
                    plugin.getMoralityManager().getScore(killerUuid));
            MoralityEngine.debug("Applying good progress (kill bad player): +" + delta + " to " + killerUuid);
            plugin.getMoralityManager().addScore(killerUuid, delta, MoralityChangeReason.KILL_BAD_PLAYER);
        }

        // Register current XP multiplier for this killer
        plugin.getPerkManager().updateXpMultiplier(killerUuid);
    }

    /**
     * Fired when the server phase changes.
     * MoralityEngine uses this to start/stop frost vignette and enforce life-trading lock.
     * (Life-trading lock is enforced in CoreLife itself; we just log the phase change.)
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPhaseChange(PhaseChangeEvent event) {
        plugin.getLogger().info("[MoralityEngine] Phase changed: "
                + event.getOldPhase() + " → " + event.getNewPhase());
        MoralityEngine.debug("PhaseChangeEvent received: " + event.getOldPhase() + " → " + event.getNewPhase());
        // Frost vignette manager checks the phase on each update tick, so no action needed here.
    }

    /**
     * Fired when a player loses a life.
     * MoralityEngine may use this to update active-player calculations if needed.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerLifeLost(PlayerLifeLostEvent event) {
        MoralityEngine.debug("PlayerLifeLostEvent received: " + event.getPlayerUuid()
                + " remainingLives=" + event.getRemainingLives());
        // Reserved for future use — e.g., adjusting proximity task counts.
    }
}
