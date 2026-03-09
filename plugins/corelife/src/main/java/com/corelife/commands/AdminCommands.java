package com.corelife.commands;

import com.corelife.CoreLife;
import com.corelife.api.CoreLifeAPI;
import com.corelife.api.Phase;
import com.corelife.data.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles /corelife, /setphase, and /checkseason commands.
 */
public class AdminCommands implements CommandExecutor, TabCompleter {

    private final CoreLife plugin;
    /** Pending /checkseason confirmations: sender UUID → expiry millis */
    private final Map<UUID, Long> seasonConfirm = new HashMap<>();

    private static final List<String> CORELIFE_SUBS = Arrays.asList(
            "reload", "lives", "setlives", "givelives", "info",
            "tag", "untag", "ban", "unban",
            "rollover", "rolloverinfo", "rolloverplaytime", "rolloverreset",
            "ghostlist", "ghostkill", "phaseinfo", "unlock"
    );

    public AdminCommands(CoreLife plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "corelife" -> handleCoreLife(sender, args);
            case "setphase" -> handleSetPhase(sender, args);
            case "checkseason" -> handleCheckSeason(sender);
            default -> false;
        };
    }

    // ── /corelife ────────────────────────────────────────────────────────────

    private boolean handleCoreLife(CommandSender sender, String[] args) {
        if (!sender.hasPermission("corelife.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("CoreLife v" + plugin.getPluginMeta().getVersion()
                    + " | /corelife <" + String.join("|", CORELIFE_SUBS) + ">"));
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.getConfigManager().load();
                sender.sendMessage(Component.text("CoreLife config reloaded."));
                yield true;
            }
            case "lives" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife lives <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                int lives = plugin.getLifeManager().getLives(target.getUniqueId());
                sender.sendMessage(Component.text((target.getName() != null ? target.getName() : args[1]) + " has " + lives + " life/lives."));
                yield true;
            }
            case "setlives" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /corelife setlives <player> <amount>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                try {
                    int amount = Integer.parseInt(args[2]);
                    plugin.getLifeManager().setLives(target.getUniqueId(), amount);
                    sender.sendMessage(Component.text("Set " + name + "'s lives to " + amount + "."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number."));
                }
                yield true;
            }
            case "givelives" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /corelife givelives <player> <amount>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                try {
                    int amount = Integer.parseInt(args[2]);
                    plugin.getLifeManager().addLives(target.getUniqueId(), amount);
                    int newLives = plugin.getLifeManager().getLives(target.getUniqueId());
                    sender.sendMessage(Component.text("Gave " + amount + " life/lives to " + name
                            + " (now: " + newLives + ")."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number."));
                }
                yield true;
            }
            case "info" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife info <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                UUID uuid = target.getUniqueId();
                PlayerData data = plugin.getPlayerDataManager().getData(uuid);
                long thresholdMs = plugin.getLifeManager().getPlaytimeThresholdMs();
                long playedMs    = data.getPlaytimeSinceRolloverMs();
                if (online != null) playedMs += (System.currentTimeMillis() - data.getLastLogin());

                sender.sendMessage(Component.text("=== CoreLife Info: " + name + " ==="));
                sender.sendMessage(Component.text("Lives: " + data.getLives()));
                sender.sendMessage(Component.text("Death-banned: " + data.isDeathBanned()));
                sender.sendMessage(Component.text("Admin-banned: " + data.isAdminBanned()));
                sender.sendMessage(Component.text("Combat-tagged: " + plugin.getCombatTagManager().isTagged(uuid)
                        + " (" + plugin.getCombatTagManager().remainingSeconds(uuid) + "s remaining)"));
                sender.sendMessage(Component.text("Has active ghost: " + plugin.getGhostManager().hasActiveGhost(uuid)));
                if (thresholdMs > 0) {
                    sender.sendMessage(Component.text("Rollover playtime: " + (playedMs / 60_000L)
                            + " / " + (thresholdMs / 60_000L) + " min"));
                }
                sender.sendMessage(Component.text("Missed last rollover: " + data.hasMissedLastRollover()));
                yield true;
            }

            // ── Debug / test subcommands ──────────────────────────────────

            case "tag" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife tag <player>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                plugin.getCombatTagManager().tag(target.getUniqueId());
                int secs = plugin.getConfigManager().getTagDurationSeconds();
                sender.sendMessage(Component.text("Combat-tagged " + target.getName() + " for " + secs + "s."));
                yield true;
            }
            case "untag" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife untag <player>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                plugin.getCombatTagManager().removeTag(target.getUniqueId());
                sender.sendMessage(Component.text("Removed combat tag from " + target.getName() + "."));
                yield true;
            }
            case "ban" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife ban <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                PlayerData data = plugin.getPlayerDataManager().getData(target.getUniqueId());
                plugin.getLifeManager().setLives(target.getUniqueId(), 0);
                data.setDeathBanned(true);
                plugin.getPlayerDataManager().save(data);
                if (online != null) {
                    String banMsg = plugin.getLifeManager().buildBanMessage();
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> { if (online.isOnline()) online.kick(Component.text(banMsg)); }, 1L);
                }
                sender.sendMessage(Component.text("Death-banned " + name + " (lives set to 0)."));
                yield true;
            }
            case "unban" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife unban <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                PlayerData data = plugin.getPlayerDataManager().getData(target.getUniqueId());
                if (!data.isDeathBanned()) {
                    sender.sendMessage(Component.text(name + " is not death-banned."));
                    yield true;
                }
                data.setDeathBanned(false);
                if (data.getLives() == 0) data.setLives(1);
                plugin.getPlayerDataManager().save(data);
                sender.sendMessage(Component.text("Removed death ban from " + name
                        + " (lives: " + data.getLives() + ")."));
                yield true;
            }
            case "rollover" -> {
                plugin.getLifeManager().applyRollover();
                sender.sendMessage(Component.text("Life rollover applied manually."));
                yield true;
            }
            case "rolloverinfo" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife rolloverinfo <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                UUID uuid = target.getUniqueId();
                PlayerData data = plugin.getPlayerDataManager().getData(uuid);

                long thresholdMs = plugin.getLifeManager().getPlaytimeThresholdMs();
                long playedMs = data.getPlaytimeSinceRolloverMs();
                // Add current online session if the player is logged in
                if (online != null) playedMs += (System.currentTimeMillis() - data.getLastLogin());
                long playedMin   = playedMs / 60_000L;
                long requiredMin = thresholdMs / 60_000L;

                int rolloverCap  = plugin.getConfigManager().getRolloverMaxLives();
                int globalCap    = plugin.getConfigManager().getMaxLives();
                String capStr    = rolloverCap > 0
                        ? rolloverCap + " (rollover) / " + (globalCap > 0 ? globalCap : "∞") + " (global)"
                        : (globalCap > 0 ? globalCap + " (shared cap)" : "∞ (no cap)");

                boolean deathBanned = data.isDeathBanned() || data.getLives() == 0;
                boolean qualifies = thresholdMs == 0 || playedMs >= thresholdMs;
                boolean atCap     = rolloverCap > 0 ? data.getLives() >= rolloverCap
                                  : (globalCap > 0 && data.getLives() >= globalCap);

                sender.sendMessage(Component.text("=== Rollover Info: " + name + " ==="));
                sender.sendMessage(Component.text("Lives: " + data.getLives() + "  |  Cap: " + capStr));
                if (thresholdMs > 0) {
                    sender.sendMessage(Component.text("Playtime this period: " + playedMin + " / " + requiredMin + " min"));
                } else {
                    sender.sendMessage(Component.text("Playtime gate: disabled"));
                }
                // Death-banned players (lives == 0) are always restored unconditionally — no playtime gate.
                String qualifiesStr;
                if (deathBanned) {
                    qualifiesStr = "YES (death-banned — will be restored regardless of playtime)";
                } else if (!atCap && qualifies) {
                    qualifiesStr = "YES";
                } else {
                    qualifiesStr = "NO"
                            + (atCap ? " (at rollover cap)" : "")
                            + (!atCap ? " (needs " + (requiredMin - playedMin) + " more min)" : "");
                }
                sender.sendMessage(Component.text("Qualifies for next rollover: " + qualifiesStr));
                sender.sendMessage(Component.text("Missed last rollover flag: " + data.hasMissedLastRollover()));
                sender.sendMessage(Component.text("Next rollover: " + plugin.getLifeManager().formatNextRollover()));
                yield true;
            }
            case "rolloverplaytime" -> {
                if (args.length < 3) { sender.sendMessage(Component.text("Usage: /corelife rolloverplaytime <player> <minutes>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                try {
                    int minutes = Integer.parseInt(args[2]);
                    PlayerData data = plugin.getPlayerDataManager().getData(target.getUniqueId());
                    data.resetPlaytimeSinceRolloverMs();
                    data.addPlaytimeSinceRolloverMs((long) minutes * 60_000L);
                    plugin.getPlayerDataManager().save(data);
                    sender.sendMessage(Component.text("Set " + name + "'s rollover playtime to " + minutes + " min."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number."));
                }
                yield true;
            }
            case "rolloverreset" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife rolloverreset <player>")); yield true; }
                Player online = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
                if (online == null && !target.hasPlayedBefore()) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                String name = target.getName() != null ? target.getName() : args[1];
                PlayerData data = plugin.getPlayerDataManager().getData(target.getUniqueId());
                data.resetPlaytimeSinceRolloverMs();
                data.setMissedLastRollover(false);
                plugin.getPlayerDataManager().save(data);
                sender.sendMessage(Component.text("Reset rollover playtime counter and missed-rollover flag for " + name + "."));
                yield true;
            }
            case "ghostlist" -> {
                Set<UUID> owners = plugin.getGhostManager().getActiveGhostOwners();
                if (owners.isEmpty()) {
                    sender.sendMessage(Component.text("No active ghosts."));
                    yield true;
                }
                sender.sendMessage(Component.text("Active ghosts (" + owners.size() + "):"));
                for (UUID uuid : owners) {
                    String name = Bukkit.getOfflinePlayer(uuid).getName();
                    if (name == null) name = uuid.toString();
                    sender.sendMessage(Component.text("  - " + name));
                }
                yield true;
            }
            case "ghostkill" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /corelife ghostkill <player>")); yield true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Player not found.")); yield true; }
                if (!plugin.getGhostManager().hasActiveGhost(target.getUniqueId())) {
                    sender.sendMessage(Component.text(target.getName() + " has no active ghost."));
                    yield true;
                }
                plugin.getGhostManager().despawnGhost(target.getUniqueId());
                sender.sendMessage(Component.text("Despawned ghost for " + target.getName() + "."));
                yield true;
            }
            case "phaseinfo" -> {
                Phase phase = plugin.getPhaseManager().getCurrentPhase();
                long startMs = plugin.getPhaseManager().getPhaseStartEpochMillis();
                long elapsedMs = System.currentTimeMillis() - startMs;
                long elapsedDays = elapsedMs / 86_400_000L;
                long elapsedHours = (elapsedMs % 86_400_000L) / 3_600_000L;

                sender.sendMessage(Component.text("=== Phase Info ==="));
                sender.sendMessage(Component.text("Current phase: " + phase));
                sender.sendMessage(Component.text("Phase active for: " + elapsedDays + "d " + elapsedHours + "h"));
                sender.sendMessage(Component.text("Server locked: " + plugin.getPhaseManager().isServerLocked()));

                if (phase == Phase.OVERWORLD && plugin.getConfigManager().isAutoAdvance()) {
                    long durationMs = plugin.getConfigManager().getOverworldDurationDays() * 86_400_000L;
                    long remainingMs = Math.max(0, durationMs - elapsedMs);
                    sender.sendMessage(Component.text("Auto-advance to NETHER in: "
                            + remainingMs / 86_400_000L + "d "
                            + (remainingMs % 86_400_000L) / 3_600_000L + "h"));
                } else if (phase == Phase.NETHER) {
                    long durationMs = plugin.getConfigManager().getNetherDurationDays() * 86_400_000L;
                    long remainingMs = Math.max(0, durationMs - elapsedMs);
                    sender.sendMessage(Component.text("END gate available in: "
                            + remainingMs / 86_400_000L + "d "
                            + (remainingMs % 86_400_000L) / 3_600_000L + "h"));
                } else if (phase == Phase.END) {
                    sender.sendMessage(Component.text("End phase is the terminal phase — no auto-advance."));
                }
                yield true;
            }
            case "unlock" -> {
                if (!plugin.getPhaseManager().isServerLocked()) {
                    sender.sendMessage(Component.text("Server is not locked."));
                    yield true;
                }
                plugin.getPhaseManager().unlockServer();
                sender.sendMessage(Component.text("Server lock removed. New connections are now accepted."));
                yield true;
            }

            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Use /corelife for a list."));
                yield true;
            }
        };
    }

    // ── /setphase ────────────────────────────────────────────────────────────

    private boolean handleSetPhase(CommandSender sender, String[] args) {
        if (!sender.hasPermission("corelife.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /setphase <OVERWORLD|NETHER|END>"));
            return true;
        }
        Phase newPhase;
        try {
            newPhase = Phase.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Invalid phase. Use OVERWORLD, NETHER, or END."));
            return true;
        }
        plugin.getPhaseManager().setPhase(newPhase);
        sender.sendMessage(Component.text("Phase set to " + newPhase + "."));
        return true;
    }

    // ── /checkseason ─────────────────────────────────────────────────────────

    private boolean handleCheckSeason(CommandSender sender) {
        if (!sender.hasPermission("corelife.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }

        long windowMs = (long) plugin.getConfigManager().getActivePlayerWindowDays() * 24 * 3600 * 1000;
        long cutoff = System.currentTimeMillis() - windowMs;

        List<PlayerData> activePlayers = plugin.getPlayerDataManager().getAllPlayers().stream()
                .filter(pd -> pd.getLives() > 0)
                .filter(pd -> pd.getLastLogin() >= cutoff)
                .toList();

        CoreLifeAPI.MoralityProvider mp = plugin.getMoralityProvider();
        long badCount = (mp == null) ? 0
                : activePlayers.stream().filter(pd -> mp.isBadMorality(pd.getUuid())).count();

        double ratio = activePlayers.isEmpty() ? 0 : (double) badCount / activePlayers.size();
        double threshold = plugin.getConfigManager().getSeasonEndThreshold();

        sender.sendMessage(Component.text(String.format(
                "Active players: %d | Bad morality: %d | Ratio: %.1f%% | Threshold: %.0f%%",
                activePlayers.size(), badCount, ratio * 100, threshold * 100)));

        if (ratio < threshold) {
            sender.sendMessage(Component.text("Season end threshold NOT met. Ratio must be >= " + (int)(threshold * 100) + "%."));
            return true;
        }

        // Threshold met — require confirmation
        UUID senderUuid = (sender instanceof Player p) ? p.getUniqueId() : UUID.nameUUIDFromBytes("console".getBytes());
        if (hasPendingSeasonConfirm(senderUuid)) {
            clearSeasonConfirm(senderUuid);
            plugin.getPhaseManager().beginWindDown();
            sender.sendMessage(Component.text("Season wind-down initiated."));
        } else {
            registerSeasonConfirm(senderUuid);
            sender.sendMessage(Component.text(
                    "Season end threshold MET. Run /checkseason again within 30 seconds to confirm wind-down."));
        }
        return true;
    }

    private boolean hasPendingSeasonConfirm(UUID uuid) {
        Long expiry = seasonConfirm.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) { seasonConfirm.remove(uuid); return false; }
        return true;
    }

    private void registerSeasonConfirm(UUID uuid) {
        seasonConfirm.put(uuid, System.currentTimeMillis() + 30_000L);
    }

    private void clearSeasonConfirm(UUID uuid) {
        seasonConfirm.remove(uuid);
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("corelife.admin")) return List.of();

        return switch (command.getName().toLowerCase()) {
            case "corelife" -> {
                if (args.length == 1)
                    yield filterPrefix(CORELIFE_SUBS, args[0]);
                if (args.length == 2) {
                    String sub = args[0].toLowerCase();
                    boolean needsPlayer = Set.of("lives", "setlives", "givelives", "info",
                            "tag", "untag", "ban", "unban", "ghostkill",
                            "rolloverinfo", "rolloverplaytime", "rolloverreset").contains(sub);
                    yield needsPlayer ? onlinePlayerNames(args[1]) : List.of();
                }
                yield List.of();
            }
            case "setphase" -> {
                if (args.length == 1)
                    yield filterPrefix(Arrays.asList("OVERWORLD", "NETHER", "END"), args[0]);
                yield List.of();
            }
            default -> List.of();
        };
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
