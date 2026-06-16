package org.swim.dungeonTrials.world;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.NonNull;

import java.util.Random;

public class VoidGenerator extends ChunkGenerator {

    @Override
    public void generateSurface(@NonNull WorldInfo worldInfo, @NonNull Random random,
                                int chunkX, int chunkZ, @NonNull ChunkData chunkData) {
    }
}
