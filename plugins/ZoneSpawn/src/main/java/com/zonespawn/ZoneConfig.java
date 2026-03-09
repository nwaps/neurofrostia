package com.zonespawn;

public class ZoneConfig {

    private final String name;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final String world;
    /** Per-zone message overrides — null means fall back to the global config. */
    private final String messageRespawn;
    private final String messageTeleport;
    private final String messageSetzone;
    /** Friendly display name shown via {label} in messages; defaults to the zone key if not set. */
    private final String label;

    public ZoneConfig(String name, int minX, int maxX, int minZ, int maxZ, String world,
                      String messageRespawn, String messageTeleport, String messageSetzone, String label) {
        this.name = name;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.world = world;
        this.messageRespawn = messageRespawn;
        this.messageTeleport = messageTeleport;
        this.messageSetzone = messageSetzone;
        this.label = label != null ? label : name;
    }

    public String getName() { return name; }
    public int getMinX() { return minX; }
    public int getMaxX() { return maxX; }
    public int getMinZ() { return minZ; }
    public int getMaxZ() { return maxZ; }
    public String getWorld() { return world; }
    /** Override for messages.respawn-player, or null to use the global value. */
    public String getMessageRespawn() { return messageRespawn; }
    /** Override for messages.teleport-player, or null to use the global value. */
    public String getMessageTeleport() { return messageTeleport; }
    /** Override for messages.setzone-player, or null to use the global value. */
    public String getMessageSetzone() { return messageSetzone; }
    /** Returns the friendly display name for use in messages via {label}. */
    public String getLabel() { return label; }

    @Override
    public String toString() {
        return "ZoneConfig{name=" + name + ", x=[" + minX + "," + maxX + "], z=[" + minZ + "," + maxZ + "], world=" + world + "}";
    }
}
