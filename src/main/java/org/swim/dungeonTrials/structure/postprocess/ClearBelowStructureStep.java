package org.swim.dungeonTrials.structure.postprocess;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.PostProcessStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ClearBelowStructureStep implements PostProcessStep {

    private final WorldService worldService;
    private final Set<Material> preservedMaterials;

    public ClearBelowStructureStep(WorldService worldService, Set<Material> preservedMaterials) {
        this.worldService = worldService;
        this.preservedMaterials = (preservedMaterials == null) ? Set.of() : preservedMaterials;
    }

    @Override
    public void apply(World world, int[] bounds, List<BoundingBox> pieceBoxes,
                      List<BlockVector> spawners, Runnable callback) {
        if (bounds[0] == Integer.MAX_VALUE) {
            if (callback != null) callback.run();
            return;
        }
        if (pieceBoxes == null || pieceBoxes.isEmpty()) {
            if (callback != null) callback.run();
            return;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BoundingBox box : pieceBoxes) {
            if (box.minX() < minX) minX = box.minX();
            if (box.maxX() > maxX) maxX = box.maxX();
            if (box.minZ() < minZ) minZ = box.minZ();
            if (box.maxZ() > maxZ) maxZ = box.maxZ();
        }

        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int[] colMinY = new int[width * depth];
        Arrays.fill(colMinY, Integer.MAX_VALUE);

        for (BoundingBox box : pieceBoxes) {
            int boxMinX = Math.max(box.minX(), minX);
            int boxMaxX = Math.min(box.maxX(), maxX);
            int boxMinZ = Math.max(box.minZ(), minZ);
            int boxMaxZ = Math.min(box.maxZ(), maxZ);
            int boxMinY = box.minY();
            for (int x = boxMinX; x <= boxMaxX; x++) {
                for (int z = boxMinZ; z <= boxMaxZ; z++) {
                    int idx = (x - minX) * depth + (z - minZ);
                    if (boxMinY < colMinY[idx]) colMinY[idx] = boxMinY;
                }
            }
        }

        int worldMinY = world.getMinHeight();
        List<BlockVector> positions = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int topY = colMinY[(x - minX) * depth + (z - minZ)];
                if (topY == Integer.MAX_VALUE) continue;
                for (int y = worldMinY; y < topY; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (preservedMaterials.contains(current)) continue;
                    positions.add(new BlockVector(x, y, z));
                }
            }
        }

        worldService.applyBatchedNms(world, positions, Material.AIR.createBlockData(), callback);
    }
}
