package org.swim.dungeonTrials.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.structure.StructureType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class StructureService {

    private final DungeonTrials plugin;

    public StructureService(DungeonTrials plugin) {
        this.plugin = plugin;
    }

    private static int[] noBounds() {
        return new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE};
    }

    private static PlaceResult failedResult() {
        return new PlaceResult(noBounds(), List.of(), List.of());
    }

    @SuppressWarnings("DuplicatedCode")
    private static List<BlockVector> collectSpawnersInBounds(World world, int[] bounds, Set<Material> spawnerBlocks) {
        List<BlockVector> result = new ArrayList<>();
        int x1 = bounds[0], x2 = bounds[1];
        int y1 = bounds[2], y2 = bounds[3];
        int z1 = bounds[4], z2 = bounds[5];
        int minCX = x1 >> 4, maxCX = x2 >> 4;
        int minCZ = z1 >> 4, maxCZ = z2 >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                ChunkSnapshot snapshot = world.getChunkAt(cx, cz).getChunkSnapshot();
                int xLo = Math.max(x1, cx << 4);
                int xHi = Math.min(x2, (cx << 4) + 15);
                int zLo = Math.max(z1, cz << 4);
                int zHi = Math.min(z2, (cz << 4) + 15);
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        for (int y = y1; y <= y2; y++) {
                            if (spawnerBlocks.contains(snapshot.getBlockType(x & 15, y, z & 15))) {
                                result.add(new BlockVector(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    public void placeStructure(World world, Player player, StructureType type,
                               Location arenaCenter, Consumer<PlaceResult> onComplete) {
        int centerX = arenaCenter.getBlockX();
        int centerZ = arenaCenter.getBlockZ();
        int centerCX = centerX >> 4;
        int centerCZ = centerZ >> 4;
        int radius = type.keepLoadedRadius();

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    world.addPluginChunkTicket(centerCX + dx, centerCZ + dz, plugin);
                    world.setChunkForceLoaded(centerCX + dx, centerCZ + dz, true);
                    world.getChunkAt(centerCX + dx, centerCZ + dz, true);
                }
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!world.isChunkLoaded(centerCX, centerCZ) || !world.isChunkGenerated(centerCX, centerCZ)) {
                    plugin.getLogger().severe("chunk (" + centerCX + "," + centerCZ + ") not loaded/generated after sync load; aborting place");
                    player.sendMessage(plugin.getConfigManager().getMessage("dungeon.placement-timeout"));
                    onComplete.accept(failedResult());
                    return;
                }

                try {
                    PlaceResult result = placeStructureNms(world, type, arenaCenter);
                    onComplete.accept(result);
                } catch (Exception e) {
                    plugin.getLogger().severe("Structure placement failed: " + e.getMessage());
                    e.printStackTrace();
                    player.sendMessage(plugin.getConfigManager().getMessage("dungeon.placement-timeout"));
                    onComplete.accept(failedResult());
                }
            }, 20L);
        });
    }

    private PlaceResult placeStructureNms(World world, StructureType type, Location arenaCenter) {
        CraftWorld craftWorld = (CraftWorld) world;
        ServerLevel level = craftWorld.getHandle();
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        StructureManager chunkStructureManager = level.structureManager();

        Identifier structureId = Identifier.parse(type.namespacedId());
        Holder.Reference<Structure> structureRef = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .get(structureId)
                .orElseThrow(() -> new IllegalStateException("Structure not registered: " + type.namespacedId()));
        Structure structure = structureRef.value();

        int placeX = arenaCenter.getBlockX() + type.placeXOffset();
        int placeY = arenaCenter.getBlockY() + type.placeYOffset();
        int placeZ = arenaCenter.getBlockZ() + type.placeZOffset();
        BlockPos pos = new BlockPos(placeX, placeY, placeZ);

        StructureStart start = structure.generate(
                structureRef,
                level.dimension(),
                level.registryAccess(),
                chunkGenerator,
                chunkGenerator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                ChunkPos.containing(pos),
                0,
                level,
                b -> true
        );

        if (!start.isValid()) {
            throw new IllegalStateException("Structure generate returned invalid start for " + type.namespacedId());
        }

        List<BoundingBox> pieceBoxes = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox pieceBox = piece.getBoundingBox();
            pieceBoxes.add(pieceBox);
            if (pieceBox.minX() < minX) minX = pieceBox.minX();
            if (pieceBox.maxX() > maxX) maxX = pieceBox.maxX();
            if (pieceBox.minY() < minY) minY = pieceBox.minY();
            if (pieceBox.maxY() > maxY) maxY = pieceBox.maxY();
            if (pieceBox.minZ() < minZ) minZ = pieceBox.minZ();
            if (pieceBox.maxZ() > maxZ) maxZ = pieceBox.maxZ();
        }
        int[] bounds = new int[]{minX, maxX, minY, maxY, minZ, maxZ};

        ChunkPos chunkMin = new ChunkPos(
                SectionPos.blockToSectionCoord(minX),
                SectionPos.blockToSectionCoord(minZ)
        );
        ChunkPos chunkMax = new ChunkPos(
                SectionPos.blockToSectionCoord(maxX),
                SectionPos.blockToSectionCoord(maxZ)
        );

        ChunkPos.rangeClosed(chunkMin, chunkMax).forEach(c ->
                start.placeInChunk(
                        level,
                        chunkStructureManager,
                        chunkGenerator,
                        level.getRandom(),
                        new BoundingBox(
                                c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(),
                                c.getMaxBlockX(), level.getMaxY(), c.getMaxBlockZ()
                        ),
                        c
                )
        );

        List<BlockVector> spawners = collectSpawnersInBounds(world, bounds, type.spawnerBlocks());

        return new PlaceResult(bounds, Collections.unmodifiableList(pieceBoxes),
                Collections.unmodifiableList(spawners));
    }

    public PlaceResult scanExistingStructure(World world, Location arenaCenter, StructureType type) {
        int centerX = arenaCenter.getBlockX();
        int centerZ = arenaCenter.getBlockZ();
        int scanRadius = type.restorationScanRadius();
        int[] bounds = new int[]{
                centerX - scanRadius, centerX + scanRadius,
                world.getMinHeight(), world.getMaxHeight() - 1,
                centerZ - scanRadius, centerZ + scanRadius
        };

        List<BlockVector> spawners = collectSpawnersInBounds(world, bounds, type.spawnerBlocks());
        if (spawners.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockVector sp : spawners) {
            if (sp.getBlockX() < minX) minX = sp.getBlockX();
            if (sp.getBlockX() > maxX) maxX = sp.getBlockX();
            if (sp.getBlockY() < minY) minY = sp.getBlockY();
            if (sp.getBlockY() > maxY) maxY = sp.getBlockY();
            if (sp.getBlockZ() < minZ) minZ = sp.getBlockZ();
            if (sp.getBlockZ() > maxZ) maxZ = sp.getBlockZ();
        }
        int[] tightBounds = new int[]{minX, maxX, minY, maxY, minZ, maxZ};
        BoundingBox fallbackBox = new BoundingBox(
                tightBounds[0], tightBounds[2], tightBounds[4],
                tightBounds[1], tightBounds[3], tightBounds[5]);
        return new PlaceResult(tightBounds, List.of(fallbackBox),
                Collections.unmodifiableList(spawners));
    }

    public record PlaceResult(int[] bounds, List<BoundingBox> pieceBoxes, List<BlockVector> spawners) {
    }
}
