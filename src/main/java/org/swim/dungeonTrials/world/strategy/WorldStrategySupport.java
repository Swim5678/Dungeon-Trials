package org.swim.dungeonTrials.world.strategy;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.structure.PostProcessStep;
import org.swim.dungeonTrials.structure.PreProcessStep;
import org.swim.dungeonTrials.structure.StructureType;

import java.util.List;
import java.util.function.Predicate;

public final class WorldStrategySupport {

    private WorldStrategySupport() {
    }

    public static void runPostProcessChain(StructureType type, World world, int[] bounds,
                                           List<BoundingBox> pieceBoxes,
                                           List<BlockVector> spawners,
                                           int index, Runnable onComplete) {
        List<PostProcessStep> steps = type.postProcessSteps();
        if (index >= steps.size()) {
            onComplete.run();
            return;
        }
        PostProcessStep step = steps.get(index);
        step.apply(world, bounds, pieceBoxes, spawners,
                () -> runPostProcessChain(type, world, bounds, pieceBoxes,
                        spawners, index + 1, onComplete));
    }

    public static void runPreProcessChain(StructureType type, World world, Location arenaCenter,
                                          int index, Runnable onComplete) {
        List<PreProcessStep> steps = type.preProcessSteps();
        if (index >= steps.size()) {
            onComplete.run();
            return;
        }
        PreProcessStep step = steps.get(index);
        step.apply(world, arenaCenter,
                () -> runPreProcessChain(type, world, arenaCenter, index + 1, onComplete));
    }

    public static Location resolveMainWorldSpawn(ConfigManager configManager,
                                                 Predicate<World> isDungeonWorld) {
        String name = configManager.mainWorld();
        World main = (name != null && !name.isBlank()) ? Bukkit.getWorld(name) : null;
        if (main == null) {
            main = Bukkit.getWorlds().stream()
                    .filter(w -> !isDungeonWorld.test(w))
                    .findFirst()
                    .orElse(Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst());
        }
        return (main != null) ? main.getSpawnLocation() : null;
    }
}
