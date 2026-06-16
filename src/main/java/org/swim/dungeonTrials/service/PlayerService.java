package org.swim.dungeonTrials.service;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PlayerService {

    public PlayerService() {
    }

    private static boolean isBed(Material m) {
        return m.name().endsWith("_BED");
    }

    public static boolean isInsideBounds(Location loc, int[] bounds) {
        if (loc == null || bounds == null) return false;
        if (bounds[0] == Integer.MAX_VALUE) return false;
        return loc.getBlockX() >= bounds[0] && loc.getBlockX() <= bounds[1]
                && loc.getBlockY() >= bounds[2] && loc.getBlockY() <= bounds[3]
                && loc.getBlockZ() >= bounds[4] && loc.getBlockZ() <= bounds[5];
    }

    public Location findBed(World world, int[] bounds, List<BlockVector> spawners) {
        Location spawnChamber = findSpawnChamberSpawner(world, spawners);
        if (spawnChamber != null) {
            Location bed = scanBedsNear(spawnChamber);
            if (bed != null) {
                return bed;
            }
        }

        if (bounds != null && bounds[0] != Integer.MAX_VALUE) {
            return scanBedsInBounds(world, bounds);
        }
        return null;
    }

    private Location findSpawnChamberSpawner(World world, List<BlockVector> spawners) {
        if (spawners == null || spawners.isEmpty()) return null;
        Location best = null;
        int maxBeds = 0;
        for (BlockVector s : spawners) {
            Location center = new Location(world,
                    s.getBlockX() + 0.5, s.getBlockY() + 0.5, s.getBlockZ() + 0.5);
            int bedCount = countBedsNear(center);
            if (bedCount > maxBeds) {
                maxBeds = bedCount;
                best = center;
            }
        }
        return best;
    }

    private int countBedsNear(Location center) {
        int[] count = {0};
        forEachBlockInBox(center.getWorld(),
                center.getBlockX() - 12, center.getBlockY() - 8, center.getBlockZ() - 12,
                center.getBlockX() + 12, center.getBlockY() + 8, center.getBlockZ() + 12,
                (x, y, z, type) -> {
                    if (isBed(type)) count[0]++;
                });
        return count[0];
    }

    private Location scanBedsNear(Location center) {
        List<Location> beds = new ArrayList<>();
        forEachBlockInBox(center.getWorld(),
                center.getBlockX() - 12, center.getBlockY() - 8, center.getBlockZ() - 12,
                center.getBlockX() + 12, center.getBlockY() + 8, center.getBlockZ() + 12,
                (x, y, z, type) -> {
                    if (isBed(type)) beds.add(new Location(center.getWorld(), x + 0.5, y + 0.5, z + 0.5));
                });
        return pickClosestSafeBed(beds, center);
    }

    private Location scanBedsInBounds(World world, int[] bounds) {
        List<Location> beds = new ArrayList<>();
        forEachBlockInBox(world, bounds[0], bounds[2], bounds[4], bounds[1], bounds[3], bounds[5],
                (x, y, z, type) -> {
                    if (isBed(type)) beds.add(new Location(world, x + 0.5, y + 0.5, z + 0.5));
                });
        Location center = new Location(world,
                (bounds[0] + bounds[1]) / 2.0,
                (bounds[2] + bounds[3]) / 2.0,
                (bounds[4] + bounds[5]) / 2.0);
        return pickClosestSafeBed(beds, center);
    }

    private Location pickClosestSafeBed(List<Location> beds, Location center) {
        beds.sort(Comparator.comparingDouble(b -> b.distanceSquared(center)));
        for (Location bed : beds) {
            if (isSafeBedLocation(bed)) {
                return bed;
            }
        }
        return null;
    }

    private void forEachBlockInBox(World world, int minX, int minY, int minZ,
                                   int maxX, int maxY, int maxZ, BlockScanAction action) {
        int minCX = minX >> 4;
        int maxCX = maxX >> 4;
        int minCZ = minZ >> 4;
        int maxCZ = maxZ >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = world.getChunkAt(cx, cz);
                ChunkSnapshot snap = chunk.getChunkSnapshot();
                int xLo = Math.max(minX, cx << 4);
                int xHi = Math.min(maxX, (cx << 4) + 15);
                int zLo = Math.max(minZ, cz << 4);
                int zHi = Math.min(maxZ, (cz << 4) + 15);
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            action.accept(x, y, z, snap.getBlockType(x & 15, y, z & 15));
                        }
                    }
                }
            }
        }
    }

    private boolean isSafeBedLocation(Location loc) {
        Block bed = loc.getBlock();
        if (!isBed(bed.getType())) return false;
        Block above1 = loc.clone().add(0, 1, 0).getBlock();
        Block above2 = loc.clone().add(0, 2, 0).getBlock();
        Block below = loc.clone().add(0, -1, 0).getBlock();
        return isAir(above1) && isAir(above2) && !isAir(below);
    }

    private boolean isSafeStandingLocation(Location loc) {
        Block head = loc.getBlock();
        Block feet = loc.clone().add(0, -1, 0).getBlock();
        Block below = loc.clone().add(0, -2, 0).getBlock();
        return isAir(head) && isAir(feet) && !isAir(below);
    }

    private boolean isAir(Block block) {
        Material t = block.getType();
        return t == Material.AIR || t == Material.VOID_AIR;
    }

    public void teleportPlayer(Player player, World world, Location bedLocation, Location arenaCenter) {
        if (bedLocation != null) {
            player.setRespawnLocation(bedLocation, true);
            player.teleport(bedLocation);
            return;
        }
        Location fallback = findSafeFallback(world, arenaCenter);
        player.teleport(fallback);
    }

    public Location findSafeFallback(World world, Location arenaCenter) {
        int cx = (arenaCenter != null) ? arenaCenter.getBlockX() : 0;
        int cz = (arenaCenter != null) ? arenaCenter.getBlockZ() : 0;
        int groundY = world.getHighestBlockYAt(cx, cz);
        if (groundY <= world.getMinHeight()) {
            return new Location(world, cx + 0.5, 81.5, cz + 0.5);
        }
        for (int dy = 0; dy <= 20; dy++) {
            int y = groundY + dy;
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    Location loc = new Location(world, cx + x + 0.5, y + 0.5, cz + z + 0.5);
                    if (isSafeStandingLocation(loc)) {
                        return loc;
                    }
                }
            }
        }
        return new Location(world, cx + 0.5, 81.5, cz + 0.5);
    }

    public Location findSafeSpot(World world, Location target) {
        if (target == null) return null;
        int cx = target.getBlockX();
        int cy = target.getBlockY();
        int cz = target.getBlockZ();

        Location sameLevel = findSafeStandingInGrid(world, cx, cy, cz, 2);
        if (sameLevel != null) return sameLevel;

        for (int dy = 1; dy <= 8; dy++) {
            for (int sign : new int[]{1, -1}) {
                Location loc = findSafeStandingInGrid(world, cx, cy + dy * sign, cz, 2);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    private Location findSafeStandingInGrid(World world, int cx, int cy, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Location loc = new Location(world, cx + dx + 0.5, cy + 0.5, cz + dz + 0.5);
                if (isSafeStandingLocation(loc)) {
                    return loc;
                }
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface BlockScanAction {
        void accept(int x, int y, int z, Material type);
    }
}
