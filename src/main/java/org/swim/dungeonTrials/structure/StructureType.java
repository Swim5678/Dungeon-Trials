package org.swim.dungeonTrials.structure;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

public interface StructureType {

    String id();

    String displayName();

    String namespacedId();

    int placeXOffset();

    int placeYOffset();

    int placeZOffset();

    int platformSize();

    int keepLoadedRadius();

    default int restorationScanRadius() {
        return keepLoadedRadius() * 16;
    }

    default int groundFoundationDepth() {
        return 0;
    }

    Set<Material> spawnerBlocks();

    default Set<Material> unsafeSpawnFloors() {
        return Set.of();
    }

    List<PostProcessStep> postProcessSteps();

    default List<PreProcessStep> preProcessSteps() {
        return List.of();
    }

    SpawnPointFinder spawnPointFinder();
}
