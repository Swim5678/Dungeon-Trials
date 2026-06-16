package org.swim.dungeonTrials.world.strategy;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.service.StructureService;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.util.RegionBounds;
import org.swim.dungeonTrials.world.AcquiredArena;
import org.swim.dungeonTrials.world.VoidGenerator;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("DuplicatedCode")
public class SharedWorldStrategy implements WorldStrategy {

    public static final String ID = "shared";
    private static final int GRID_DIM = 100;
    private static final int PLATFORM_Y = 79;
    private static final int ARENA_FLOOR_Y = 80;

    private static final GameRule<Boolean> SPAWN_MOBS = org.bukkit.GameRules.SPAWN_MOBS;
    private static final GameRule<Boolean> SPAWN_WARDENS = org.bukkit.GameRules.SPAWN_WARDENS;
    private static final GameRule<Boolean> SPAWN_PATROLS = org.bukkit.GameRules.SPAWN_PATROLS;
    private static final GameRule<Boolean> SPAWN_WANDERING_TRADERS = org.bukkit.GameRules.SPAWN_WANDERING_TRADERS;
    private static final GameRule<Boolean> SPAWN_PHANTOMS = org.bukkit.GameRules.SPAWN_PHANTOMS;
    private static final GameRule<Boolean> ADVANCE_TIME = org.bukkit.GameRules.ADVANCE_TIME;
    private static final GameRule<Boolean> ADVANCE_WEATHER = org.bukkit.GameRules.ADVANCE_WEATHER;

    private final DungeonTrials plugin;
    private final WorldService worldService;
    private final StructureService structureService;
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;

    private final Map<String, AcquiredArena> arenas = new ConcurrentHashMap<>();
    private final Set<Long> usedGridIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> freeGridIds = ConcurrentHashMap.newKeySet();
    private final Set<String> readyArenas = ConcurrentHashMap.newKeySet();
    private final Object gridLock = new Object();
    private int gridSize;
    private String worldName;
    private long nextGridId = 0;

