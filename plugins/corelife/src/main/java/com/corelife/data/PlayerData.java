package com.corelife.data;

import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private int lives;
    private boolean deathBanned;              // lifted automatically on life rollover
    private boolean adminBanned;              // never lifted by rollover
    private long lastLogin;                   // epoch millis, updated on PlayerJoinEvent
    private long playtimeSinceRolloverMs;     // accumulated online ms since last rollover
    private boolean missedLastRollover;       // true when skipped due to playtime; cleared on next login

    public PlayerData(UUID uuid, int startingLives) {
        this.uuid = uuid;
        this.lives = startingLives;
        this.deathBanned = false;
        this.adminBanned = false;
        this.lastLogin = System.currentTimeMillis();
    }

    // Raw constructor used by PlayerDataManager when loading from disk
    public PlayerData(UUID uuid, int lives, boolean deathBanned, boolean adminBanned,
                      long lastLogin, long playtimeSinceRolloverMs, boolean missedLastRollover) {
        this.uuid = uuid;
        this.lives = lives;
        this.deathBanned = deathBanned;
        this.adminBanned = adminBanned;
        this.lastLogin = lastLogin;
        this.playtimeSinceRolloverMs = playtimeSinceRolloverMs;
        this.missedLastRollover = missedLastRollover;
    }

    public UUID getUuid() { return uuid; }

    public int getLives() { return lives; }
    public void setLives(int lives) { this.lives = lives; }

    public boolean isDeathBanned() { return deathBanned; }
    public void setDeathBanned(boolean deathBanned) { this.deathBanned = deathBanned; }

    public boolean isAdminBanned() { return adminBanned; }
    public void setAdminBanned(boolean adminBanned) { this.adminBanned = adminBanned; }

    public boolean isBanned() { return deathBanned || adminBanned; }

    public long getLastLogin() { return lastLogin; }
    public void setLastLogin(long lastLogin) { this.lastLogin = lastLogin; }

    public long getPlaytimeSinceRolloverMs() { return playtimeSinceRolloverMs; }
    public void addPlaytimeSinceRolloverMs(long ms) { this.playtimeSinceRolloverMs += ms; }
    public void resetPlaytimeSinceRolloverMs() { this.playtimeSinceRolloverMs = 0; }

    public boolean hasMissedLastRollover() { return missedLastRollover; }
    public void setMissedLastRollover(boolean missedLastRollover) { this.missedLastRollover = missedLastRollover; }
}
