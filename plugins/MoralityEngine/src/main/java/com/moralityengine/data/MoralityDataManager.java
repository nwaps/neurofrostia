package com.moralityengine.data;

import com.moralityengine.MoralityEngine;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class MoralityDataManager {

    private final MoralityEngine plugin;
    private final File dataDir;
    private final Map<UUID, PlayerMorality> cache = new HashMap<>();

    public MoralityDataManager(MoralityEngine plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "players");
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    public void loadAll() {
        File[] files = dataDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            try {
                UUID uuid = UUID.fromString(name);
                cache.put(uuid, load(uuid));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public PlayerMorality getData(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> new PlayerMorality(u, 0.0));
    }

    public Collection<PlayerMorality> getAllPlayers() {
        return cache.values();
    }

    public void save(PlayerMorality data) {
        File file = new File(dataDir, data.getUuid() + ".yml");
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("score", data.getScore());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save morality data for " + data.getUuid(), e);
        }
    }

    public void saveAll() {
        for (PlayerMorality data : cache.values()) save(data);
    }

    /** Reset all scores to 0 (called at season start). */
    public void resetAll() {
        for (PlayerMorality data : cache.values()) {
            data.setScore(0.0);
            save(data);
        }
    }

    private PlayerMorality load(UUID uuid) {
        File file = new File(dataDir, uuid + ".yml");
        if (!file.exists()) return new PlayerMorality(uuid, 0.0);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        double score = yml.getDouble("score", 0.0);
        return new PlayerMorality(uuid, score);
    }
}
