package org.swim.dungeonTrials.structure;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.World;
import org.bukkit.util.BlockVector;

import java.util.List;

public interface PostProcessStep {

    void apply(World world, int[] bounds, List<BoundingBox> pieceBoxes,
               List<BlockVector> spawners, Runnable callback);
}
