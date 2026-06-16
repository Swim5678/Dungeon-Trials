package org.swim.dungeonTrials.service;

import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.structure.StructureRegistry;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.util.Players;
import org.swim.dungeonTrials.util.RegionBounds;
import org.swim.dungeonTrials.world.AcquiredArena;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SessionService {

    private final DungeonTrials plugin;
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final StructureRegistry structureRegistry;

    private final Map<String, RegionState> regions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerToRegion = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerOrigins = new ConcurrentHashMap<>();
    private final Supplier<RestoredListener> restoredListenerSupplier;
    @Setter
    private WorldStrategy worldStrategy;
    @Setter
    private StructureService structureService;
    @Setter
    private BossBarService bossBarService;
    private CompletableFuture<Integer> regionLoadFuture;

    public SessionService(DungeonTrials plugin, DatabaseManager databaseManager,
                          ConfigManager configManager, StructureRegistry structureRegistry,
                          Supplier<RestoredListener> restoredListenerSupplier) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.structureRegistry = structureRegistry;
        this.restoredListenerSupplier = restoredListenerSupplier;
    }

    public CompletableFuture<Integer> loadActiveRegionsAsync() {
        if (regionLoadFuture == null) {
            regionLoadFuture = databaseManager.loadAllRegionsAsync().thenApply(loaded -> {
                int n = 0;
                for (RegionState region : loaded) {
                    regions.put(region.regionId(), region);
                    for (UUID member : region.members()) {
                        playerToRegion.put(member, region.regionId());
                    }
                    if (bossBarService != null) {
                        bossBarService.onRegionCreated(region);
                    }
                    n++;
                }
                if (n > 0) {
                    plugin.getLogger().info("Restored " + n + " active region(s) from database");
                }
                return n;
            });
        }
        return regionLoadFuture;
    }

    public RegionState createOrJoinRegion(Player player, AcquiredArena arena, int level,
                                          int[] structureBounds, List<BlockVector> spawners,
                                          StructureType type, Location origin) {
        String structureId = (type != null) ? type.id() : configManager.defaultStructure();
        UUID playerUuid = player.getUniqueId();
        String regionId = arena.arenaId();

        RegionState region = regions.get(regionId);
        if (region == null) {
            region = new RegionState(regionId, arena.worldName(), structureId, playerUuid, level,
                    System.currentTimeMillis());
            region.structureType(type);
            region.structureBounds(structureBounds);
            region.arenaRegion(arena.regionBounds());
            region.spawners(spawners);
            regions.put(regionId, region);
            databaseManager.saveRegionState(region);
            databaseManager.addRegionMember(regionId, playerUuid.toString(),
                    System.currentTimeMillis());
            if (bossBarService != null) {
                bossBarService.onRegionCreated(region);
            }
        }
        playerToRegion.put(playerUuid, regionId);
        if (region.addMember(playerUuid)) {
            databaseManager.addRegionMember(regionId, playerUuid.toString(),
                    System.currentTimeMillis());
        }
        if (bossBarService != null) {
            bossBarService.onMemberAdded(region, playerUuid);
        }
        if (origin != null) {
            playerOrigins.put(playerUuid, origin.clone());
        }
        return region;
    }

    public boolean joinAsMember(UUID playerUuid, String regionId) {
        RegionState region = regions.get(regionId);
        if (region == null) return false;
        boolean newMember = region.addMember(playerUuid);
        playerToRegion.put(playerUuid, regionId);
        if (newMember) {
            databaseManager.addRegionMember(regionId, playerUuid.toString(),
                    System.currentTimeMillis());
        }
        if (bossBarService != null) {
            bossBarService.onMemberAdded(region, playerUuid);
        }
        return true;
    }

    public boolean removeMember(UUID playerUuid) {
        String regionId = playerToRegion.remove(playerUuid);
        playerOrigins.remove(playerUuid);
        if (regionId == null) return false;
        RegionState region = regions.get(regionId);
        if (region == null) return false;
        region.removeMember(playerUuid);
        databaseManager.removeRegionMember(regionId, playerUuid.toString());
        if (bossBarService != null) {
            bossBarService.onMemberRemoved(region, playerUuid);
        }
        return true;
    }

    public void endRegion(String regionId, String reason) {
        RegionState region = regions.remove(regionId);
        if (region == null) return;
        for (UUID member : new ArrayList<>(region.members())) {
            playerToRegion.remove(member, regionId);
        }
        databaseManager.deleteRegionState(regionId);
        if (bossBarService != null) {
            bossBarService.onRegionEnded(regionId);
        }
    }

    public void persistRegion(RegionState region) {
        if (region != null) {
            databaseManager.saveRegionState(region);
        }
    }

    public RegionState getRegion(String regionId) {
        return regions.get(regionId);
    }

    public RegionState getRegionByPlayer(UUID playerUuid) {
        String regionId = playerToRegion.get(playerUuid);
        return (regionId != null) ? regions.get(regionId) : null;
    }

    public boolean isInAnyRegion(UUID playerUuid) {
        return playerToRegion.containsKey(playerUuid);
    }

    public Location getOrigin(UUID playerUuid) {
        return playerOrigins.get(playerUuid);
    }

    public List<RegionState> allRegions() {
        return new ArrayList<>(regions.values());
    }

    public RegionState findNearestRegion(Location loc, World world) {
        if (loc == null || world == null) return null;
        RegionState best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (RegionState region : regions.values()) {
            if (!world.getName().equals(region.worldName())) continue;

            UUID ownerUuid = region.ownerUuid();
            Player owner = Players.online(ownerUuid);
            if (owner == null) continue;
            if (owner.getWorld() != world) continue;

            int[] regionBounds = region.arenaRegion();
            if (regionBounds != null && !RegionBounds.isWithin2D(loc, regionBounds)) continue;

            double distSq = owner.getLocation().distanceSquared(loc);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = region;
            }
        }
        return best;
    }

    public void restorePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        RegionState region = getRegionByPlayer(uuid);
        if (region == null) return;
        if (worldStrategy == null || structureService == null) return;

        World world = worldStrategy.resolveWorld(region.regionId());
        if (world == null) return;

        Location arenaCenter = worldStrategy.arenaCenterFor(region.regionId());
        if (arenaCenter == null) return;

        StructureType type = region.structureType();
        if (type == null) {
            applyRestoredSpawn(player, world, arenaCenter);
            notifyRestored(player, uuid, region, world, arenaCenter, null);
            return;
        }

        int chunkRadius = (Math.max(0, type.restorationScanRadius()) >> 4) + 1;
        int cx0 = arenaCenter.getBlockX() >> 4;
        int cz0 = arenaCenter.getBlockZ() >> 4;
        int minCX = cx0 - chunkRadius;
        int maxCX = cx0 + chunkRadius;
        int minCZ = cz0 - chunkRadius;
        int maxCZ = cz0 + chunkRadius;
        world.getChunksAtAsync(minCX, minCZ, maxCX, maxCZ, false,
                () -> finishRestorePlayer(player, uuid, region, world, arenaCenter, type));
    }

    private void finishRestorePlayer(Player player, UUID uuid, RegionState region,
                                     World world, Location arenaCenter, StructureType type) {
        if (!player.isOnline()) return;

        StructureService.PlaceResult result = structureService.scanExistingStructure(world, arenaCenter, type);

        Location target = arenaCenter;
        if (result != null) {
            int[] bounds = result.bounds();
            List<BlockVector> spawners = result.spawners();
            region.structureBounds(bounds);
            region.spawners(spawners);
            if (region.arenaRegion() == null) {
                int[] arenaRegion = worldStrategy.regionBoundsFor(region.regionId());
                if (arenaRegion != null) {
                    region.arenaRegion(arenaRegion);
                }
            }
            Location bed = type.spawnPointFinder().find(world, bounds, spawners);
            if (bed != null) target = bed;
        }

        applyRestoredSpawn(player, world, target);
        notifyRestored(player, uuid, region, world, target, region.arenaRegion());
    }

    private void applyRestoredSpawn(Player player, World world, Location target) {
        player.setRespawnLocation(target, true);
        if (player.getLocation().getWorld() != world || player.getLocation().distance(target) > 2.0) {
            player.teleport(target);
        }
    }

    private void notifyRestored(Player player, UUID uuid, RegionState region,
                                World world, Location target, int[] regionBounds) {
        RestoredListener listener = (restoredListenerSupplier != null) ? restoredListenerSupplier.get() : null;
        if (listener != null) {
            listener.onRestored(new RestoredInfo(player, uuid, region, world, target, regionBounds));
        }
    }

    @FunctionalInterface
    public interface RestoredListener {
        void onRestored(RestoredInfo info);
    }

    public record RestoredInfo(Player player, UUID uuid, RegionState region,
                               World world, Location target, int[] regionBounds) {
    }
}
