package org.swim.dungeonTrials.structure.postprocess;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.PostProcessStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BeardifierShellStep implements PostProcessStep {

    public static final Material DEFAULT_SHELL_MATERIAL = Material.BEDROCK;
    public static final double DEFAULT_SHELL_THRESHOLD = 0.65;

    private static final int MARGIN = 12;

    private final WorldService worldService;
    private final TerrainAdjustment terrainAdjustment;
    private final Material shellMaterial;
    private final double shellThreshold;

    public BeardifierShellStep(WorldService worldService, TerrainAdjustment terrainAdjustment,
                               Material shellMaterial, double shellThreshold) {
        this.worldService = worldService;
        this.terrainAdjustment = terrainAdjustment;
        this.shellMaterial = shellMaterial;
        this.shellThreshold = shellThreshold;
    }

    @Override
    public void apply(World world, int[] bounds, List<BoundingBox> pieceBoxes,
                      List<BlockVector> spawners, Runnable callback) {
        if (bounds[0] == Integer.MAX_VALUE) {
            if (callback != null) callback.run();
            return;
        }

        List<BoundingBox> effectivePieces = (pieceBoxes == null || pieceBoxes.isEmpty())
                ? List.of(new BoundingBox(
                bounds[0], bounds[2], bounds[4],
                bounds[1], bounds[3], bounds[5]))
                : pieceBoxes;
        BoundingBox[] piecesArr = effectivePieces.toArray(new BoundingBox[0]);

        BoundingBox combined = new BoundingBox(
                bounds[0], bounds[2], bounds[4],
                bounds[1], bounds[3], bounds[5]);

        List<Beardifier.Rigid> rigids = new ArrayList<>(piecesArr.length);
        for (BoundingBox box : piecesArr) {
            rigids.add(new Beardifier.Rigid(box, terrainAdjustment, 0));
        }

        Beardifier beard = new Beardifier(
                rigids,
                List.of(),
                combined.inflatedBy(MARGIN)
        );

        int x1 = combined.minX() - MARGIN;
        int x2 = combined.maxX() + MARGIN;
        int y1 = Math.max(world.getMinHeight(), combined.minY() - MARGIN);
        int y2 = Math.min(world.getMaxHeight() - 1, combined.maxY() + MARGIN);
        int z1 = combined.minZ() - MARGIN;
        int z2 = combined.maxZ() + MARGIN;

        int widthX = x2 - x1 + 1;
        int widthZ = z2 - z1 + 1;
        int heightY = y2 - y1 + 1;
        int sliceSize = widthZ * heightY;
        int total = widthX * sliceSize;
        double threshold = this.shellThreshold;

        List<BlockVector> positions = IntStream.range(0, total).parallel()
                .mapToObj(i -> {
                    int x = x1 + (i / sliceSize);
                    int rem = i % sliceSize;
                    int z = z1 + (rem / heightY);
                    int y = y1 + (rem % heightY);
                    for (BoundingBox box : piecesArr) {
                        if (x >= box.minX() && x <= box.maxX()
                                && y >= box.minY() && y <= box.maxY()
                                && z >= box.minZ() && z <= box.maxZ()) {
                            return null;
                        }
                    }
                    double density = beard.compute(
                            new DensityFunction.SinglePointContext(x, y, z));
                    if (density > threshold) return new BlockVector(x, y, z);
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        worldService.applyBatchedNms(world, positions, shellMaterial.createBlockData(), callback);
    }
}
