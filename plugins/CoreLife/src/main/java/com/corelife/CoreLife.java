package com.corelife;

import com.corelife.api.CoreLifeAPI;
import com.corelife.api.Phase;
import com.corelife.combat.CombatHudTask;
import com.corelife.combat.CombatTagManager;
import com.corelife.combat.GhostManager;
import com.corelife.commands.AdminCommands;
import com.corelife.commands.LifeTradeCommand;
import com.corelife.commands.LivesCommand;
import com.corelife.data.PlayerDataManager;
import com.corelife.life.LifeManager;
import com.corelife.life.LifeRolloverTask;
import com.corelife.listeners.CombatListener;
import com.corelife.listeners.DeathListener;
import com.corelife.listeners.PhasePortalListener;
import com.corelife.listeners.PlayerJoinQuitListener;
import com.corelife.phase.PhaseManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class CoreLife extends JavaPlugin {

    private static CoreLife instance;

    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private LifeManager lifeManager;
    private CombatTagManager combatTagManager;
    private GhostManager ghostManager;
    private PhaseManager phaseManager;
    private LifeRolloverTask rolloverTask;
    private CombatHudTask combatHudTask;
    private DeathListener deathListener;

    private CoreLifeAPI api;
    private CoreLifeAPI.MoralityProvider moralityProvider;

    /** XP multipliers registered by MoralityEngine. */
    private final Map<UUID, Double> xpMultipliers = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        // Config
        configManager = new ConfigManager(this);
        configManager.load();

        // Data
        playerDataManager = new PlayerDataManager(this);
        playerDataManager.loadAll();

        // Managers (order matters — PhaseManager first, LifeManager needs it)
        phaseManager = new PhaseManager(this);
        lifeManager = new LifeManager(this, playerDataManager, phaseManager);
        combatTagManager = new CombatTagManager(this);
        ghostManager = new GhostManager(this);

        // Listeners
        deathListener = new DeathListener(this);
        getServer().getPluginManager().registerEvents(deathListener, this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PhasePortalListener(this), this);

        // Commands
        AdminCommands adminCommands = new AdminCommands(this);
        Objects.requireNonNull(getCommand("corelife")).setExecutor(adminCommands);
        Objects.requireNonNull(getCommand("corelife")).setTabCompleter(adminCommands);
        Objects.requireNonNull(getCommand("setphase")).setExecutor(adminCommands);
        Objects.requireNonNull(getCommand("setphase")).setTabCompleter(adminCommands);
        Objects.requireNonNull(getCommand("checkseason")).setExecutor(adminCommands);

        LifeTradeCommand tradeCommand = new LifeTradeCommand(this);
        Objects.requireNonNull(getCommand("tradelives")).setExecutor(tradeCommand);
        Objects.requireNonNull(getCommand("tradelives")).setTabCompleter(tradeCommand);

        LivesCommand livesCommand = new LivesCommand(this);
        String livesAlias = configManager.getLivesAliases().get(0);
        var livesCmd = getCommand(livesAlias);
        if (livesCmd != null) {
            livesCmd.setExecutor(livesCommand);
            livesCmd.setTabCompleter(livesCommand);
        }

        // API implementation
        api = new CoreLifeAPIImpl();

        // Rollover task
        rolloverTask = new LifeRolloverTask(this).schedule();

        // Combat HUD task — action bar countdown for tagged players
        int hudTicks = configManager.getPvpTagHudUpdateTicks();
        combatHudTask = new CombatHudTask(this);
        combatHudTask.runTaskTimer(this, hudTicks, hudTicks);

        // Restore ghost villagers that survived a server restart (1-tick delay so all
        // worlds and chunks are fully loaded before we iterate entities)
        getServer().getScheduler().runTaskLater(this, ghostManager::restoreGhosts, 1L);

        // Schedule auto-advance on startup (in case we missed a transition during downtime)
        phaseManager.scheduleAutoAdvance();

        getLogger().info("CoreLife enabled. Phase: " + phaseManager.getCurrentPhase());
    }

    @Override
    public void onDisable() {
        if (rolloverTask != null && !rolloverTask.isCancelled()) rolloverTask.cancel();
        if (combatHudTask != null && !combatHudTask.isCancelled()) combatHudTask.cancel();
        if (playerDataManager != null) playerDataManager.saveAll();
        getLogger().info("CoreLife disabled.");
    }

    public static CoreLife getInstance() { return instance; }

    /** Log a debug message if debug mode is enabled in config. */
    public static void debug(String msg) {
        if (instance != null && instance.configManager != null && instance.configManager.isDebug()) {
            instance.getLogger().info("[DEBUG] " + msg);
        }
    }

    public ConfigManager getConfigManager() { return configManager; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public LifeManager getLifeManager() { return lifeManager; }
    public CombatTagManager getCombatTagManager() { return combatTagManager; }
    public GhostManager getGhostManager() { return ghostManager; }
    public PhaseManager getPhaseManager() { return phaseManager; }
    public DeathListener getDeathListener() { return deathListener; }
    public LifeRolloverTask getRolloverTask() { return rolloverTask; }
    public CoreLifeAPI getAPI() { return api; }
    public CoreLifeAPI.MoralityProvider getMoralityProvider() { return moralityProvider; }

    // ── CoreLifeAPI inner implementation ─────────────────────────────────────

    private class CoreLifeAPIImpl implements CoreLifeAPI {

        @Override
        public int getPlayerLives(UUID playerUuid) {
            return lifeManager.getLives(playerUuid);
        }

        @Override
        public Phase getCurrentPhase() {
            return phaseManager.getCurrentPhase();
        }

        @Override
        public void registerKillXpMultiplier(UUID killerUuid, double multiplier) {
            xpMultipliers.put(killerUuid, multiplier);
            debug("XP multiplier registered: " + killerUuid + " = " + multiplier);
        }

        @Override
        public double getKillXpMultiplier(UUID killerUuid) {
            return xpMultipliers.getOrDefault(killerUuid, 1.0);
        }

        @Override
        public void registerMoralityProvider(MoralityProvider provider) {
            moralityProvider = provider;
        }

        @Override
        public boolean isPlayerCombatTagged(UUID playerUuid) {
            return combatTagManager.isTagged(playerUuid);
        }

        @Override
        public long getCombatTagRemainingSeconds(UUID playerUuid) {
            return combatTagManager.remainingSeconds(playerUuid);
        }
    }
}
