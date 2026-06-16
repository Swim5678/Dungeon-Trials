package org.swim.dungeonTrials.util;

import org.bukkit.Bukkit;
import org.bukkit.World;

public final class Worlds {

    private Worlds() {
    }

    public static boolean isLoaded(World world) {
        return world != null && Bukkit.getWorld(world.getName()) != null;
    }
}
