package com.moralityengine.analytics;

public enum MoralityChangeReason {
    KILL_NEUTRAL_GOOD_PLAYER("Kill Neutral/Good"),
    KILL_BAD_PLAYER         ("Kill Bad Player"),
    KILL_UNDEAD             ("Kill Undead"),
    KILL_VILLAGER           ("Kill Villager"),
    KILL_VILLAGER_CONSUME   ("Villager Meat"),
    HERO_OF_VILLAGE         ("Hero of Village"),
    TOTEM_OF_DYING          ("Totem of Dying"),
    PROXIMITY_BONUS         ("Proximity Bonus"),
    KILL_GOLEM_BOSS         ("Kill Golem Boss"),
    KILL_IRON_GOLEM         ("Kill Iron Golem"),
    EAT_ROTTEN_FLESH        ("Eat Rotten Flesh"),
    KILL_PASSIVE_MOB        ("Kill Passive Mob"),
    DEATH_DECAY             ("Death Decay");

    private final String displayName;

    MoralityChangeReason(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
