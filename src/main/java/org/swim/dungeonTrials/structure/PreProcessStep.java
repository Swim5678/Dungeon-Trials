package org.swim.dungeonTrials.structure;

import org.bukkit.Location;
import org.bukkit.World;

public interface PreProcessStep {

    void apply(World world, Location arenaCenter, Runnable callback);
}
