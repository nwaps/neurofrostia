package com.corelife.data;

import com.corelife.CoreLife;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerDataManager {

    private final CoreLife plugin;
    private final File dataDir;
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    public PlayerDataManager(CoreLife plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "players");
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    /** Load all player files from disk into cache on startup. */
    public void loadAll() {
        File[] files = dataDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            try {
                UUID uuid = UUID.fromString(name);
                PlayerData data = load(uuid);
                cache.put(uuid, data);
            } catch (IllegalArgumentException ignored) {
                // not a UUID filename
            }
        }
        plugin.getLogger().info("Loaded " + cache.size() + " player data file(s).");
    }

    /** Get player data, loading from disk if not cached. Creates default entry if new player. */
    public PlayerData getData(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    /** Force-get from cache only (returns null if not loaded). */
    public PlayerData getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public Collection<PlayerData> getAllPlayers() {
        return new ArrayList<>(cache.values());
    }

    /** Save a single player's data to disk. */
    public void save(PlayerData data) {
        File file = fileFor(data.getUuid());
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("lives", data.getLives());
        yml.set("deathBanned", data.isDeathBanned());
        yml.set("adminBanned", data.isAdminBanned());
        yml.set("lastLogin", data.getLastLogin());
        yml.set("playtimeSinceRolloverMs", data.getPlaytimeSinceRolloverMs());
        yml.set("missedLastRollover", data.hasMissedLastRollover());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player data for " + data.getUuid(), e);
        }
    }

    /** Save all cached entries. */
    public void saveAll() {
        for (PlayerData data : cache.values()) {
            save(data);
        }
    }

    /** Load or create a PlayerData from disk for the given UUID. */
    private PlayerData load(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) {
            // New player — use starting lives from config
            int startingLives = plugin.getConfigManager().getStartingLives();
            return new PlayerData(uuid, startingLives);
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        int lives = yml.getInt("lives", plugin.getConfigManager().getStartingLives());
        boolean deathBanned = yml.getBoolean("deathBanned", false);
        boolean adminBanned = yml.getBoolean("adminBanned", false);
        long lastLogin = yml.getLong("lastLogin", System.currentTimeMillis());
        long playtime = yml.getLong("playtimeSinceRolloverMs", 0L);
        boolean missedRollover = yml.getBoolean("missedLastRollover", false);
        return new PlayerData(uuid, lives, deathBanned, adminBanned, lastLogin, playtime, missedRollover);
    }

    private File fileFor(UUID uuid) {
        return new File(dataDir, uuid.toString() + ".yml");
    }

    /** Returns all player UUIDs that are currently death-banned (for rollover). */
    public List<PlayerData> getDeathBannedPlayers() {
        List<PlayerData> banned = new ArrayList<>();
        for (PlayerData data : cache.values()) {
            if (data.isDeathBanned()) banned.add(data);
        }
        return banned;
    }
}
