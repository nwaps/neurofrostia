package com.corelife.commands;

import com.corelife.CoreLife;
import com.corelife.api.Phase;
import com.corelife.data.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LifeTradeCommand implements CommandExecutor, TabCompleter {

    private final CoreLife plugin;
    /** Pending last-life confirmations: sender UUID → expiry epoch millis */
    private final Map<UUID, Long> pendingConfirm = new HashMap<>();

    public LifeTradeCommand(CoreLife plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can trade lives."));
            return true;
        }

        if (!player.hasPermission("corelife.trade")) {
            player.sendMessage(Component.text("You don't have permission to trade lives."));
            return true;
        }

        // Trading blocked during End phase
        if (plugin.getPhaseManager().getCurrentPhase() == Phase.END) {
            player.sendMessage(Component.text(plugin.getConfigManager().getTradeDisabledMessage()));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /tradelives <player> <amount>"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || target.equals(player)) {
            player.sendMessage(Component.text("Player not found or you cannot trade with yourself."));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Amount must be a positive integer."));
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(Component.text("Amount must be positive."));
            return true;
        }

        PlayerData senderData = plugin.getPlayerDataManager().getData(player.getUniqueId());
        int senderLives = senderData.getLives();

        // Cannot trade if it would drop sender to 0
        if (senderLives - amount <= 0) {
            // If the result is exactly 0, this would be their last life
            if (senderLives - amount < 0) {
                player.sendMessage(Component.text("You don't have enough lives. You have " + senderLives + " life/lives."));
                return true;
            }
            // Trading last life — requires confirmation
            if (plugin.getConfigManager().isTradeLastLifeConfirm()) {
                if (hasPendingConfirm(player.getUniqueId())) {
                    // Second invocation — confirm the trade
                    clearConfirm(player.getUniqueId());
                    executeTrade(player, target, amount, senderData);
                } else {
                    // First invocation — ask for confirmation
                    registerConfirm(player.getUniqueId());
                    player.sendMessage(Component.text(plugin.getConfigManager().getTradeLastLifeConfirmMessage()));
                }
                return true;
            }
        }

        // Normal trade (sender keeps at least 1 life)
        if (senderLives <= amount) {
            player.sendMessage(Component.text("You must keep at least 1 life. You have " + senderLives + " life/lives."));
            return true;
        }

        executeTrade(player, target, amount, senderData);
        return true;
    }

    private void executeTrade(Player sender, Player target, int amount, PlayerData senderData) {
        CoreLife.debug("Life trade: " + sender.getName() + " → " + target.getName()
                + " amount=" + amount + " senderLivesBefore=" + senderData.getLives());
        plugin.getLifeManager().addLives(target.getUniqueId(), amount);
        plugin.getLifeManager().setLives(sender.getUniqueId(),
                senderData.getLives() - amount);

        int senderLivesAfter = plugin.getLifeManager().getLives(sender.getUniqueId());
        int targetLivesAfter = plugin.getLifeManager().getLives(target.getUniqueId());

        sender.sendMessage(Component.text("You traded " + amount + " life/lives to " + target.getName()
                + ". You now have " + senderLivesAfter + " life/lives."));
        target.sendMessage(Component.text(sender.getName() + " gave you " + amount
                + " life/lives. You now have " + targetLivesAfter + " life/lives."));
    }

    private boolean hasPendingConfirm(UUID uuid) {
        Long expiry = pendingConfirm.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            pendingConfirm.remove(uuid);
            return false;
        }
        return true;
    }

    private void registerConfirm(UUID uuid) {
        int timeoutMs = plugin.getConfigManager().getTradeLastLifeConfirmTimeoutSeconds() * 1000;
        pendingConfirm.put(uuid, System.currentTimeMillis() + timeoutMs);
    }

    private void clearConfirm(UUID uuid) {
        pendingConfirm.remove(uuid);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
