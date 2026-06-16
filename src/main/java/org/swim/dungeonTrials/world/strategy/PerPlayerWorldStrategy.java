package org.swim.dungeonTrials.world.strategy;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.service.StructureService;
import org.swim.dungeonTrials.service.WorldService;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.world.AcquiredArena;
import org.swim.dungeonTrials.world.VoidGenerator;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

@SuppressWarnings("DuplicatedCode")
public class PerPlayerWorldStrategy implements WorldStrategy {

    public static final String ID = "per-player";

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
    private final Set<String> readyArenas = ConcurrentHashMap.newKeySet();
    private final ExecutorService ioExecutor;

    public PerPlayerWorldStrategy(DungeonTrials plugin, WorldService worldService,
                                  StructureService structureService,
                                  DatabaseManager databaseManager,
                                  ConfigManager configManager) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.structureService = structureService;
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DungeonTrials-IO");
            t.setDaemon(true);
            return t;
        });
    }

    private static StructureService.PlaceResult failedPlaceResult() {
        return new StructureService.PlaceResult(
                new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MIN_VALUE},
                List.of(), List.of());
    }

    @Override
    public AcquiredArena acquire(UUID ownerUuid, StructureType type) {
        String arenaId = ownerUuid.toString();

        AcquiredArena existing = arenas.get(arenaId);
        if (existing != null && existing.world() != null) {
            World existingWorld = existing.world();
            if (existingWorld.getPlayers().isEmpty()) {
                purgeExistingArena(existingWorld, type);
            }
            databaseManager.saveArena(arenaId, ID, arenaId, ownerUuid.toString(), type.id());
            return existing;
        }

        World world = Bukkit.getWorld(arenaId);
        if (world == null) {
            WorldCreator creator = new WorldCreator(arenaId);
            creator.generator(new VoidGenerator());
            creator.generateStructures(true);
            world = creator.createWorld();
        }

        if (world == null) {
            plugin.getLogger().warning("Failed to create per-player world for " + ownerUuid);
            return null;
        }

        purgeExistingArena(world, type);

        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(SPAWN_MOBS, true);
        world.setGameRule(SPAWN_WARDENS, false);
        world.setGameRule(SPAWN_PATROLS, false);
        world.setGameRule(SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(SPAWN_PHANTOMS, false);
        world.setGameRule(ADVANCE_TIME, false);
        world.setGameRule(ADVANCE_WEATHER, false);
        world.setAutoSave(false);
        world.setSpawnLocation(0, 80, 0);

        Location center = new Location(world, 0.5, 80.0, 0.5);
        int platformHalf = Math.max(0, type.platformSize());
        for (int x = -platformHalf; x <= platformHalf; x++) {
            for (int z = -platformHalf; z <= platformHalf; z++) {
                world.getBlockAt(x, 79, z).setType(Material.OBSIDIAN, false);
            }
        }

        int foundationDepth = Math.max(0, type.groundFoundationDepth());
        int nonZeroDepth = foundationDepth;
        if (nonZeroDepth > 0) {
            int foundationY = 79 - nonZeroDepth;
            for (int x = -platformHalf; x <= platformHalf; x++) {
                for (int z = -platformHalf; z <= platformHalf; z++) {
                    world.getBlockAt(x, foundationY, z).setType(Material.BEDROCK, false);
                }
            }
        }

        int keepRadius = Math.max(0, type.keepLoadedRadius());
        for (int dx = -keepRadius; dx <= keepRadius; dx++) {
            for (int dz = -keepRadius; dz <= keepRadius; dz++) {
                world.addPluginChunkTicket(dx, dz, plugin);
                world.getChunkAt(dx, dz);
            }
        }

        AcquiredArena arena = new AcquiredArena(arenaId, ID, world, arenaId, null, center, keepRadius);
        arenas.put(arenaId, arena);
        databaseManager.saveArena(arenaId, ID, arenaId, ownerUuid.toString(), type.id());

        return arena;
    }

    @Override
    public void release(UUID ownerUuid, String arenaId, int[] structureBounds) {
        readyArenas.remove(arenaId);
        AcquiredArena arena = arenas.remove(arenaId);
        if (arena == null) return;

        String worldName = arena.worldName();
        World world = arena.world();
        if (world == null) {
            world = Bukkit.getWorld(worldName);
        }

        Path folder = null;
        if (world != null) {
            Location safeSpawn = getMainWorldSpawn();
            for (Player p : List.copyOf(world.getPlayers())) {
                try {
                    p.teleport(safeSpawn);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to teleport " + p.getName()
                            + " out of " + world.getName() + ": " + e.getMessage());
                }
            }

            try {
                world.setAutoSave(false);
                world.removePluginChunkTickets(plugin);
                world.getEntities().stream()
                        .filter(e -> !(e instanceof Player))
                        .forEach(Entity::remove);

                boolean unloaded = Bukkit.unloadWorld(world, false);
                if (!unloaded) {
                    plugin.getLogger().warning("First unload attempt failed for " + world.getName()
                            + ", retrying after entity purge");
                    world.getEntities().stream()
                            .filter(e -> !(e instanceof Player))
                            .forEach(Entity::remove);
                    unloaded = Bukkit.unloadWorld(world, false);
                    if (!unloaded) {
                        plugin.getLogger().warning("Failed to unload world " + world.getName()
                                + ", will be retried on next startup via cleanupOrphans");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Exception during unload of " + world.getName()
                        + ": " + e.getMessage());
            }

            folder = world.getWorldFolder().toPath();
        }

        if (folder != null) {
            deleteFolderAsync(folder);
        }
        databaseManager.deleteArena(arenaId);
    }

    @Override
    public World resolveWorld(String arenaId) {
        if (arenaId == null) return null;
        AcquiredArena arena = arenas.get(arenaId);
        if (arena != null) return arena.world();
        return Bukkit.getWorld(arenaId);
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
        try {
            UUID.fromString(arenaId);
        } catch (IllegalArgumentException e) {
            databaseManager.deleteArena(arenaId);
            return;
        }

        World world = Bukkit.getWorld(arenaId);
        if (world != null) {
            world.setAutoSave(false);
            world.removePluginChunkTickets(plugin);
            world.getEntities().stream()
                    .filter(e -> !(e instanceof Player))
                    .forEach(Entity::remove);
            Bukkit.unloadWorld(world, false);
        }

        arenas.remove(arenaId);

        Path folder = null;
        if (world != null) {
            folder = world.getWorldFolder().toPath();
        } else {
            File candidate = new File(Bukkit.getWorldContainer(), arenaId);
            if (candidate.exists()) {
                folder = candidate.toPath();
            }
        }

        if (folder != null) {
            deleteFolderAsync(folder);
        }
        databaseManager.deleteArena(arenaId);
    }

    public void cleanupOrphanWorldFolders() {
        File container = Bukkit.getWorldContainer();
        File[] folders = container.listFiles();
        if (folders == null) return;

        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            String name = folder.getName();
            if (!isUuid(name)) continue;
            if (Bukkit.getWorld(name) != null) continue;
            deleteFolderAsync(folder.toPath());
        }
    }

    private boolean isUuid(String s) {
        if (s == null) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void deleteFolderAsync(Path folder) {
        if (folder == null || !Files.exists(folder)) return;
        ioExecutor.execute(() -> deleteFolderSync(folder));
    }

    private void deleteFolderSync(Path folder) {
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Failed to delete " + p + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to walk " + folder + ": " + e.getMessage());
        }
    }

    public CompletableFuture<List<String>> loadActiveArenasAsync() {
        return databaseManager.getAllArenasAsync().thenApply(records -> {
            for (DatabaseManager.ArenaRecord rec : records) {
                if (!ID.equals(rec.strategyId())) continue;
                World world = Bukkit.getWorld(rec.worldName());
                if (world == null) continue;
                Location center = new Location(world, 0.5, 80.0, 0.5);
                AcquiredArena arena = new AcquiredArena(
                        rec.arenaId(), ID, world, rec.worldName(), null, center, 0);
                arenas.put(rec.arenaId(), arena);
            }
            return List.copyOf(arenas.keySet());
        });
    }

    @Override
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("DungeonTrials-IO did not terminate in 30s, forcing shutdown");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private void purgeExistingArena(World world, StructureType type) {
        if (world == null) return;
        int keepRadius = Math.max(0, type.keepLoadedRadius());
        int halfChunk = keepRadius * 16 + 16;
        int[] bounds = new int[]{
                -halfChunk, halfChunk,
                world.getMinHeight(), world.getMaxHeight() - 1,
                -halfChunk, halfChunk
        };
        worldService.clearRegion(world, bounds, 0, null);
        for (Entity e : List.copyOf(world.getEntities())) {
            if (e instanceof Player) continue;
            e.remove();
        }
    }
}
