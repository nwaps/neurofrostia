package com.corelife.api;

import java.util.UUID;

/**
 * Public API surface for CoreLife. MoralityEngine (and other plugins) may call these methods.
 * Obtain the instance via CoreLife.getInstance().getAPI().
 */
public interface CoreLifeAPI {

    /** Returns the number of lives the player currently has. Returns 0 if unknown. */
    int getPlayerLives(UUID playerUuid);

    /** Returns the server's current seasonal phase. */
    Phase getCurrentPhase();

    /**
     * Called by MoralityEngine to register the XP multiplier that should be applied to
     * CoreLife's base kill reward for a specific killer. Replaces any previously registered
     * multiplier for that player. Pass 1.0 to reset to neutral.
     */
    void registerKillXpMultiplier(UUID killerUuid, double multiplier);

    /**
     * Returns the currently registered XP multiplier for the given killer.
     * Returns 1.0 if no multiplier has been registered.
     */
    double getKillXpMultiplier(UUID killerUuid);

    /**
     * Register a morality provider so CoreLife can query bad-morality status for
     * active-player ratio calculations in /checkseason, and tier names for the
     * combat HUD display.
     */
    void registerMoralityProvider(MoralityProvider provider);

    /** Returns true if the player is currently combat-tagged. */
    boolean isPlayerCombatTagged(UUID playerUuid);

    /** Returns the remaining combat tag duration in seconds, or 0 if not tagged. */
    long getCombatTagRemainingSeconds(UUID playerUuid);

    /** Provider interface for MoralityEngine to supply morality data to CoreLife. */
    interface MoralityProvider {
        boolean isBadMorality(UUID playerUuid);

        /**
         * Returns the player's morality tier name (e.g. "EVIL", "NEUTRAL", "NOBLE").
         * Defaults to "UNKNOWN" so existing lambda registrations remain valid.
         */
        default String getTierName(UUID playerUuid) {
            return "UNKNOWN";
        }
    }
}
