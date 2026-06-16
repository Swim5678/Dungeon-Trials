package org.swim.dungeonTrials.structure.spawnpoint;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.service.PlayerService;
import org.swim.dungeonTrials.structure.SpawnPointFinder;

import java.util.List;

public class BedNearSpawnerFinder implements SpawnPointFinder {

    private final PlayerService playerService;

    public BedNearSpawnerFinder(PlayerService playerService) {
        this.playerService = playerService;
    }

    @Override
    public Location find(World world, int[] bounds, List<BlockVector> spawners) {
        return playerService.findBed(world, bounds, spawners);
    }
}
