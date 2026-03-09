package com.corelife;

import java.util.List;

public class ConfigManager {

    private final CoreLife plugin;

    private boolean debug;

    // Lives
    private int startingLives;
    private int maxLives;
    private boolean pvpOnly;
    private int rolloverIntervalHours;
    private int rolloverMaxLives;
    private int rolloverMinPlayMinutes;
    private int rolloverMinPlayPercent;
    private String rolloverStatusMessage;
    private String rolloverMissedMessage;
    private String rolloverSkippedMessage;
    private String banMessage;
    private String banMessageEndPhase;
    private boolean allowSpectateOnBan;
    private String tradeDisabledMessage;
    private boolean tradeLastLifeConfirm;
    private String tradeLastLifeConfirmMessage;
    private int tradeLastLifeConfirmTimeoutSeconds;

    // XP
    private int victimLossBase;
    private int killerRewardBase;

    // Enderchest
    private boolean dropEnderchestOnPvpDeath;

    // Combat
    private int tagDurationSeconds;
    private int ghostDurationSeconds;
    private boolean ghostEnabled;
    private boolean pvpTagHudEnabled;
    private String  pvpTagHudMessage;
    private String  pvpTagHudFlashMessage;
    private boolean pvpTagHudFlash;
    private int     pvpTagHudUpdateTicks;

    // Commands
    private List<String> livesAliases;

    // Phases
    private int overworldDurationDays;
    private int netherDurationDays;
    private boolean autoAdvance;
    private String broadcastNetherOpen;
    private String broadcastEndOpen;
    private double seasonEndThreshold;
    private int activePlayerWindowDays;
    private int windDownDays;
    private String windDownBroadcast;
    private String lockBroadcast;
    private boolean joinBroadcastEnabled;
    private String joinBroadcastOverworld;
    private String joinBroadcastNether;
    private String joinBroadcastEnd;
    private String portalBlockedNether;
    private String portalBlockedEnd;

    public ConfigManager(CoreLife plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        debug = plugin.getConfig().getBoolean("debug", false);

        startingLives = plugin.getConfig().getInt("lives.starting-lives", 1);
        maxLives = plugin.getConfig().getInt("lives.max-lives", 0);
        pvpOnly = plugin.getConfig().getBoolean("lives.pvp-only", true);
        rolloverIntervalHours = plugin.getConfig().getInt("lives.rollover-interval-hours", 168);
        rolloverMaxLives = plugin.getConfig().getInt("lives.rollover-max-lives", 0);
        rolloverMinPlayMinutes = plugin.getConfig().getInt("lives.rollover-min-play-minutes", 0);
        rolloverMinPlayPercent = plugin.getConfig().getInt("lives.rollover-min-play-percent", 0);
        rolloverStatusMessage = plugin.getConfig().getString("lives.rollover-status-message",
                "&7Rollover progress: &e{played}&7/&e{required} &7min played. Next rollover: &e{next_rollover}&7.");
        rolloverMissedMessage = plugin.getConfig().getString("lives.rollover-missed-message",
                "&cYou missed the last life rollover — you needed &e{required} &cmin of playtime. Next rollover: &e{next_rollover}&c.");
        rolloverSkippedMessage = plugin.getConfig().getString("lives.rollover-skipped-message",
                "&cYou didn't earn a rollover life this period (needed &e{required} &cmin of playtime). Next rollover: &e{next_rollover}&c.");
        banMessage = plugin.getConfig().getString("lives.ban-message", "You have no lives remaining.");
        banMessageEndPhase = plugin.getConfig().getString("lives.ban-message-end-phase", "The season is ending.");
        allowSpectateOnBan = plugin.getConfig().getBoolean("lives.allow-spectate-on-ban", false);
        tradeDisabledMessage = plugin.getConfig().getString("lives.trade-disabled-message", "Life trading is closed.");
        tradeLastLifeConfirm = plugin.getConfig().getBoolean("lives.trade-last-life-confirm", true);
        tradeLastLifeConfirmMessage = plugin.getConfig().getString("lives.trade-last-life-confirm-message",
                "You are about to trade your last life. Run the command again to confirm.");
        tradeLastLifeConfirmTimeoutSeconds = plugin.getConfig().getInt("lives.trade-last-life-confirm-timeout-seconds", 30);

        victimLossBase = plugin.getConfig().getInt("xp.victim-loss-base", 50);
        killerRewardBase = plugin.getConfig().getInt("xp.killer-reward-base", 5345);

        dropEnderchestOnPvpDeath = plugin.getConfig().getBoolean("enderchest.drop-on-pvp-death", true);

        tagDurationSeconds = plugin.getConfig().getInt("combat.tag-duration-seconds", 15);
        ghostDurationSeconds = plugin.getConfig().getInt("combat.ghost-duration-seconds", 30);
        ghostEnabled = plugin.getConfig().getBoolean("combat.ghost-enabled", true);
        pvpTagHudEnabled = plugin.getConfig().getBoolean("combat.pvp-tag-hud-enabled", true);
        pvpTagHudMessage = plugin.getConfig().getString("combat.pvp-tag-hud-message",
                "&cCombat tagged! Logout in &e{seconds}s &c| Lives: &e{lives} &c| Tier: &e{tier}");
        pvpTagHudFlashMessage = plugin.getConfig().getString("combat.pvp-tag-hud-flash-message",
                "&4&lCOMBAT! &cLogout in &e{seconds}s &c| Lives: &e{lives} &c| Tier: &e{tier}");
        pvpTagHudFlash = plugin.getConfig().getBoolean("combat.pvp-tag-hud-flash", true);
        pvpTagHudUpdateTicks = plugin.getConfig().getInt("combat.pvp-tag-hud-update-ticks", 10);

        overworldDurationDays = plugin.getConfig().getInt("phases.overworld-duration-days", 21);
        netherDurationDays = plugin.getConfig().getInt("phases.nether-duration-days", 21);
        autoAdvance = plugin.getConfig().getBoolean("phases.auto-advance", true);
        broadcastNetherOpen = plugin.getConfig().getString("phases.broadcast-nether-open", "The Nether is now open!");
        broadcastEndOpen = plugin.getConfig().getString("phases.broadcast-end-open", "The End is now open!");
        seasonEndThreshold = plugin.getConfig().getDouble("phases.season-end-threshold", 0.6);
        activePlayerWindowDays = plugin.getConfig().getInt("phases.active-player-window-days", 7);
        windDownDays = plugin.getConfig().getInt("phases.wind-down-days", 7);
        windDownBroadcast = plugin.getConfig().getString("phases.wind-down-broadcast", "The season ends in 7 days...");
        lockBroadcast = plugin.getConfig().getString("phases.lock-broadcast", "The server is now locked.");
        joinBroadcastEnabled = plugin.getConfig().getBoolean("phases.join-broadcast-enabled", true);
        joinBroadcastOverworld = plugin.getConfig().getString("phases.join-broadcast-overworld", "You are playing in the Overworld phase.");
        joinBroadcastNether = plugin.getConfig().getString("phases.join-broadcast-nether", "The Nether is open!");
        joinBroadcastEnd = plugin.getConfig().getString("phases.join-broadcast-end", "The End is open. This is the final phase.");
        portalBlockedNether = plugin.getConfig().getString("phases.portal-blocked-nether", "The Nether is not yet open.");
        portalBlockedEnd = plugin.getConfig().getString("phases.portal-blocked-end", "The End is not yet open.");

        livesAliases = plugin.getConfig().getStringList("commands.lives-aliases");
        if (livesAliases.isEmpty()) livesAliases = List.of("lives");
    }

