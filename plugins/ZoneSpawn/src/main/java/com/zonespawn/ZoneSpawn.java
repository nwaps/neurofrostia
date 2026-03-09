package com.zonespawn;

import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class ZoneSpawn extends JavaPlugin {

    private ConfigManager configManager;
    private SafeLocationFinder safeLocationFinder;
    private LuckPermsHook luckPermsHook;
    private InvulnerabilityTracker invulnerabilityTracker;

    @Override
    public void onEnable() {
        // Load config
        configManager = new ConfigManager(this);
        configManager.load();

        // Hook into LuckPerms
        LuckPerms luckPermsApi = resolveLuckPerms();
        if (luckPermsApi == null) {
            getLogger().severe("LuckPerms not found! ZoneSpawn requires LuckPerms to function. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        luckPermsHook = new LuckPermsHook(this, luckPermsApi);

        // Safe location finder
        safeLocationFinder = new SafeLocationFinder(this);

        // Invulnerability tracker (persists across restarts)
        invulnerabilityTracker = new InvulnerabilityTracker(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new RespawnListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryCloseListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);

        // Register command
        ZoneSpawnCommand commandHandler = new ZoneSpawnCommand(this);
        Objects.requireNonNull(getCommand("zonespawn")).setExecutor(commandHandler);
        Objects.requireNonNull(getCommand("zonespawn")).setTabCompleter(commandHandler);

        getLogger().info("ZoneSpawn enabled. Loaded " + configManager.getAllZones().size() + " zone(s).");
    }

    @Override
    public void onDisable() {
        getLogger().info("ZoneSpawn disabled.");
    }

    private LuckPerms resolveLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider =
                getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) return null;
        return provider.getProvider();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public SafeLocationFinder getSafeLocationFinder() {
        return safeLocationFinder;
    }

    public LuckPermsHook getLuckPermsHook() {
        return luckPermsHook;
    }

    public InvulnerabilityTracker getInvulnerabilityTracker() {
        return invulnerabilityTracker;
    }
}
