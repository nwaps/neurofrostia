package com.zonespawn;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ZoneSpawnCommand implements CommandExecutor, TabCompleter {

    private final ZoneSpawn plugin;

    public ZoneSpawnCommand(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /zonespawn <reload|teleport|setzone|info|listinvulnerable>");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "teleport", "tp" -> handleTeleport(sender, args);
            case "setzone", "sz" -> handleSetzone(sender, args);
            case "info" -> handleInfo(sender, args);
            case "listinvulnerable", "li" -> handleListInvulnerable(sender);
            default -> {
                sender.sendMessage("§cUnknown subcommand. Usage: /zonespawn <reload|teleport|setzone|info|listinvulnerable>");
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("zonespawn.reload")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        plugin.getConfigManager().load();
        sender.sendMessage("§aZoneSpawn config reloaded.");
        return true;
    }

    private boolean handleTeleport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zonespawn.teleport")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§eUsage: /zonespawn teleport <player> <zone>");
            return true;
        }

        String playerName = args[1];
        String zoneName = args[2].toLowerCase();

        if (!plugin.getConfigManager().hasZone(zoneName)) {
            sender.sendMessage("§cUnknown zone: §f" + zoneName);
            return true;
        }

        Player target = plugin.getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: §f" + playerName);
            return true;
        }

        ZoneConfig zone = plugin.getConfigManager().getZone(zoneName);

        // Assign LuckPerms group (clears old zone groups first)
        plugin.getLuckPermsHook().setZone(target, zoneName);

        // Find safe location and teleport
        if (plugin.getConfigManager().isRandomiseOnSelection()) {
            plugin.getSafeLocationFinder().findSafeLocationAsync(zone, location -> {
                if (location == null) {
                    sender.sendMessage("§cCould not find a safe location in §f" + zoneName
                            + "§c after §f" + plugin.getConfigManager().getMaxAttempts() + "§c attempts.");
                    return;
                }

                target.teleport(location);
                plugin.getInvulnerabilityTracker().setInvulnerable(target, false);
                target.sendMessage(plugin.getConfigManager().formatPlayerMessage(plugin.getConfigManager().getMsgTeleportPlayer(), zone, zone.getMessageTeleport(), target.getName()));
                sender.sendMessage(plugin.getConfigManager().format(plugin.getConfigManager().getMsgTeleportSender(), zoneName, target.getName()));
            });
        } else {
            sender.sendMessage("§eLuckPerms group assigned. Randomise-on-selection is disabled; no teleport performed.");
        }

        return true;
    }

    private boolean handleSetzone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zonespawn.setzone")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§eUsage: /zonespawn setzone <player> <zone>");
            return true;
        }

        String playerName = args[1];
        String zoneName = args[2].toLowerCase();

        if (!plugin.getConfigManager().hasZone(zoneName)) {
            sender.sendMessage("§cUnknown zone: §f" + zoneName);
            return true;
        }

        Player target = plugin.getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: §f" + playerName);
            return true;
        }

        ZoneConfig zone = plugin.getConfigManager().getZone(zoneName);

        // Assign LuckPerms group (clears old zone groups first)
        plugin.getLuckPermsHook().setZone(target, zoneName);

        // Remove invulnerability granted when zone picker opened (no-op if not set)
        plugin.getInvulnerabilityTracker().setInvulnerable(target, false);

        target.sendMessage(plugin.getConfigManager().formatPlayerMessage(plugin.getConfigManager().getMsgSetzonePlayer(), zone, zone.getMessageSetzone(), target.getName()));
        sender.sendMessage(plugin.getConfigManager().format(plugin.getConfigManager().getMsgSetzoneSender(), zoneName, target.getName()));

        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zonespawn.info")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: §f" + args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§eUsage: /zonespawn info <player>");
            return true;
        }

        ZoneConfig zone = plugin.getLuckPermsHook().getZoneForPlayer(target);

        if (zone == null) {
            sender.sendMessage("§e" + target.getName() + " §fhas no zone assigned.");
            return true;
        }

        sender.sendMessage("§6--- ZoneSpawn Info: §f" + target.getName() + " §6---");
        sender.sendMessage("§eZone: §f" + zone.getName());
        sender.sendMessage("§eWorld: §f" + zone.getWorld());
        sender.sendMessage("§eX range: §f" + zone.getMinX() + " §7to §f" + zone.getMaxX());
        sender.sendMessage("§eZ range: §f" + zone.getMinZ() + " §7to §f" + zone.getMaxZ());

        return true;
    }

    private boolean handleListInvulnerable(CommandSender sender) {
        if (!sender.hasPermission("zonespawn.info")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        Set<UUID> tracked = plugin.getInvulnerabilityTracker().getTracked();

        if (tracked.isEmpty()) {
            sender.sendMessage("§aNo players are currently tracked as invulnerable by ZoneSpawn.");
            return true;
        }

        sender.sendMessage("§6--- ZoneSpawn Invulnerable Players (" + tracked.size() + ") ---");

        for (UUID uuid : tracked) {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                sender.sendMessage("§a[ONLINE] §f" + online.getName() + " §7(" + uuid + ")");
            } else {
                OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
                String name = offline.getName() != null ? offline.getName() : "§oUnknown§r";
                sender.sendMessage("§7[OFFLINE] §f" + name + " §7(" + uuid + ")");
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subs = List.of("reload", "teleport", "setzone", "info", "listinvulnerable");
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("setzone") || args[0].equalsIgnoreCase("sz") || args[0].equalsIgnoreCase("info"))) {
            String partial = args[1].toLowerCase();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("setzone") || args[0].equalsIgnoreCase("sz"))) {
            String partial = args[2].toLowerCase();
            for (ZoneConfig zone : plugin.getConfigManager().getAllZones()) {
                if (zone.getName().startsWith(partial)) {
                    completions.add(zone.getName());
                }
            }
        }

        return completions;
    }
}
