package com.moralityengine.data;

import java.util.UUID;

public class PlayerMorality {

    private final UUID uuid;
    private double score;

    public PlayerMorality(UUID uuid, double score) {
        this.uuid = uuid;
        this.score = score;
    }

    public UUID getUuid() { return uuid; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public void addScore(double delta) { this.score += delta; }
}
