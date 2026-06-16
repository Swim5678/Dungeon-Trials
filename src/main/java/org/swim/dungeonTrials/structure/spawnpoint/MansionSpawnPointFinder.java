package org.swim.dungeonTrials.structure.spawnpoint;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.service.PlayerService;
import org.swim.dungeonTrials.structure.SpawnPointFinder;

import java.util.ArrayList;
import java.util.List;

public class MansionSpawnPointFinder implements SpawnPointFinder {

    private static final double TORCH_PAIR_DISTANCE = 6.0;
    private static final double TORCH_PAIR_TOLERANCE = 1;

    private final PlayerService playerService;

    public MansionSpawnPointFinder(PlayerService playerService) {
        this.playerService = playerService;
    }

    @Override
    public Location find(World world, int[] bounds, List<BlockVector> spawners) {
        if (world == null || bounds == null || bounds[0] == Integer.MAX_VALUE) {
            return null;
        }
        int minX = bounds[0], maxX = bounds[1];
        int minY = bounds[2], maxY = bounds[3];
        int minZ = bounds[4], maxZ = bounds[5];

        List<BlockVector> torches = scanTorches(world, minX, maxX, minY, maxY, minZ, maxZ);

        BlockVector t1 = null;
        BlockVector t2 = null;
        int bestY = Integer.MAX_VALUE;
        for (int i = 0; i < torches.size(); i++) {
            for (int j = i + 1; j < torches.size(); j++) {
                BlockVector a = torches.get(i);
                BlockVector b = torches.get(j);
                if (a.getBlockY() != b.getBlockY()) {
                    continue;
                }
                double dx = a.getBlockX() - b.getBlockX();
                double dy = a.getBlockY() - b.getBlockY();
                double dz = a.getBlockZ() - b.getBlockZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (Math.abs(dist - TORCH_PAIR_DISTANCE) > TORCH_PAIR_TOLERANCE) {
                    continue;
                }
                if (a.getBlockY() < bestY) {
                    t1 = a;
                    t2 = b;
                    bestY = a.getBlockY();
                }
            }
        }

        if (t1 != null) {
            double mx = (t1.getBlockX() + t2.getBlockX()) / 2.0;
            double mz = (t1.getBlockZ() + t2.getBlockZ()) / 2.0;
            int my = t1.getBlockY();
            Location mid = new Location(world, mx + 0.5, my, mz + 0.5);
            Location safe = playerService.findSafeSpot(world, mid);
            if (safe != null) {
                return safe;
            }
        }

        if (!torches.isEmpty()) {
            BlockVector tMin = torches.getFirst();
            for (BlockVector t : torches) {
                if (t.getBlockY() < tMin.getBlockY()) {
                    tMin = t;
                }
            }
            Location torchLoc = new Location(world,
                    tMin.getX() + 0.5, tMin.getY(), tMin.getZ() + 0.5);
            return playerService.findSafeSpot(world, torchLoc);
        }

        return null;
    }

    private List<BlockVector> scanTorches(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<BlockVector> torches = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m == Material.TORCH || m == Material.WALL_TORCH) {
                        torches.add(new BlockVector(x, y, z));
                    }
                }
            }
        }
        return torches;
    }
}
