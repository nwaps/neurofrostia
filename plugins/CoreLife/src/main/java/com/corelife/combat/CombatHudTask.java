package com.corelife.combat;

import com.corelife.ConfigManager;
import com.corelife.CoreLife;
import com.corelife.api.CoreLifeAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Repeating task that sends a combat-tag countdown to the action bar of every
 * player who is currently PvP-tagged.
 *
 * Two configurable message templates alternate on each run() call when
 * pvp-tag-hud-flash is enabled, creating a visual pulse to grab attention.
 * Both templates support the same variables:
 *   {seconds} — remaining tag seconds
 *   {lives}   — player's current CoreLife life count
 *   {tier}    — morality tier name (from MoralityEngine; "UNKNOWN" if absent)
 */
public class CombatHudTask extends BukkitRunnable {

    private final CoreLife plugin;
    private boolean flashState = false;

    public CombatHudTask(CoreLife plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isPvpTagHudEnabled()) return;

        String template;
        if (cfg.isPvpTagHudFlash()) {
            template = flashState ? cfg.getPvpTagHudFlashMessage() : cfg.getPvpTagHudMessage();
            flashState = !flashState;
        } else {
            template = cfg.getPvpTagHudMessage();
        }

        CoreLifeAPI.MoralityProvider mp = plugin.getMoralityProvider();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!plugin.getCombatTagManager().isTagged(uuid)) continue;

            long   seconds = plugin.getCombatTagManager().remainingSeconds(uuid);
            int    lives   = plugin.getLifeManager().getLives(uuid);
            String tier    = (mp != null) ? mp.getTierName(uuid) : "UNKNOWN";

            String formatted = template
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{lives}",   String.valueOf(lives))
                    .replace("{tier}",    tier);

            player.sendActionBar(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
        }
    }
}
