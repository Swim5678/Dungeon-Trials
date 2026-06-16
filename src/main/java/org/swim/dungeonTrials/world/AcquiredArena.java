package org.swim.dungeonTrials.world;

import org.bukkit.Location;
import org.bukkit.World;

public record AcquiredArena(
        String arenaId,
        String strategyId,
        World world,
        String worldName,
        int[] regionBounds,
        Location arenaCenter,
        int keepLoadedRadius
) {
}
