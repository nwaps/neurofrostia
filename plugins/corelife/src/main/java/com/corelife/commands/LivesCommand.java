package com.corelife.commands;

import com.corelife.CoreLife;
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

public class LivesCommand implements CommandExecutor, TabCompleter {

    private final CoreLife plugin;

    public LivesCommand(CoreLife plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Console must specify a player: /lives <player>"));
                return true;
            }
            sendLivesInfo(sender, player.getUniqueId(), player.getName(), true);
            return true;
        }

        // Lookup another player (admin only)
        if (!sender.hasPermission("corelife.admin")) {
            sender.sendMessage(Component.text("You can only check your own lives.", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: " + args[0], NamedTextColor.RED));
            return true;
        }

        sendLivesInfo(sender, target.getUniqueId(), target.getName(), false);
        return true;
    }

    private void sendLivesInfo(CommandSender sender, java.util.UUID uuid, String name, boolean isSelf) {
        boolean banned = plugin.getLifeManager().isDeathBanned(uuid);
        int lives = plugin.getLifeManager().getLives(uuid);
        String prefix = isSelf ? "You have" : name + " has";
        String suffix = (lives == 1 ? " life" : " lives") + " remaining" + (banned ? " (death-banned)" : "") + ".";
        NamedTextColor color = banned ? NamedTextColor.RED : (lives <= 1 ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
        sender.sendMessage(Component.text(prefix + " " + lives + suffix, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("corelife.admin")) return List.of();
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
