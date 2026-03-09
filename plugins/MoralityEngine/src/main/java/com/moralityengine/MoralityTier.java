package com.moralityengine;

/**
 * Represents a player's morality tier.
 * BAD tiers are negative; GOOD tiers are positive; NEUTRAL is the centre.
 */
public enum MoralityTier {
    BAD_4(-4),
    BAD_3(-3),
    BAD_2(-2),
    BAD_1(-1),
    NEUTRAL(0),
    GOOD_1(1),
    GOOD_2(2),
    GOOD_3(3),
    GOOD_4(4);

    private final int value;

    MoralityTier(int value) {
        this.value = value;
    }

    /** Signed tier value: negative = bad, 0 = neutral, positive = good. */
    public int getValue() { return value; }

    public boolean isBad() { return value < 0; }
    public boolean isGood() { return value > 0; }
    public boolean isNeutral() { return value == 0; }

    /** Absolute tier level (1–4), or 0 for neutral. */
    public int getLevel() { return Math.abs(value); }

    @Override
    public String toString() {
        return switch (this) {
            case BAD_4 -> "Bad IV";
            case BAD_3 -> "Bad III";
            case BAD_2 -> "Bad II";
            case BAD_1 -> "Bad I";
            case NEUTRAL -> "Neutral";
            case GOOD_1 -> "Good I";
            case GOOD_2 -> "Good II";
            case GOOD_3 -> "Good III";
            case GOOD_4 -> "Good IV";
        };
    }
}
