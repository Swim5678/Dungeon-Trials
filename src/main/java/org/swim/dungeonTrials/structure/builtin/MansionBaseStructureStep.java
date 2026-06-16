package org.swim.dungeonTrials.structure.builtin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.PreProcessStep;

import java.util.ArrayList;
import java.util.List;

public class MansionBaseStructureStep implements PreProcessStep {

    private static final int PLATFORM_HALF_SIZE = 7;
    private static final int PLATFORM_Y = 64;

    private final WorldService worldService;

    public MansionBaseStructureStep(WorldService worldService) {
        this.worldService = worldService;
    }

    @Override
    public void apply(World world, Location arenaCenter, Runnable callback) {
        int centerX = arenaCenter.getBlockX();
        int centerZ = arenaCenter.getBlockZ();

        List<BlockVector> platform = new ArrayList<>((PLATFORM_HALF_SIZE * 2 + 1) * (PLATFORM_HALF_SIZE * 2 + 1));
        for (int dx = -PLATFORM_HALF_SIZE; dx <= PLATFORM_HALF_SIZE; dx++) {
            for (int dz = -PLATFORM_HALF_SIZE; dz <= PLATFORM_HALF_SIZE; dz++) {
                platform.add(new BlockVector(centerX + dx, PLATFORM_Y, centerZ + dz));
            }
        }

        Runnable onAllBatched = () -> {
            if (callback != null) callback.run();
        };

        placeBatched(world, platform, onAllBatched);
    }

    private void placeBatched(World world, List<BlockVector> positions, Runnable next) {
        if (positions.isEmpty()) {
            if (next != null) next.run();
            return;
        }
        worldService.applyBatched(world, positions, block -> block.setType(Material.OBSIDIAN, false), next);
    }
}
