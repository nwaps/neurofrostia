package com.zonespawn;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {

    private final ZoneSpawn plugin;

    private final Map<String, ZoneConfig> zones = new HashMap<>();
    private int maxAttempts;
    private int minY;
    private boolean fallbackToGroupSpawn;
    private boolean randomiseOnSelection;
    private String defaultGroup;
    private String zonePickerTitle;
    private String zonePickerReopenCommand;
    private boolean debug;

    // Configurable messages
    private String msgTeleportPlayer;
    private String msgTeleportSender;
    private String msgSetzonePlayer;
    private String msgSetzoneSender;
    private String msgRespawnPlayer;

    public ConfigManager(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        zones.clear();

        ConfigurationSection zonesSection = plugin.getConfig().getConfigurationSection("zones");
        if (zonesSection != null) {
            for (String zoneName : zonesSection.getKeys(false)) {
                ConfigurationSection zoneSection = zonesSection.getConfigurationSection(zoneName);
                if (zoneSection == null) continue;

                int minX = zoneSection.getInt("min-x");
                int maxX = zoneSection.getInt("max-x");
                int minZ = zoneSection.getInt("min-z");
                int maxZ = zoneSection.getInt("max-z");
                String world = zoneSection.getString("world", "world");
                String messageRespawn  = zoneSection.getString("message", null);
                String messageTeleport = zoneSection.getString("message-teleport", null);
                String messageSetzone  = zoneSection.getString("message-setzone", null);
                String label = zoneSection.getString("label", null);

                zones.put(zoneName.toLowerCase(), new ZoneConfig(zoneName.toLowerCase(), minX, maxX, minZ, maxZ, world, messageRespawn, messageTeleport, messageSetzone, label));
            }
        }

        maxAttempts = plugin.getConfig().getInt("max-attempts", 50);
        minY = plugin.getConfig().getInt("min-y", 60);
        fallbackToGroupSpawn = plugin.getConfig().getBoolean("fallback-to-group-spawn", true);
        randomiseOnSelection = plugin.getConfig().getBoolean("randomise-on-selection", true);
        defaultGroup = plugin.getConfig().getString("default-group", "default");
        zonePickerTitle = plugin.getConfig().getString("zone-picker-title", "Choose Your Spawn Zone");
        zonePickerReopenCommand = plugin.getConfig().getString("zone-picker-reopen-command", "deluxemenus open ZonePickerFirstJoin");
        debug = plugin.getConfig().getBoolean("debug", false);

        msgTeleportPlayer = plugin.getConfig().getString("messages.teleport-player", "&aYou have been placed in the &e{zone_name}&a zone!");
        msgTeleportSender = plugin.getConfig().getString("messages.teleport-sender", "&aTeleported &f{player}&a to zone &e{zone_name}&a.");
        msgSetzonePlayer  = plugin.getConfig().getString("messages.setzone-player",  "&aYour spawn zone has been set to &e{zone_name}&a!");
        msgSetzoneSender  = plugin.getConfig().getString("messages.setzone-sender",  "&aZone set to &e{zone_name}&a for &f{player}&a.");
        msgRespawnPlayer  = plugin.getConfig().getString("messages.respawn-player",  "&aYou have respawned in the &e{zone_name}&a zone!");

        if (debug) {
            plugin.getLogger().info("Loaded " + zones.size() + " zones: " + zones.keySet());
        }
    }

    public ZoneConfig getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    public Collection<ZoneConfig> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public boolean hasZone(String name) {
        return zones.containsKey(name.toLowerCase());
    }

    public int getMaxAttempts() { return maxAttempts; }
    public int getMinY() { return minY; }
    public boolean isFallbackToGroupSpawn() { return fallbackToGroupSpawn; }
    public boolean isRandomiseOnSelection() { return randomiseOnSelection; }
    public String getDefaultGroup() { return defaultGroup; }
    public String getZonePickerTitle() { return zonePickerTitle; }
    public String getZonePickerReopenCommand() { return zonePickerReopenCommand; }
    public boolean isDebug() { return debug; }

    // Message getters
    public String getMsgTeleportPlayer() { return msgTeleportPlayer; }
    public String getMsgTeleportSender() { return msgTeleportSender; }
    public String getMsgSetzonePlayer()  { return msgSetzonePlayer; }
    public String getMsgSetzoneSender()  { return msgSetzoneSender; }
    public String getMsgRespawnPlayer()  { return msgRespawnPlayer; }

    /**
     * Resolves {zone_name} and {player} placeholders in a message template,
     * then translates &-colour codes.
     */
    public String format(String template, String zoneName, String playerName) {
        return ChatColor.translateAlternateColorCodes('&',
                template.replace("{zone_name}", zoneName).replace("{player}", playerName));
    }

    /**
     * Formats a player-facing message for a zone action.
     * Uses {@code zoneOverride} when non-null, otherwise falls back to {@code globalTemplate}.
     * Supports {zone_name}, {player}, and {label} placeholders; &colour codes are translated.
     *
     * @param globalTemplate the value from the global messages section
     * @param zone           the zone being acted on (provides {zone_name} and {label})
     * @param zoneOverride   the per-zone message key (e.g. zone.getMessageTeleport()), or null
     * @param playerName     the player's name for {player}
     */
    public String formatPlayerMessage(String globalTemplate, ZoneConfig zone, String zoneOverride, String playerName) {
        String template = zoneOverride != null ? zoneOverride : globalTemplate;
        // Translate the label's own colour codes before inserting it into the surrounding template
        String label = ChatColor.translateAlternateColorCodes('&', zone.getLabel());
        return ChatColor.translateAlternateColorCodes('&',
                template.replace("{zone_name}", zone.getName())
                        .replace("{player}", playerName)
                        .replace("{label}", label));
    }
}
