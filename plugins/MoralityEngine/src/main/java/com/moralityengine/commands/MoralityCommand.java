package com.moralityengine.commands;

import com.moralityengine.MoralityEngine;
import com.moralityengine.MoralityTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MoralityCommand implements CommandExecutor, TabCompleter {

    private final MoralityEngine plugin;

    public MoralityCommand(MoralityEngine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Self lookup
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Console must specify a player: /morality <player>"));
                return true;
            }
            sendReadout(sender, player.getUniqueId(), player.getName());
            return true;
        }

        // Admin lookup of another player
        if (!sender.hasPermission("moralityengine.admin")) {
            sender.sendMessage(Component.text("You can only check your own morality."));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: " + args[0]));
            return true;
        }

        sendReadout(sender, target.getUniqueId(), target.getName());
        return true;
    }

    private void sendReadout(CommandSender sender, UUID uuid, String name) {
        MoralityTier tier = plugin.getMoralityManager().getTier(uuid);
        double score = plugin.getMoralityManager().getScore(uuid);

        sender.sendMessage(Component.text("═══════ Morality ═══════", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(name + " — ", NamedTextColor.GRAY)
                .append(Component.text(tier.toString(), tier.isBad() ? NamedTextColor.RED
                        : tier.isGood() ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Score: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("%.2f", score), NamedTextColor.WHITE)));

        if (!tier.isBad() || tier.getLevel() < 4) {
            double distBad = plugin.getMoralityManager().distanceToNextBadTier(uuid);
            if (distBad > 0)
                sender.sendMessage(Component.text(String.format("%.0f points from Bad %d",
                        distBad, tier.isBad() ? tier.getLevel() + 1 : 1), NamedTextColor.RED));
        }
        if (!tier.isGood() || tier.getLevel() < 4) {
            double distGood = plugin.getMoralityManager().distanceToNextGoodTier(uuid);
            if (distGood > 0)
                sender.sendMessage(Component.text(String.format("%.0f points from Good %d",
                        distGood, tier.isGood() ? tier.getLevel() + 1 : 1), NamedTextColor.GREEN));
        }
        sender.sendMessage(Component.text("══════════════════════", NamedTextColor.GOLD));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("moralityengine.admin")) return List.of();
        List<String> names = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) names.add(p.getName());
            }
        }
        return names;
    }
}