    public boolean isDebug() { return debug; }
    public int getStartingLives() { return startingLives; }
    public int getMaxLives() { return maxLives; }
    public boolean isPvpOnly() { return pvpOnly; }
    public int getRolloverIntervalHours()      { return rolloverIntervalHours; }
    public int getRolloverMaxLives()           { return rolloverMaxLives; }
    public int getRolloverMinPlayMinutes()     { return rolloverMinPlayMinutes; }
    public int getRolloverMinPlayPercent()     { return rolloverMinPlayPercent; }
    public String getRolloverStatusMessage()   { return rolloverStatusMessage; }
    public String getRolloverMissedMessage()   { return rolloverMissedMessage; }
    public String getRolloverSkippedMessage()  { return rolloverSkippedMessage; }
    public String getBanMessage() { return banMessage; }
    public String getBanMessageEndPhase() { return banMessageEndPhase; }
    public boolean isAllowSpectateOnBan() { return allowSpectateOnBan; }
    public String getTradeDisabledMessage() { return tradeDisabledMessage; }
    public boolean isTradeLastLifeConfirm() { return tradeLastLifeConfirm; }
    public String getTradeLastLifeConfirmMessage() { return tradeLastLifeConfirmMessage; }
    public int getTradeLastLifeConfirmTimeoutSeconds() { return tradeLastLifeConfirmTimeoutSeconds; }
    public int getVictimLossBase() { return victimLossBase; }
    public int getKillerRewardBase() { return killerRewardBase; }
    public boolean isDropEnderchestOnPvpDeath() { return dropEnderchestOnPvpDeath; }
    public int getTagDurationSeconds() { return tagDurationSeconds; }
    public int getGhostDurationSeconds() { return ghostDurationSeconds; }
    public boolean isGhostEnabled() { return ghostEnabled; }
    public boolean isPvpTagHudEnabled() { return pvpTagHudEnabled; }
    public String getPvpTagHudMessage() { return pvpTagHudMessage; }
    public String getPvpTagHudFlashMessage() { return pvpTagHudFlashMessage; }
    public boolean isPvpTagHudFlash() { return pvpTagHudFlash; }
    public int getPvpTagHudUpdateTicks() { return pvpTagHudUpdateTicks; }
    public int getOverworldDurationDays() { return overworldDurationDays; }
    public int getNetherDurationDays() { return netherDurationDays; }
    public boolean isAutoAdvance() { return autoAdvance; }
    public String getBroadcastNetherOpen() { return broadcastNetherOpen; }
    public String getBroadcastEndOpen() { return broadcastEndOpen; }
    public double getSeasonEndThreshold() { return seasonEndThreshold; }
    public int getActivePlayerWindowDays() { return activePlayerWindowDays; }
    public int getWindDownDays() { return windDownDays; }
    public String getWindDownBroadcast() { return windDownBroadcast; }
    public String getLockBroadcast() { return lockBroadcast; }
    public boolean isJoinBroadcastEnabled() { return joinBroadcastEnabled; }
    public String getJoinBroadcastOverworld() { return joinBroadcastOverworld; }
    public String getJoinBroadcastNether() { return joinBroadcastNether; }
    public String getJoinBroadcastEnd() { return joinBroadcastEnd; }
    public List<String> getLivesAliases() { return livesAliases; }
    public String getPortalBlockedNether() { return portalBlockedNether; }
    public String getPortalBlockedEnd() { return portalBlockedEnd; }
}
