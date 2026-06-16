package org.swim.dungeonTrials.structure.builtin;

import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.bukkit.Material;
import org.swim.dungeonTrials.service.PlayerService;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.*;
import org.swim.dungeonTrials.structure.postprocess.BeardifierShellStep;
import org.swim.dungeonTrials.structure.postprocess.ClearBelowStructureStep;
import org.swim.dungeonTrials.structure.spawnpoint.MansionSpawnPointFinder;

import java.util.List;
import java.util.Set;

public class WoodlandMansionStructureType implements StructureType {

    public static final String ID = "woodland_mansion";

    private static final Set<Material> SPAWNER_BLOCKS = Set.of(Material.SPAWNER);

    private static final int PLACE_X_OFFSET = 0;
    private static final int PLACE_Y_OFFSET = -4;
    private static final int PLACE_Z_OFFSET = 0;

    private static final int PLATFORM_SIZE = 1;
    private static final int KEEP_LOADED_RADIUS = 5;
    private static final int RESTORATION_SCAN_RADIUS = 128;

    private final List<PreProcessStep> preProcessSteps;
    private final List<PostProcessStep> postProcessSteps;
    private final SpawnPointFinder spawnPointFinder;

    public WoodlandMansionStructureType(WorldService worldService, PlayerService playerService) {
        this.preProcessSteps = List.of(
                new MansionBaseStructureStep(worldService)
        );
        this.postProcessSteps = List.of(
                new BeardifierShellStep(worldService, TerrainAdjustment.ENCAPSULATE,
                        Material.BARRIER, BeardifierShellStep.DEFAULT_SHELL_THRESHOLD),
                new ClearBelowStructureStep(worldService, Set.of(Material.BARRIER))
        );
        this.spawnPointFinder = new MansionSpawnPointFinder(playerService);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Woodland Mansion";
    }

    @Override
    public String namespacedId() {
        return "minecraft:mansion";
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
    public List<PreProcessStep> preProcessSteps() {
        return preProcessSteps;
    }

    @Override
    public SpawnPointFinder spawnPointFinder() {
        return spawnPointFinder;
    }
}
