package com.moralityengine.commands;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import com.moralityengine.analytics.AnalyticsManager;
import com.moralityengine.data.PlayerMorality;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handles the /moralityadmin command — admin and debug tools for MoralityEngine.
 *
 * Subcommands:
 *   setscore <player> <score>   — set score directly
 *   addscore <player> <delta>   — add or subtract points
 *   resetscore <player>         — reset to 0 (neutral)
 *   resetall                    — reset ALL players to 0
 *   settier <player> <tier>     — set score to just inside a named tier
 *   list [bad|good|neutral]     — list online players' tiers, optionally filtered
 *   bossinfo                    — show golem boss status and cooldown
 *   spawnboss                   — force-spawn boss at sender's location (player only)
 *   resetcooldown               — reset boss respawn cooldown
 *   givetotem <player> <dying|undying> — give a totem item to a player
 *   givehead <player> [count]   — give villager head(s) to a player
 *   reload                      — reload MoralityEngine config
 *   froststatus                 — show frost vignette ratio and intensity
 */
public class MoralityAdminCommand implements CommandExecutor, TabCompleter {

    private final MoralityEngine plugin;

    private static final List<String> SUBS = Arrays.asList(
            "setscore", "addscore", "resetscore", "resetall",
            "settier", "list", "bossinfo", "spawnboss", "resetcooldown",
            "givetotem", "givehead", "reload", "froststatus", "analytics"
    );

    private static final List<String> TIERS = Arrays.asList(
            "bad1", "bad2", "bad3", "bad4", "neutral",
            "good1", "good2", "good3", "good4"
    );

    public MoralityAdminCommand(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("moralityengine.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("MoralityAdmin | /moralityadmin <" + String.join("|", SUBS) + ">"));
            return true;
        }

        return switch (args[0].toLowerCase()) {

            case "setscore" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /moralityadmin setscore <player> <score>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                try {
                    double score = Double.parseDouble(args[2]);
                    plugin.getMoralityManager().setScore(target.getUniqueId(), score);
                    MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
                    sender.sendMessage(Component.text(String.format(
                            "Set %s's score to %.2f (%s).", target.getName(), score, tier)));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number."));
                }
                yield true;
            }

            case "addscore" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /moralityadmin addscore <player> <delta>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                try {
                    double delta = Double.parseDouble(args[2]);
                    plugin.getMoralityManager().addScore(target.getUniqueId(), delta);
                    double newScore = plugin.getMoralityManager().getScore(target.getUniqueId());
                    MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
                    sender.sendMessage(Component.text(String.format(
                            "Added %.2f to %s's score (now: %.2f, %s).", delta, target.getName(), newScore, tier)));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number."));
                }
                yield true;
            }

