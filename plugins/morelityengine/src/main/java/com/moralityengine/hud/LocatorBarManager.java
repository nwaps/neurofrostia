package com.moralityengine.hud;

import com.moralityengine.MoralityEngine;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the Player Locator Bar for good morality players.
 *
 * All nearby bad morality players are shown on a single BossBar. The direction bar is
 * built character-by-character as a rich Component so each player's ◆ dot is rendered
 * in their unique colour. Player name chips appear after the bar in the same colour:
 *   [──◆──◆────────────] RedPlayer · Nearby | AquaPlayer · Far
 *
 * Bar progress reflects the proximity of the closest target. The bar is hidden entirely
 * when no bad players are in range.
 */
public class LocatorBarManager {

    private static final TextColor[] PLAYER_COLORS = {
        NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.AQUA,
        NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.DARK_PURPLE,
        NamedTextColor.WHITE
    };

    private static final TextColor IDLE_COLOR    = NamedTextColor.DARK_GRAY;
    private static final TextColor BRACKET_COLOR  = NamedTextColor.GRAY;
    private static final TextColor SEP_COLOR      = NamedTextColor.DARK_GRAY;

    private final MoralityEngine plugin;
    /** observer UUID → their single locator BossBar */
    private final Map<UUID, BossBar> playerBars = new HashMap<>();

    public LocatorBarManager(MoralityEngine plugin) {
        this.plugin = plugin;
        startTask();
    }

    public void removeBar(UUID observerUuid) {
        BossBar bar = playerBars.remove(observerUuid);
        if (bar == null) return;
        Player observer = Bukkit.getPlayer(observerUuid);
        if (observer != null) observer.hideBossBar(bar);
    }

    public void removeAll() {
        playerBars.forEach((observerUuid, bar) -> {
            Player observer = Bukkit.getPlayer(observerUuid);
            if (observer != null) observer.hideBossBar(bar);
        });
        playerBars.clear();
    }

    private void startTask() {
        int updateTicks = plugin.getConfigManager().getLocatorUpdateTicks();
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    if (!plugin.getMoralityManager().isGoodMorality(uuid)) {
                        removeBar(uuid);
                        continue;
                    }
                    updateLocatorBar(player);
                }
                playerBars.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            }
        }.runTaskTimer(plugin, updateTicks, updateTicks);
    }

    private void updateLocatorBar(Player observer) {
        List<Player> targets = findBadPlayersInRange(observer);

        if (targets.isEmpty()) {
            removeBar(observer.getUniqueId());
            return;
        }

        Component title = buildTitle(observer, targets);
        double closestDist = observer.getLocation().distance(targets.get(0).getLocation());
        float progress = (float) Math.max(0.0, Math.min(1.0,
                1.0 - (closestDist / plugin.getConfigManager().getLocatorRangeFar())));

        BossBar bar = playerBars.get(observer.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            playerBars.put(observer.getUniqueId(), bar);
            observer.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    private Component buildTitle(Player observer, List<Player> targets) {
        int barWidth = 21;

        // Assign each bar slot the colour of the closest player whose dot lands there.
        // Targets are already sorted closest-first, so the first assignment wins.
        TextColor[] slots = new TextColor[barWidth];
        for (Player target : targets) {
            double relAngle = calculateRelativeAngle(observer, target);
            double clamped = Math.max(-90.0, Math.min(90.0, relAngle));
            double t = (clamped + 90.0) / 180.0;
            int idx = (int) Math.round(t * (barWidth - 1));
            if (slots[idx] == null) {
                slots[idx] = colorFor(target.getUniqueId());
            }
        }

        TextComponent.Builder builder = Component.text();

        // Direction bar
        builder.append(Component.text("[").color(BRACKET_COLOR));
        for (int i = 0; i < barWidth; i++) {
            if (slots[i] != null) {
                builder.append(Component.text("◆").color(slots[i]));
            } else {
                builder.append(Component.text("─").color(IDLE_COLOR));
            }
        }
        builder.append(Component.text("] ").color(BRACKET_COLOR));

        // Name chips — same colour as the player's dot
        for (int i = 0; i < targets.size(); i++) {
            Player target = targets.get(i);
            TextColor color = colorFor(target.getUniqueId());
            double distance = observer.getLocation().distance(target.getLocation());
            String label = getDistanceLabel(distance);

            if (i > 0) builder.append(Component.text(" | ").color(SEP_COLOR));
            builder.append(Component.text(target.getName()).color(color));
            if (!label.isEmpty()) {
                builder.append(Component.text(" · " + label).color(NamedTextColor.GRAY));
            }
        }

        return builder.build();
    }

    private TextColor colorFor(UUID id) {
        return PLAYER_COLORS[Math.abs(id.hashCode()) % PLAYER_COLORS.length];
    }

    /**
     * Returns all bad morality players in the same world within the configured far range,
     * sorted by distance (closest first).
     */
    private List<Player> findBadPlayersInRange(Player goodPlayer) {
        int farRange = plugin.getConfigManager().getLocatorRangeFar();
        List<Player> result = new ArrayList<>();
        for (Player other : goodPlayer.getWorld().getPlayers()) {
            if (other.equals(goodPlayer)) continue;
            if (!plugin.getMoralityManager().isBadMorality(other.getUniqueId())) continue;
            if (other.getGameMode() != org.bukkit.GameMode.SURVIVAL &&
                    other.getGameMode() != org.bukkit.GameMode.ADVENTURE) continue;
            if (other.hasMetadata("vanished")) continue;
            if (goodPlayer.getLocation().distance(other.getLocation()) <= farRange) {
                result.add(other);
            }
        }
        result.sort((a, b) -> Double.compare(
                goodPlayer.getLocation().distanceSquared(a.getLocation()),
                goodPlayer.getLocation().distanceSquared(b.getLocation())));
        return result;
    }

    /**
     * Calculates the horizontal angle from the player's facing direction to the target.
     * Returns a value in [-180, 180]: negative = target is to the left, positive = to the right.
     */
    private double calculateRelativeAngle(Player player, Player target) {
        double dx = target.getLocation().getX() - player.getLocation().getX();
        double dz = target.getLocation().getZ() - player.getLocation().getZ();
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double relAngle = bearing - player.getLocation().getYaw();
        while (relAngle > 180) relAngle -= 360;
        while (relAngle < -180) relAngle += 360;
        return relAngle;
    }

    private String getDistanceLabel(double distance) {
        var cfg = plugin.getConfigManager();
        if (distance <= cfg.getLocatorRangeVeryClose()) return cfg.getLocatorLabelVeryClose();
        if (distance <= cfg.getLocatorRangeNearby()) return cfg.getLocatorLabelNearby();
        return cfg.getLocatorLabelFar();
    }
}
