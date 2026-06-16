package org.swim.dungeonTrials.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.swim.dungeonTrials.service.StructureService;
import org.swim.dungeonTrials.structure.StructureType;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

public interface WorldStrategy {

    AcquiredArena acquire(UUID ownerUuid, StructureType type);

    void release(UUID ownerUuid, String arenaId, int[] structureBounds);

    default void releaseRegion(String regionId, int[] structureBounds) {
        release(null, regionId, structureBounds);
    }

    World resolveWorld(String arenaId);

    int[] regionBoundsFor(String arenaId);

    Location arenaCenterFor(String arenaId);

    boolean isDungeonWorld(World world);

    Collection<String> activeArenaIds();

    void place(Player player, AcquiredArena arena, StructureType type, Consumer<StructureService.PlaceResult> onComplete);

    Location getReturnLocation(Location savedRespawn);

    void shutdown();

    default void refreshConfig() {
    }

    default void cleanupOrphans() {
    }

    default boolean isArenaReady(String arenaId) {
        return true;
    }
}
