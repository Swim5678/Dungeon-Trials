package org.swim.dungeonTrials.service;

import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.swim.dungeonTrials.util.Players;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RespawnService {

    private final Map<UUID, Location> originalRespawns = new HashMap<>();
    private final Map<UUID, Location> pendingRespawns = new HashMap<>();
    @Setter
    private WorldStrategy worldStrategy;

    public void saveOriginalRespawn(UUID playerUuid, Location respawn) {
        if (respawn == null) return;
        if (respawn.getWorld() == null) return;
        if (worldStrategy != null && worldStrategy.isDungeonWorld(respawn.getWorld())) return;
        if (originalRespawns.containsKey(playerUuid)) return;
        originalRespawns.put(playerUuid, respawn);
    }

    public Location getOriginalRespawn(UUID playerUuid) {
        return originalRespawns.get(playerUuid);
    }

    public void clearOriginalRespawn(UUID playerUuid) {
        originalRespawns.remove(playerUuid);
    }

    public void restoreBedSpawn(UUID playerUuid, Location original) {
        if (original == null) return;
        Player player = Players.online(playerUuid);
        if (player != null) {
            player.setRespawnLocation(original, true);
        } else {
            pendingRespawns.put(playerUuid, original);
        }
    }

    public void applyPendingRespawn(UUID playerUuid) {
        Location pending = pendingRespawns.remove(playerUuid);
        if (pending == null) return;
        Player player = Players.online(playerUuid);
        if (player != null) {
            player.setRespawnLocation(pending, true);
        }
    }

    public void consumePending(UUID playerUuid) {
        pendingRespawns.remove(playerUuid);
    }

    public void clearAll(UUID playerUuid) {
        originalRespawns.remove(playerUuid);
        pendingRespawns.remove(playerUuid);
    }

    public void clearAllMaps() {
        originalRespawns.clear();
        pendingRespawns.clear();
    }
}
