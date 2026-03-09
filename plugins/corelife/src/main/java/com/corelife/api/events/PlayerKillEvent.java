package com.corelife.api.events;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired by CoreLife on any PvP kill, including ghost kills.
 * MoralityEngine listens to this to attribute morality progress and register XP multipliers.
 */
public class PlayerKillEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID killerUuid;
    private final UUID victimUuid;
    private final Location killLocation;
    /** True when the victim was a combat-log ghost, not the live player. */
    private final boolean ghostKill;

    public PlayerKillEvent(UUID killerUuid, UUID victimUuid, Location killLocation, boolean ghostKill) {
        this.killerUuid = killerUuid;
        this.victimUuid = victimUuid;
        this.killLocation = killLocation;
        this.ghostKill = ghostKill;
    }

    public UUID getKillerUuid() { return killerUuid; }
    public UUID getVictimUuid() { return victimUuid; }
    public Location getKillLocation() { return killLocation; }
    public boolean isGhostKill() { return ghostKill; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
