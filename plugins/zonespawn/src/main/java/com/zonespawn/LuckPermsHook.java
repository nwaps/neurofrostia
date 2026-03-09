package com.zonespawn;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;

import java.util.Optional;

public class LuckPermsHook {

    private final ZoneSpawn plugin;
    private final LuckPerms luckPerms;

    public LuckPermsHook(ZoneSpawn plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    /**
     * Returns the first configured zone that the player is a member of,
     * iterating LuckPerms groups in their natural order.
     */
    public ZoneConfig getZoneForPlayer(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] LuckPerms user not loaded for " + player.getName());
            }
            return null;
        }

        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            String groupName = node.getGroupName().toLowerCase();
            ZoneConfig zone = plugin.getConfigManager().getZone(groupName);
            if (zone != null) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[ZoneSpawn] " + player.getName() + " matched zone: " + groupName);
                }
                return zone;
            }
        }

        return null;
    }

    /**
     * Removes all configured zone groups from the player, then assigns the new zone group.
     */
    public void setZone(Player player, String zoneName) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            plugin.getLogger().warning("[ZoneSpawn] Cannot set zone for " + player.getName() + ": LuckPerms user not loaded");
            return;
        }

        // Remove all existing zone groups
        for (ZoneConfig zone : plugin.getConfigManager().getAllZones()) {
            InheritanceNode node = InheritanceNode.builder(zone.getName()).build();
            user.data().remove(node);
        }

        // Add the new zone group
        InheritanceNode newNode = InheritanceNode.builder(zoneName.toLowerCase()).build();
        user.data().add(newNode);

        // Save asynchronously
        luckPerms.getUserManager().saveUser(user);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[ZoneSpawn] Assigned zone '" + zoneName + "' to " + player.getName());
        }
    }

    /**
     * Returns the underlying LuckPerms API instance.
     */
    public LuckPerms getApi() {
        return luckPerms;
    }
}
