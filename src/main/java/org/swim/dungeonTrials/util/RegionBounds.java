package org.swim.dungeonTrials.util;

import org.bukkit.Location;

public final class RegionBounds {

    private RegionBounds() {
    }

    public static boolean isWithin2D(Location loc, int[] regionBounds) {
        if (loc == null || regionBounds == null) return false;
        return loc.getBlockX() >= regionBounds[0] && loc.getBlockX() <= regionBounds[3]
                && loc.getBlockZ() >= regionBounds[2] && loc.getBlockZ() <= regionBounds[5];
    }
}
