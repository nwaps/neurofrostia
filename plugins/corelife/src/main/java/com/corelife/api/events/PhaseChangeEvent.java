package com.corelife.api.events;

import com.corelife.api.Phase;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by CoreLife when the server phase advances.
 * MoralityEngine listens to activate frost vignette on END and lock life trading.
 */
public class PhaseChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Phase oldPhase;
    private final Phase newPhase;

    public PhaseChangeEvent(Phase oldPhase, Phase newPhase) {
        this.oldPhase = oldPhase;
        this.newPhase = newPhase;
    }

    public Phase getOldPhase() { return oldPhase; }
    public Phase getNewPhase() { return newPhase; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
