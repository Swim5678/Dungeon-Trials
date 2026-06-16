package org.swim.dungeonTrials.structure.builtin;

import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.bukkit.Material;
import org.swim.dungeonTrials.service.PlayerService;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.PostProcessStep;
import org.swim.dungeonTrials.structure.SpawnPointFinder;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.structure.postprocess.BeardifierShellStep;
import org.swim.dungeonTrials.structure.spawnpoint.BedNearSpawnerFinder;

import java.util.List;
import java.util.Set;

public class TrialChambersStructureType implements StructureType {

    public static final String ID = "trial_chambers";

    private static final Set<Material> SPAWNER_BLOCKS = Set.of(Material.TRIAL_SPAWNER);

    private static final int PLACE_X_OFFSET = 0;
    private static final int PLACE_Y_OFFSET = -30;
    private static final int PLACE_Z_OFFSET = 0;

    private static final int PLATFORM_SIZE = 2;
    private static final int KEEP_LOADED_RADIUS = 4;
    private static final int RESTORATION_SCAN_RADIUS = 128;

    private final List<PostProcessStep> postProcessSteps;
    private final SpawnPointFinder spawnPointFinder;

    public TrialChambersStructureType(WorldService worldService, PlayerService playerService) {
        this.postProcessSteps = List.of(
                new BeardifierShellStep(worldService, TerrainAdjustment.ENCAPSULATE,
                        BeardifierShellStep.DEFAULT_SHELL_MATERIAL,
                        BeardifierShellStep.DEFAULT_SHELL_THRESHOLD)
        );
        this.spawnPointFinder = new BedNearSpawnerFinder(playerService);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Trial Chambers";
    }

    @Override
    public String namespacedId() {
        return "minecraft:trial_chambers";
    }

    @Override
    public int placeXOffset() {
        return PLACE_X_OFFSET;
    }

    @Override
    public int placeYOffset() {
        return PLACE_Y_OFFSET;
    }

    @Override
    public int placeZOffset() {
        return PLACE_Z_OFFSET;
    }

    @Override
    public int platformSize() {
        return PLATFORM_SIZE;
    }

    @Override
    public int keepLoadedRadius() {
        return KEEP_LOADED_RADIUS;
    }

    @Override
    public int restorationScanRadius() {
        return RESTORATION_SCAN_RADIUS;
    }

    @Override
    public Set<Material> spawnerBlocks() {
        return SPAWNER_BLOCKS;
    }

    @Override
    public List<PostProcessStep> postProcessSteps() {
        return postProcessSteps;
    }

    @Override
    public SpawnPointFinder spawnPointFinder() {
        return spawnPointFinder;
    }
}
