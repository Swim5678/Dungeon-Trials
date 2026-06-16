package org.swim.dungeonTrials.model;

public final class MobSpawnState {

    public int totalSpawned;
    public int aliveCount;
    public long nextSpawnTick;
    public net.minecraft.world.entity.EntityType<?> nmsType;

    public MobSpawnState() {
        this.totalSpawned = 0;
        this.aliveCount = 0;
        this.nextSpawnTick = 0;
    }

    public void reset() {
        totalSpawned = 0;
        aliveCount = 0;
        nextSpawnTick = 0;
    }
}