    public SharedWorldStrategy(DungeonTrials plugin, WorldService worldService,
                               StructureService structureService,
                               DatabaseManager databaseManager,
                               ConfigManager configManager) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.structureService = structureService;
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.gridSize = configManager.sharedGridSize();
        this.worldName = configManager.sharedWorldName();
    }

    private static StructureService.PlaceResult failedPlaceResult() {
        return new StructureService.PlaceResult(
                new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MIN_VALUE},
                List.of(), List.of());
    }

    private static String formatArenaId(int gridX, int gridZ) {
        return "shared:" + gridX + ":" + gridZ;
    }

    private static Long parseGridId(String arenaId) {
        if (arenaId == null || !arenaId.startsWith("shared:")) return null;
        String[] parts = arenaId.split(":");
        if (parts.length != 3) return null;
        try {
            int gx = Integer.parseInt(parts[1]);
            int gz = Integer.parseInt(parts[2]);
            if (gx < 0 || gx >= GRID_DIM || gz < 0 || gz >= GRID_DIM) return null;
            return (long) gz * GRID_DIM + gx;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isWithinRegion(Location loc, int[] bounds) {
        return RegionBounds.isWithin2D(loc, bounds);
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void refreshConfig() {
        this.gridSize = configManager.sharedGridSize();
        this.worldName = configManager.sharedWorldName();
    }

    @Override
    public AcquiredArena acquire(UUID ownerUuid, StructureType type) {
        long gridId = allocateGridId();
        int gridX = (int) (gridId % GRID_DIM);
        int gridZ = (int) (gridId / GRID_DIM);
        int worldX = gridX * gridSize;
        int worldZ = gridZ * gridSize;
        int centerX = worldX + gridSize / 2;
        int centerZ = worldZ + gridSize / 2;
        String arenaId = formatArenaId(gridX, gridZ);

        World world = ensureSharedWorld();
        if (world == null) {
            freeGridId(gridId);
            plugin.getLogger().warning("Failed to create shared world for " + ownerUuid);
            return null;
        }

        int[] cleanupBounds = new int[]{
                worldX, worldX + gridSize - 1,
                world.getMinHeight(), world.getMaxHeight() - 1,
                worldZ, worldZ + gridSize - 1
        };
        clearNonPlayerEntitiesInRegion(world, cleanupBounds);

        int keepRadius = Math.max(0, type.keepLoadedRadius());

        int[] regionBounds = new int[]{
                worldX, world.getMinHeight(), worldZ,
                worldX + gridSize - 1, world.getMaxHeight(), worldZ + gridSize - 1
        };
        Location arenaCenter = new Location(world, centerX + 0.5, ARENA_FLOOR_Y + 0.0, centerZ + 0.5);

        AcquiredArena arena = new AcquiredArena(arenaId, ID, world, worldName, regionBounds, arenaCenter, keepRadius);
        arenas.put(arenaId, arena);
        databaseManager.saveArena(arenaId, ID, worldName, ownerUuid.toString(), type.id());
        return arena;
    }

    @Override
    public void release(UUID ownerUuid, String arenaId, int[] structureBounds) {
        readyArenas.remove(arenaId);
        AcquiredArena arena = arenas.remove(arenaId);
        if (arena == null) return;

        Long gridId = parseGridId(arenaId);
        if (gridId == null) {
            plugin.getLogger().warning("SharedWorldStrategy.release: malformed arenaId " + arenaId);
        }

        World world = arena.world();
        if (world == null) {
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            finalizeRelease(arenaId, gridId);
            return;
        }
        final World finalWorld = world;

        int[] region = arena.regionBounds();
        Location safeSpawn = getReturnLocation(null);

        if (region != null) {
            for (Player p : List.copyOf(world.getPlayers())) {
                Location loc = p.getLocation();
                if (isWithinRegion(loc, region)) {
                    try {
                        p.teleport(safeSpawn);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to teleport " + p.getName()
                                + " out of " + world.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        int centerX = arena.arenaCenter().getBlockX();
        int centerZ = arena.arenaCenter().getBlockZ();
        int keepRadius = arena.keepLoadedRadius();
        int centerCX = centerX >> 4;
        int centerCZ = centerZ >> 4;
        for (int dx = -keepRadius; dx <= keepRadius; dx++) {
            for (int dz = -keepRadius; dz <= keepRadius; dz++) {
                finalWorld.removePluginChunkTicket(centerCX + dx, centerCZ + dz, plugin);
            }
        }

        Runnable finalize = () -> finalizeRelease(arenaId, gridId);

        if (structureBounds != null && structureBounds[0] != Integer.MAX_VALUE) {
            worldService.clearRegion(finalWorld, structureBounds, 8, finalize);
        } else {
            finalize.run();
        }
    }

    private void finalizeRelease(String arenaId, Long gridId) {
        if (gridId != null) {
            usedGridIds.remove(gridId);
            freeGridId(gridId);
        }
        databaseManager.deleteArena(arenaId);
    }

    private void clearNonPlayerEntitiesInRegion(World world, int[] bounds) {
        if (world == null || bounds == null) return;
        int x1 = bounds[0], x2 = bounds[1];
        int y1 = bounds[2], y2 = bounds[3];
        int z1 = bounds[4], z2 = bounds[5];
        for (org.bukkit.entity.Entity e : world.getEntities()) {
            if (e instanceof Player) continue;
            Location loc = e.getLocation();
            if (loc.getBlockX() < x1 || loc.getBlockX() > x2) continue;
            if (loc.getBlockY() < y1 || loc.getBlockY() > y2) continue;
            if (loc.getBlockZ() < z1 || loc.getBlockZ() > z2) continue;
            e.remove();
        }
    }

    @Override
    public World resolveWorld(String arenaId) {
        if (arenaId == null) return null;
        AcquiredArena arena = arenas.get(arenaId);
        if (arena != null && arena.world() != null) return arena.world();
        return Bukkit.getWorld(worldName);
    }

    @Override
    public int[] regionBoundsFor(String arenaId) {
        AcquiredArena arena = arenas.get(arenaId);
        return (arena != null) ? arena.regionBounds() : null;
    }

    @Override
    public Location arenaCenterFor(String arenaId) {
        AcquiredArena arena = arenas.get(arenaId);
        return (arena != null) ? arena.arenaCenter() : null;
    }

    @Override
    public boolean isArenaReady(String arenaId) {
        return readyArenas.contains(arenaId);
    }

    @Override
    public boolean isDungeonWorld(World world) {
        if (world == null) return false;
        if (worldName.equals(world.getName())) return true;
        for (AcquiredArena a : arenas.values()) {
            if (a.world() != null && a.world().getUID().equals(world.getUID())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Collection<String> activeArenaIds() {
        return arenas.keySet();
    }

    @Override
    public void place(Player player, AcquiredArena arena, StructureType type, Consumer<StructureService.PlaceResult> onComplete) {
        World world = arena.world();
        if (world == null) {
            onComplete.accept(failedPlaceResult());
            return;
        }

        int centerX = arena.arenaCenter().getBlockX();
        int centerZ = arena.arenaCenter().getBlockZ();
        int chunkRadius = Math.max(0, type.keepLoadedRadius());
        int minCX = (centerX >> 4) - chunkRadius;
        int maxCX = (centerX >> 4) + chunkRadius;
        int minCZ = (centerZ >> 4) - chunkRadius;
        int maxCZ = (centerZ >> 4) + chunkRadius;

        world.getChunksAtAsync(minCX, minCZ, maxCX, maxCZ, false, () ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int platformHalf = Math.max(0, type.platformSize());
                    for (int dx = -platformHalf; dx <= platformHalf; dx++) {
                        for (int dz = -platformHalf; dz <= platformHalf; dz++) {
                            world.getBlockAt(centerX + dx, PLATFORM_Y, centerZ + dz).setType(Material.OBSIDIAN, false);
                        }
                    }
                    int foundationDepth = Math.max(0, type.groundFoundationDepth());
                    int nonZeroDepth = foundationDepth;
                    if (nonZeroDepth > 0) {
                        int foundationY = PLATFORM_Y - nonZeroDepth;
                        for (int dx = -platformHalf; dx <= platformHalf; dx++) {
                            for (int dz = -platformHalf; dz <= platformHalf; dz++) {
                                world.getBlockAt(centerX + dx, foundationY, centerZ + dz).setType(Material.BEDROCK, false);
                            }
                        }
                    }

                    player.sendMessage(plugin.getConfigManager().getMessage("dungeon.building"));

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        player.sendMessage(plugin.getConfigManager().getMessage("dungeon.placing",
                                "structure", type.displayName()));
                        runPreProcessChain(type, world, arena.arenaCenter(), 0, () ->
                                structureService.placeStructure(world, player, type, arena.arenaCenter(), result -> {
                                    if (result.bounds()[0] == Integer.MAX_VALUE) {
                                        onComplete.accept(result);
                                        return;
                                    }
                                    List<BlockVector> spawners = result.spawners();
                                    List<BoundingBox> pieceBoxes = result.pieceBoxes();
                                    runPostProcessChain(type, world, result.bounds(), pieceBoxes, spawners, 0,
                                            () -> {
                                                readyArenas.add(arena.arenaId());
                                                player.setRespawnLocation(arena.arenaCenter(), true);
                                                player.teleport(arena.arenaCenter());
                                                onComplete.accept(result);
                                            });
                                }));
                    }, 20L);
                }));
    }

    private void runPostProcessChain(StructureType type, World world, int[] bounds,
                                     List<BoundingBox> pieceBoxes,
                                     List<BlockVector> spawners, int index, Runnable onComplete) {
        WorldStrategySupport.runPostProcessChain(type, world, bounds, pieceBoxes, spawners, index, onComplete);
    }

    private void runPreProcessChain(StructureType type, World world, Location arenaCenter,
                                    int index, Runnable onComplete) {
        WorldStrategySupport.runPreProcessChain(type, world, arenaCenter, index, onComplete);
    }

    @Override
    public Location getReturnLocation(Location savedRespawn) {
        if (savedRespawn != null) return savedRespawn;
        return getMainWorldSpawn();
    }

    public Location getMainWorldSpawn() {
        return WorldStrategySupport.resolveMainWorldSpawn(configManager, this::isDungeonWorld);
    }

    public void cleanupOrphans() {
        databaseManager.getOrphanArenasAsync().whenComplete((tracked, err) -> {
            if (err != null) {
                plugin.getLogger().warning("Failed to query orphan arenas: " + err.getMessage());
                return;
            }
            if (tracked == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String arenaId : tracked) {
                    cleanupTrackedArena(arenaId);
                }
            });
        });
    }

    public void cleanupTrackedArena(String arenaId) {
        if (arenaId.startsWith("shared:")) {
            Long gridId = parseGridId(arenaId);
            if (gridId != null) {
                usedGridIds.remove(gridId);
                freeGridId(gridId);
            }
            plugin.getLogger().info("Removing orphan shared arena record: " + arenaId);
            databaseManager.deleteArena(arenaId);
        } else {
            plugin.getLogger().info("Removing orphan legacy arena record: " + arenaId);
            databaseManager.deleteArena(arenaId);
        }
    }

    public void cleanupOrphanWorldFolders() {
        File container = Bukkit.getWorldContainer();
        File[] folders = container.listFiles();
        if (folders == null) return;
        boolean async = plugin.isEnabled();
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            String name = folder.getName();
            if (!isUuid(name)) continue;
            if (async) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteFolderRecursive(folder));
            } else {
                deleteFolderRecursive(folder);
            }
        }
    }

    private void deleteFolderRecursive(File folder) {
        try (java.util.stream.Stream<java.nio.file.Path> stream =
                     java.nio.file.Files.walk(folder.toPath())) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (java.io.IOException e) {
                            plugin.getLogger().warning("Failed to delete " + p + ": " + e.getMessage());
                        }
                    });
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Failed to walk " + folder + ": " + e.getMessage());
        }
    }

    public CompletableFuture<List<String>> loadActiveArenasAsync() {
        return databaseManager.getAllArenasAsync().thenCompose(records -> {
            java.util.concurrent.CompletableFuture<List<String>> sync = new java.util.concurrent.CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = ensureSharedWorld();
                if (world == null) {
                    plugin.getLogger().warning("SharedWorldStrategy.loadActiveArenasAsync: shared world not available");
                    sync.complete(List.of());
                    return;
                }
                long maxGridId = -1;
                for (DatabaseManager.ArenaRecord rec : records) {
                    if (!ID.equals(rec.strategyId())) continue;
                    Long gridId = parseGridId(rec.arenaId());
                    if (gridId == null) continue;
                    int gridX = (int) (gridId % GRID_DIM);
                    int gridZ = (int) (gridId / GRID_DIM);
                    int worldX = gridX * gridSize;
                    int worldZ = gridZ * gridSize;
                    int centerX = worldX + gridSize / 2;
                    int centerZ = worldZ + gridSize / 2;
                    String arenaId = rec.arenaId();
                    int[] regionBounds = new int[]{
                            worldX, world.getMinHeight(), worldZ,
                            worldX + gridSize - 1, world.getMaxHeight(), worldZ + gridSize - 1
                    };
                    Location arenaCenter = new Location(world, centerX + 0.5, ARENA_FLOOR_Y + 0.0, centerZ + 0.5);
                    AcquiredArena arena = new AcquiredArena(arenaId, ID, world, worldName, regionBounds, arenaCenter, 0);
                    arenas.put(arenaId, arena);
                    usedGridIds.add(gridId);
                    if (gridId > maxGridId) maxGridId = gridId;
                }
                if (maxGridId + 1 > nextGridId) {
                    nextGridId = maxGridId + 1;
                }
                sync.complete(List.copyOf(arenas.keySet()));
            });
            return sync;
        });
    }

    @Override
    public void shutdown() {
        saveWorld();
    }

    private World ensureSharedWorld() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            WorldCreator creator = new WorldCreator(worldName);
            creator.generator(new VoidGenerator());
            creator.generateStructures(true);
            world = creator.createWorld();
        }
        if (world == null) return null;

        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(SPAWN_MOBS, true);
        world.setGameRule(SPAWN_WARDENS, false);
        world.setGameRule(SPAWN_PATROLS, false);
        world.setGameRule(SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(SPAWN_PHANTOMS, false);
        world.setGameRule(ADVANCE_TIME, false);
        world.setGameRule(ADVANCE_WEATHER, false);
        world.setAutoSave(true);
        world.setSpawnLocation(0, ARENA_FLOOR_Y, 0);

        return world;
    }

    public void saveWorld() {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            try {
                world.save();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save shared world " + worldName + ": " + e.getMessage());
            }
        }
    }

    private long allocateGridId() {
        synchronized (gridLock) {
            Long reused = null;
            for (Long id : freeGridIds) {
                reused = id;
                break;
            }
            if (reused != null) {
                freeGridIds.remove(reused);
                usedGridIds.add(reused);
                return reused;
            }
            long id = nextGridId++;
            usedGridIds.add(id);
            return id;
        }
    }

    private void freeGridId(long gridId) {
        synchronized (gridLock) {
            freeGridIds.add(gridId);
        }
    }
}
