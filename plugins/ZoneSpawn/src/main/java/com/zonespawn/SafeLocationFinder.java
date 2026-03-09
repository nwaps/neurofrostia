package com.zonespawn;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

public class SafeLocationFinder {

    private static final Set<Material> UNSAFE_STANDING = Set.of(
            Material.LAVA,
            Material.WATER,
            Material.CACTUS,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.MAGMA_BLOCK,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE
    );

    private static final Set<Material> PASSABLE_ABOVE = Set.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.SHORT_GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.SNOW,
            Material.TORCH,
            Material.WALL_TORCH,
            Material.SOUL_TORCH,
            Material.SOUL_WALL_TORCH,
            Material.VINE,
            // Single flowers
            Material.DANDELION,
            Material.POPPY,
            Material.BLUE_ORCHID,
            Material.ALLIUM,
            Material.AZURE_BLUET,
            Material.RED_TULIP,
            Material.ORANGE_TULIP,
            Material.WHITE_TULIP,
            Material.PINK_TULIP,
            Material.OXEYE_DAISY,
            Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY,
            Material.WITHER_ROSE,
            // Double flowers (top and bottom halves)
            Material.SUNFLOWER,
            Material.LILAC,
            Material.ROSE_BUSH,
            Material.PEONY,
            Material.PITCHER_PLANT,
            Material.PITCHER_CROP,
            // Dead bush, saplings, etc.
            Material.DEAD_BUSH,
            Material.OAK_SAPLING,
            Material.SPRUCE_SAPLING,
            Material.BIRCH_SAPLING,
            Material.JUNGLE_SAPLING,
            Material.ACACIA_SAPLING,
            Material.DARK_OAK_SAPLING,
            Material.MANGROVE_PROPAGULE,
            Material.CHERRY_SAPLING,
            Material.BAMBOO_SAPLING,
            Material.SEAGRASS,
            Material.SEA_PICKLE
    );

    private final ZoneSpawn plugin;
    private final Random random = new Random();

    public SafeLocationFinder(ZoneSpawn plugin) {
        this.plugin = plugin;
    }

    public Location findSafeLocation(ZoneConfig zone) {
        ConfigManager config = plugin.getConfigManager();
        World world = plugin.getServer().getWorld(zone.getWorld());

        if (world == null) {
            plugin.getLogger().warning("World '" + zone.getWorld() + "' not found for zone '" + zone.getName() + "'");
            return null;
        }

        int maxAttempts = config.getMaxAttempts();
        int minY = config.getMinY();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            int x = zone.getMinX() + random.nextInt(zone.getMaxX() - zone.getMinX() + 1);
            int z = zone.getMinZ() + random.nextInt(zone.getMaxZ() - zone.getMinZ() + 1);

            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            if (y < minY) {
                if (config.isDebug()) {
                    plugin.getLogger().info("[ZoneSpawn] Attempt " + attempt + ": y=" + y + " too low at (" + x + "," + z + ")");
                }
                continue;
            }

            Block standingBlock = world.getBlockAt(x, y, z);
            Block above1 = world.getBlockAt(x, y + 1, z);
            Block above2 = world.getBlockAt(x, y + 2, z);

            if (UNSAFE_STANDING.contains(standingBlock.getType())) {
                if (config.isDebug()) {
                    plugin.getLogger().info("[ZoneSpawn] Attempt " + attempt + ": unsafe standing block " + standingBlock.getType() + " at (" + x + "," + y + "," + z + ")");
                }
                continue;
            }

            if (!PASSABLE_ABOVE.contains(above1.getType())) {
                if (config.isDebug()) {
                    plugin.getLogger().info("[ZoneSpawn] Attempt " + attempt + ": blocked above1 " + above1.getType() + " at (" + x + "," + (y + 1) + "," + z + ")");
                }
                continue;
            }

            if (!PASSABLE_ABOVE.contains(above2.getType())) {
                if (config.isDebug()) {
                    plugin.getLogger().info("[ZoneSpawn] Attempt " + attempt + ": blocked above2 " + above2.getType() + " at (" + x + "," + (y + 2) + "," + z + ")");
                }
                continue;
            }

            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (config.isDebug()) {
                plugin.getLogger().info("[ZoneSpawn] Safe location found on attempt " + attempt + ": " + loc);
            }
            return loc;
        }

        plugin.getLogger().warning("[ZoneSpawn] Exhausted " + maxAttempts + " attempts finding safe location in zone '" + zone.getName() + "'. Consider reconfiguring zone boundaries.");
        return null;
    }

    /**
     * Runs findSafeLocation on the main thread (block lookups require main thread),
     * then calls the callback with the result. This method exists as a convenience
     * wrapper for command handling where we want to keep the calling code clean.
     * If already on the main thread, the callback is invoked synchronously.
     */
    public void findSafeLocationAsync(ZoneConfig zone, Consumer<Location> callback) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location loc = findSafeLocation(zone);
            callback.accept(loc);
        });
    }
}