            case "resetscore" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /moralityadmin resetscore <player>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                plugin.getMoralityManager().setScore(target.getUniqueId(), 0.0);
                sender.sendMessage(Component.text("Reset " + target.getName() + "'s morality score to 0 (NEUTRAL)."));
                yield true;
            }

            case "resetall" -> {
                plugin.getMoralityDataManager().resetAll();
                // Update XP multipliers for all online players after reset
                Bukkit.getOnlinePlayers().forEach(p ->
                        plugin.getPerkManager().updateXpMultiplier(p.getUniqueId()));
                int count = plugin.getMoralityDataManager().getAllPlayers().size();
                sender.sendMessage(Component.text("Reset morality scores for " + count + " player(s) to 0."));
                yield true;
            }

            case "settier" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /moralityadmin settier <player> <tier>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                Double score = tierToScore(args[2].toLowerCase());
                if (score == null) {
                    sender.sendMessage(Component.text("Invalid tier. Valid: " + String.join(", ", TIERS)));
                    yield true;
                }
                plugin.getMoralityManager().setScore(target.getUniqueId(), score);
                MoralityTier tier = plugin.getMoralityManager().getTier(target.getUniqueId());
                sender.sendMessage(Component.text(String.format(
                        "Set %s to %s (score: %.2f).", target.getName(), tier, score)));
                yield true;
            }

            case "list" -> {
                String filter = args.length >= 2 ? args[1].toLowerCase() : "all";
                sender.sendMessage(Component.text("=== Morality List (online) ==="));
                int shown = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    MoralityTier tier = plugin.getMoralityManager().getTier(p.getUniqueId());
                    double score = plugin.getMoralityManager().getScore(p.getUniqueId());
                    boolean include = switch (filter) {
                        case "bad" -> tier.isBad();
                        case "good" -> tier.isGood();
                        case "neutral" -> tier.isNeutral();
                        default -> true;
                    };
                    if (!include) continue;
                    NamedTextColor colour = tier.isBad() ? NamedTextColor.RED
                            : tier.isGood() ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                    sender.sendMessage(Component.text(
                            String.format("  %s — %s (%.2f)", p.getName(), tier, score), colour));
                    shown++;
                }
                if (shown == 0) sender.sendMessage(Component.text("  (no matching players online)"));
                yield true;
            }

            case "bossinfo" -> {
                boolean active = plugin.getGolemBossManager().isActive();
                sender.sendMessage(Component.text("=== Golem Boss Info ==="));
                sender.sendMessage(Component.text("Boss active: " + active));
                if (!active) {
                    long remaining = plugin.getGolemBossManager().cooldownRemainingMs();
                    if (remaining > 0) {
                        long mins = remaining / 60_000;
                        long secs = (remaining % 60_000) / 1000;
                        sender.sendMessage(Component.text("Respawn cooldown: " + mins + "m " + secs + "s remaining"));
                    } else {
                        sender.sendMessage(Component.text("Respawn cooldown: ready (can be spawned now)"));
                    }
                }
                yield true;
            }

            case "spawnboss" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only a player can use spawnboss (location required)."));
                    yield true;
                }
                if (!plugin.getConfigManager().isGolemBossEnabled()) {
                    sender.sendMessage(Component.text("The Golem Boss is disabled in config (golem-boss.enabled: false).", NamedTextColor.RED));
                    yield true;
                }
                plugin.getGolemBossManager().forceSpawn(player.getLocation());
                sender.sendMessage(Component.text("Force-spawned Golem Boss at your location (cooldown bypassed)."));
                yield true;
            }

            case "resetcooldown" -> {
                plugin.getGolemBossManager().resetCooldown();
                sender.sendMessage(Component.text("Golem Boss respawn cooldown reset. Boss can now be spawned immediately."));
                yield true;
            }

            case "givetotem" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /moralityadmin givetotem <player> <dying|undying>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                ItemStack totem = switch (args[2].toLowerCase()) {
                    case "dying" -> plugin.getTotemOfDyingManager().createTotemOfDying();
                    case "undying" -> new ItemStack(Material.TOTEM_OF_UNDYING);
                    default -> null;
                };
                if (totem == null) { sender.sendMessage(Component.text("Invalid totem type. Use 'dying' or 'undying'.")); yield true; }
                target.getInventory().addItem(totem);
                sender.sendMessage(Component.text("Gave totem of " + args[2].toLowerCase() + " to " + target.getName() + "."));
                yield true;
            }

            case "givehead" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /moralityadmin givehead <player> [count]")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                int count = 1;
                if (args.length >= 3) {
                    try {
                        count = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Invalid count."));
                        yield true;
                    }
                }
                ItemStack head = plugin.getGolemBossManager().createVillagerHead();
                head.setAmount(count);
                target.getInventory().addItem(head);
                sender.sendMessage(Component.text("Gave " + count + " villager head(s) to " + target.getName() + "."));
                yield true;
            }

            case "reload" -> {
                plugin.getConfigManager().load();
                // Refresh XP multipliers after config change
                Bukkit.getOnlinePlayers().forEach(p ->
                        plugin.getPerkManager().updateXpMultiplier(p.getUniqueId()));
                sender.sendMessage(Component.text("MoralityEngine config reloaded."));
                yield true;
            }

            case "froststatus" -> {
                sender.sendMessage(Component.text("=== Frost Vignette Status ==="));
                boolean coreLifePresent = plugin.getCoreLifeHook().isPresent();
                if (!coreLifePresent) {
                    sender.sendMessage(Component.text("CoreLife not present — frost is inactive."));
                    yield true;
                }
                String currentPhase = plugin.getCoreLifeHook().get().getCurrentPhase().name();
                boolean frostActive = plugin.getConfigManager().getFrostEnabledPhases().stream()
                        .anyMatch(p -> p.equalsIgnoreCase(currentPhase));
                sender.sendMessage(Component.text("Current phase: " + currentPhase));
                sender.sendMessage(Component.text("Frost active: " + frostActive));

                // Compute bad ratio among online players
                long total = 0, bad = 0;
                for (PlayerMorality pm : plugin.getMoralityDataManager().getAllPlayers()) {
                    if (Bukkit.getPlayer(pm.getUuid()) == null) continue;
                    total++;
                    if (plugin.getMoralityManager().isBadMorality(pm.getUuid())) bad++;
                }
                double ratio = total == 0 ? 0 : (double) bad / total;
                double start = plugin.getConfigManager().getFrostStartThreshold();
                double max = plugin.getConfigManager().getFrostMaxThreshold();
                double intensity = (ratio <= start) ? 0 : Math.min(1.0, (ratio - start) / (max - start));

                sender.sendMessage(Component.text(String.format(
                        "Online bad ratio: %.1f%% (start: %.0f%%, max: %.0f%%)",
                        ratio * 100, start * 100, max * 100)));
                sender.sendMessage(Component.text(String.format("Frost intensity: %.0f%%", intensity * 100)));
                yield true;
            }

            case "analytics" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text(
                            "Usage: /moralityadmin analytics <server|player <name>|reset|export>"));
                    yield true;
                }
                AnalyticsManager am = plugin.getAnalyticsManager();
                switch (args[1].toLowerCase()) {

                    case "server" -> {
                        var stats = am.getServerStats();
                        sender.sendMessage(Component.text("=== Morality Analytics — Server (all time) ===",
                                NamedTextColor.GOLD));
                        if (stats.isEmpty()) {
                            sender.sendMessage(Component.text("  No data recorded yet.", NamedTextColor.GRAY));
                        } else {
                            for (AnalyticsManager.ReasonStat s : stats) {
                                boolean positive = s.totalPoints() >= 0;
                                String sign   = positive ? "[+]" : "[-]";
                                String prefix = String.format("%-4s %-22s  %5d events   %+10.1f pts   avg %+.1f",
                                        sign, s.reason().displayName(), s.count(), s.totalPoints(), s.avg());
                                sender.sendMessage(Component.text(prefix,
                                        positive ? NamedTextColor.GREEN : NamedTextColor.RED));
                            }
                            double net = am.getNetServerDrift();
                            String driftLabel = net > 0 ? "Good drift" : net < 0 ? "Bad drift" : "Neutral";
                            sender.sendMessage(Component.text(
                                    String.format("Net server drift: %+.1f pts (%s)", net, driftLabel),
                                    net >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
                        }
                    }

                    case "player" -> {
                        if (args.length < 3) {
                            sender.sendMessage(Component.text(
                                    "Usage: /moralityadmin analytics player <name>"));
                            break;
                        }
                        org.bukkit.entity.Player target = Bukkit.getPlayer(args[2]);
                        if (target == null) {
                            sender.sendMessage(Component.text("Player not found (must be online)."));
                            break;
                        }
                        var stats = am.getPlayerStats(target.getUniqueId());
                        sender.sendMessage(Component.text(
                                "=== Morality Analytics — " + target.getName() + " ===",
                                NamedTextColor.GOLD));
                        if (stats.isEmpty()) {
                            sender.sendMessage(Component.text("  No data recorded yet.", NamedTextColor.GRAY));
                        } else {
                            for (AnalyticsManager.ReasonStat s : stats) {
                                boolean positive = s.totalPoints() >= 0;
                                String sign   = positive ? "[+]" : "[-]";
                                String line   = String.format("%-4s %-22s  %5d events   %+10.1f pts   avg %+.1f",
                                        sign, s.reason().displayName(), s.count(), s.totalPoints(), s.avg());
                                sender.sendMessage(Component.text(line,
                                        positive ? NamedTextColor.GREEN : NamedTextColor.RED));
                            }
                            double net = stats.stream().mapToDouble(AnalyticsManager.ReasonStat::totalPoints).sum();
                            sender.sendMessage(Component.text(
                                    String.format("Net: %+.1f pts", net),
                                    net >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
                        }
                    }

                    case "reset" -> {
                        am.reset();
                        sender.sendMessage(Component.text("Analytics data cleared.", NamedTextColor.YELLOW));
                    }

                    case "export" -> {
                        try {
                            java.nio.file.Path out = am.exportCsv();
                            sender.sendMessage(Component.text(
                                    "Exported to: " + out.getFileName(), NamedTextColor.GREEN));
                        } catch (java.io.IOException e) {
                            sender.sendMessage(Component.text(
                                    "Export failed: " + e.getMessage(), NamedTextColor.RED));
                        }
                    }

                    default -> sender.sendMessage(Component.text(
                            "Unknown analytics subcommand. Use: server | player <name> | reset | export"));
                }
                yield true;
            }

            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Use /moralityadmin for a list."));
                yield true;
            }
        };
    }

    /**
     * Maps a tier name to a representative score that places the player firmly inside that tier.
     * Uses a 1-point offset beyond each threshold to guarantee the tier activates.
     */
    private Double tierToScore(String tier) {
        var cfg = plugin.getConfigManager();
        return switch (tier) {
            case "bad4"    -> cfg.getBadThreshold(4) - 1.0;
            case "bad3"    -> cfg.getBadThreshold(3) - 1.0;
            case "bad2"    -> cfg.getBadThreshold(2) - 1.0;
            case "bad1"    -> cfg.getBadThreshold(1) - 1.0;
            case "neutral" -> 0.0;
            case "good1"   -> cfg.getGoodThreshold(1) + 1.0;
            case "good2"   -> cfg.getGoodThreshold(2) + 1.0;
            case "good3"   -> cfg.getGoodThreshold(3) + 1.0;
            case "good4"   -> cfg.getGoodThreshold(4) + 1.0;
            default        -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("moralityengine.admin")) return List.of();

        if (args.length == 1) return filterPrefix(SUBS, args[0]);

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            boolean needsPlayer = Set.of("setscore", "addscore", "resetscore", "settier",
                    "givetotem", "givehead").contains(sub);
            if (needsPlayer) return onlinePlayerNames(args[1]);
            if (sub.equals("list"))
                return filterPrefix(Arrays.asList("bad", "good", "neutral"), args[1]);
            if (sub.equals("analytics"))
                return filterPrefix(Arrays.asList("server", "player", "reset", "export"), args[1]);
        }
        if (args.length == 3) {
            if (sub.equals("settier")) return filterPrefix(TIERS, args[2]);
            if (sub.equals("givetotem")) return filterPrefix(Arrays.asList("dying", "undying"), args[2]);
            if (sub.equals("analytics") && args[1].equalsIgnoreCase("player")) return onlinePlayerNames(args[2]);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
    }

    private List<String> onlinePlayerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix.toLowerCase())) names.add(p.getName());
        }
        return names;
    }
}
