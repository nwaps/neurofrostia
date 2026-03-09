package com.corelife.combat;

import com.corelife.CoreLife;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which players are combat-tagged and when their tag expires.
 * Tags are refreshed on each PvP damage event.
 */
public class CombatTagManager {

    private final CoreLife plugin;
    /** Maps player UUID to tag expiry timestamp (epoch millis). */
    private final Map<UUID, Long> taggedPlayers = new HashMap<>();

    public CombatTagManager(CoreLife plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    /** Tag player for combat. Refreshes existing tag if already tagged. */
    public void tag(UUID uuid) {
        boolean wasTagged = isTagged(uuid);
        long durationMs = plugin.getConfigManager().getTagDurationSeconds() * 1000L;
        taggedPlayers.put(uuid, System.currentTimeMillis() + durationMs);
        CoreLife.debug("Combat tag " + (wasTagged ? "refreshed" : "applied") + " for " + uuid
                + " (expires in " + plugin.getConfigManager().getTagDurationSeconds() + "s)");
    }

    public boolean isTagged(UUID uuid) {
        Long expiry = taggedPlayers.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            taggedPlayers.remove(uuid);
            return false;
        }
        return true;
    }

    public void removeTag(UUID uuid) {
        boolean wasTagged = taggedPlayers.containsKey(uuid);
        taggedPlayers.remove(uuid);
        if (wasTagged) {
            CoreLife.debug("Combat tag removed for " + uuid);
        }
    }

    /** Remaining tag time in seconds, or 0 if not tagged. */
    public long remainingSeconds(UUID uuid) {
        Long expiry = taggedPlayers.get(uuid);
        if (expiry == null) return 0;
        long remaining = (expiry - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                taggedPlayers.entrySet().removeIf(e -> e.getValue() < now);
            }
        }.runTaskTimer(plugin, 20L * 5, 20L * 5);
    }
}
