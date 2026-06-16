package org.swim.dungeonTrials.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class WorldService {

    private static final int BLOCKS_PER_TICK = 4096;

    private final JavaPlugin plugin;
    private final Light ambientLightData = createLightData(15);

    public WorldService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static Light createLightData(int level) {
        Light d = (Light) Material.LIGHT.createBlockData();
        d.setLevel(Math.clamp(level, 0, 15));
        return d;
    }

    public void clearRegion(World world, int[] bounds, int margin, Runnable callback) {
        if (bounds == null || bounds[0] == Integer.MAX_VALUE) {
            if (callback != null) callback.run();
            return;
        }
        int x1 = bounds[0] - margin, x2 = bounds[1] + margin;
        int y1 = world.getMinHeight();
        int y2 = Math.min(world.getMaxHeight() - 1, bounds[3] + margin);
        int z1 = bounds[4] - margin, z2 = bounds[5] + margin;

        int[] outer = {x1, x2, y1, y2, z1, z2};
        List<BlockVector> positions = new ArrayList<>();
        forEachBlockInBounds(world, outer, (snapshot, x, y, z) -> {
            if (snapshot.getBlockType(x & 15, y, z & 15) != Material.AIR) {
                positions.add(new BlockVector(x, y, z));
            }
        });

        applyBatched(world, positions, block -> block.setType(Material.AIR, false), callback);
    }

    @SuppressWarnings("DuplicatedCode")
    private void forEachBlockInBounds(World world, int[] bounds, BlockScanAction action) {
        int x1 = bounds[0], x2 = bounds[1];
        int y1 = bounds[2], y2 = bounds[3];
        int z1 = bounds[4], z2 = bounds[5];
        int minCX = x1 >> 4, maxCX = x2 >> 4;
        int minCZ = z1 >> 4, maxCZ = z2 >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (!chunk.isLoaded()) continue;
                ChunkSnapshot snapshot = chunk.getChunkSnapshot();
                int xLo = Math.max(x1, cx << 4);
                int xHi = Math.min(x2, (cx << 4) + 15);
                int zLo = Math.max(z1, cz << 4);
                int zHi = Math.min(z2, (cz << 4) + 15);
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        for (int y = y1; y <= y2; y++) {
                            action.accept(snapshot, x, y, z);
                        }
                    }
                }
            }
        }
    }

    public void applyBatched(World world, List<BlockVector> positions, Consumer<Block> action, Runnable callback) {
        final int total = positions.size();
        if (total == 0) {
            if (callback != null) callback.run();
            return;
        }
        if (!plugin.isEnabled()) {
            for (BlockVector v : positions) {
                action.accept(world.getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
            }
            if (callback != null) callback.run();
            return;
        }
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(i + BLOCKS_PER_TICK, total);
                for (; i < end; i++) {
                    BlockVector v = positions.get(i);
                    action.accept(world.getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
                }
                if (i >= total) {
                    cancel();
                    if (callback != null) callback.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void applyBatchedNms(World world, List<BlockVector> positions,
                                BlockData blockData, Runnable callback) {
        final int total = positions.size();
        if (total == 0) {
            if (callback != null) callback.run();
            return;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockState nmsState = ((CraftBlockData) blockData).getState();
        int flags = 2;

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(i + BLOCKS_PER_TICK, total);
                for (; i < end; i++) {
                    BlockVector v = positions.get(i);
                    BlockPos pos = new BlockPos(v.getBlockX(), v.getBlockY(), v.getBlockZ());
                    level.setBlock(pos, nmsState, flags);
                }
                if (i >= total) {
                    cancel();
                    if (callback != null) callback.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @FunctionalInterface
    private interface BlockScanAction {
        void accept(ChunkSnapshot snapshot, int x, int y, int z);
    }
}
