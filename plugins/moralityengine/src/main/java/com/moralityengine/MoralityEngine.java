package com.moralityengine;

import com.moralityengine.analytics.AnalyticsManager;
import com.moralityengine.commands.MoralityAdminCommand;
import com.moralityengine.commands.MoralityCommand;
import com.moralityengine.corelife.CoreLifeHook;
import com.moralityengine.data.MoralityDataManager;
import com.moralityengine.hud.FrostVignetteManager;
import com.moralityengine.hud.LocatorBarManager;
import com.moralityengine.hud.MoralityHudManager;
import com.moralityengine.items.MoralityCompassManager;
import com.moralityengine.items.TotemOfDyingManager;
import com.moralityengine.items.TotemOfUndyingManager;
import com.moralityengine.perks.MobBehaviorManager;
import com.moralityengine.perks.PerkManager;
import com.moralityengine.perks.VillageGolemManager;
import com.moralityengine.perks.VillagerReactionManager;
import com.moralityengine.perks.WraithManager;
import com.moralityengine.perks.ZombieShearManager;
import com.moralityengine.progression.ProgressionManager;
import com.moralityengine.progression.ProximityTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Optional;

public class MoralityEngine extends JavaPlugin {

    private static MoralityEngine instance;

    private AnalyticsManager analyticsManager;
    private ConfigManager configManager;
    private MoralityDataManager moralityDataManager;
    private MoralityManager moralityManager;
    private PerkManager perkManager;
    private CoreLifeHook coreLifeHook;
    private ProgressionManager progressionManager;
    private MobBehaviorManager mobBehaviorManager;
    private VillagerReactionManager villagerReactionManager;
    private WraithManager wraithManager;
    private ZombieShearManager zombieShearManager;
    private TotemOfDyingManager totemOfDyingManager;
    private TotemOfUndyingManager totemOfUndyingManager;
    private MoralityCompassManager moralityCompassManager;
    private VillageGolemManager villageGolemManager;
    private LocatorBarManager locatorBarManager;
    private FrostVignetteManager frostVignetteManager;
    private MoralityHudManager moralityHudManager;

    @Override
    public void onEnable() {
        instance = this;

        // Config
        configManager = new ConfigManager(this);
        configManager.load();

        // Analytics (loaded before MoralityManager so the field is ready when managers start)
        analyticsManager = new AnalyticsManager(this);
        analyticsManager.load();

        // Data
        moralityDataManager = new MoralityDataManager(this);
        moralityDataManager.loadAll();

        // Managers (PerkManager depends on MoralityManager, so init order matters)
        perkManager = new PerkManager(this);
        moralityManager = new MoralityManager(this, moralityDataManager);

        // CoreLife hook (soft dependency — gracefully handles absence)
        coreLifeHook = new CoreLifeHook(this);
        getServer().getPluginManager().registerEvents(coreLifeHook, this);

        // Progression
        progressionManager = new ProgressionManager(this);
        getServer().getPluginManager().registerEvents(progressionManager, this);
        new ProximityTask(this).start();

        // Perks and mob behaviour
        mobBehaviorManager = new MobBehaviorManager(this);
        getServer().getPluginManager().registerEvents(mobBehaviorManager, this);
        villagerReactionManager = new VillagerReactionManager(this);
        getServer().getPluginManager().registerEvents(villagerReactionManager, this);
        wraithManager = new WraithManager(this);
        getServer().getPluginManager().registerEvents(wraithManager, this);
        zombieShearManager = new ZombieShearManager(this);
        getServer().getPluginManager().registerEvents(zombieShearManager, this);

        // Items
        totemOfDyingManager = new TotemOfDyingManager(this);
        getServer().getPluginManager().registerEvents(totemOfDyingManager, this);
        totemOfUndyingManager = new TotemOfUndyingManager(this);
        getServer().getPluginManager().registerEvents(totemOfUndyingManager, this);
        moralityCompassManager = new MoralityCompassManager(this);
        getServer().getPluginManager().registerEvents(moralityCompassManager, this);

        // Village golem enhancements
        villageGolemManager = new VillageGolemManager(this);
        getServer().getPluginManager().registerEvents(villageGolemManager, this);
        villageGolemManager.reattachAfterRestart();

        // HUD
        locatorBarManager = new LocatorBarManager(this);
        frostVignetteManager = new FrostVignetteManager(this);
        moralityHudManager = new MoralityHudManager(this);
        getServer().getPluginManager().registerEvents(moralityHudManager, this);

        // Commands — use primary alias from config
        String primaryAlias = configManager.getMoralityAliases().get(0);
        MoralityCommand moralityCmd = new MoralityCommand(this);
        var cmd = getCommand(primaryAlias);
        if (cmd != null) {
            cmd.setExecutor(moralityCmd);
            cmd.setTabCompleter(moralityCmd);
        }

        MoralityAdminCommand adminCmd = new MoralityAdminCommand(this);
        var adminCmdReg = getCommand("moralityadmin");
        if (adminCmdReg != null) {
            adminCmdReg.setExecutor(adminCmd);
            adminCmdReg.setTabCompleter(adminCmd);
        }

        // Update XP multipliers for all already-online players (e.g. after /reload)
        getServer().getOnlinePlayers().forEach(p ->
                perkManager.updateXpMultiplier(p.getUniqueId()));

        getLogger().info("MoralityEngine enabled.");
    }

    @Override
    public void onDisable() {
        if (moralityDataManager != null) moralityDataManager.saveAll();
        if (locatorBarManager != null) locatorBarManager.removeAll();
        getLogger().info("MoralityEngine disabled.");
    }

    public static MoralityEngine getInstance() { return instance; }

    /** Log a debug message if debug mode is enabled in config. */
    public static void debug(String msg) {
        if (instance != null && instance.configManager != null && instance.configManager.isDebug()) {
            instance.getLogger().info("[DEBUG] " + msg);
        }
    }

    public AnalyticsManager getAnalyticsManager() { return analyticsManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public MoralityDataManager getMoralityDataManager() { return moralityDataManager; }
    public MoralityManager getMoralityManager() { return moralityManager; }
    public PerkManager getPerkManager() { return perkManager; }
    public Optional<com.corelife.api.CoreLifeAPI> getCoreLifeHook() { return coreLifeHook.get(); }
    public ProgressionManager getProgressionManager() { return progressionManager; }
    public MobBehaviorManager getMobBehaviorManager() { return mobBehaviorManager; }
    public VillagerReactionManager getVillagerReactionManager() { return villagerReactionManager; }
    public WraithManager getWraithManager() { return wraithManager; }
    public TotemOfDyingManager getTotemOfDyingManager() { return totemOfDyingManager; }
    public TotemOfUndyingManager getTotemOfUndyingManager() { return totemOfUndyingManager; }
    public MoralityCompassManager getMoralityCompassManager() { return moralityCompassManager; }
    public VillageGolemManager getVillageGolemManager() { return villageGolemManager; }
    public LocatorBarManager getLocatorBarManager() { return locatorBarManager; }
    public FrostVignetteManager getFrostVignetteManager() { return frostVignetteManager; }
    public MoralityHudManager getMoralityHudManager() { return moralityHudManager; }
}
