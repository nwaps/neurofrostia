package com.corelife.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired by CoreLife when a player loses a life.
 * MoralityEngine uses this to update active-player calculations.
 */
public class PlayerLifeLostEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final int remainingLives;

    public PlayerLifeLostEvent(UUID playerUuid, int remainingLives) {
        this.playerUuid = playerUuid;
        this.remainingLives = remainingLives;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public int getRemainingLives() { return remainingLives; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
