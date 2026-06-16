package org.swim.dungeonTrials.structure;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BlockVector;

import java.util.List;

public interface SpawnPointFinder {

    Location find(World world, int[] bounds, List<BlockVector> spawners);
}
