package org.swim.dungeonTrials.service;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.config.LevelConfig;
import org.swim.dungeonTrials.model.MobSpawnRule;
import org.swim.dungeonTrials.model.MobSpawnState;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.util.Players;
import org.swim.dungeonTrials.util.RegionBounds;
import org.swim.dungeonTrials.util.Worlds;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class WaveService {

    private static final int MAX_ALIVE = 30;
    private static final int DESPAWN_DISTANCE_SQUARED = 32 * 32;
    private static final int DESPAWN_INTERVAL_TICKS = 60;
    private static final int MAX_SPAWN_RADIUS = 10;
    private static final int SPAWN_ATTEMPTS = 20;
    private static final long SPAWN_FAIL_COOLDOWN_TICKS = 100L;

    private static final double LEVEL_INTENSITY_LEVEL_COEFF = 0.1;
    private static final double LEVEL_INTENSITY_N_COEFF = 0.05;

    private static final Set<Material> UNSAFE_SPAWN_FLOORS = Set.of(
            Material.CACTUS, Material.MAGMA_BLOCK, Material.SWEET_BERRY_BUSH,
            Material.POINTED_DRIPSTONE, Material.LAVA, Material.FIRE, Material.WATER,
            Material.POWDER_SNOW, Material.COBWEB, Material.WITHER_ROSE,
            Material.SOUL_FIRE, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.HONEY_BLOCK,
            Material.SLIME_BLOCK, Material.BARRIER, Material.BEDROCK
    );

    private final DungeonTrials plugin;
    private final ConfigManager configManager;
    private final SessionService sessionService;

    private final Map<String, BukkitTask> regionWaveTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> regionDespawnTasks = new ConcurrentHashMap<>();
    private final Map<String, Map<MobSpawnRule, MobSpawnState>> regionSpawnStates = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> regionSpawnedEntities = new ConcurrentHashMap<>();
    private final Map<UUID, String> entityToRegion = new ConcurrentHashMap<>();
    private final Map<UUID, MobSpawnRule> entityToRule = new ConcurrentHashMap<>();

    public WaveService(DungeonTrials plugin, ConfigManager configManager, SessionService sessionService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.sessionService = sessionService;
    }

    public void startWaveCycle(String regionId, World world, int level, int[] arenaRegion, String structureId) {
        LevelConfig config = configManager.getEffectiveLevelConfig(level, structureId);
        if (config == null || config.mobs().isEmpty()) return;

        removeSpawnedEntities(regionId);

        Map<MobSpawnRule, MobSpawnState> stateMap = new HashMap<>();
        for (MobSpawnRule rule : config.mobs()) {
            MobSpawnState s = new MobSpawnState();
            s.nmsType = CraftEntityType.bukkitToMinecraft(rule.entityType());
            stateMap.put(rule, s);
        }
        regionSpawnStates.put(regionId, stateMap);

        cancelTask(regionWaveTasks, regionId);

        CraftWorld craftWorld = (world instanceof CraftWorld) ? (CraftWorld) world : null;
        if (craftWorld == null) return;
        ServerLevel nmsLevel = craftWorld.getHandle();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                runWaveTick(regionId, craftWorld, nmsLevel, arenaRegion, config), 20L, 1L);
        regionWaveTasks.put(regionId, task);

        cancelTask(regionDespawnTasks, regionId);
        BukkitTask despawnTask = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                        runDespawnTick(regionId, world, arenaRegion),
                DESPAWN_INTERVAL_TICKS, DESPAWN_INTERVAL_TICKS);
        regionDespawnTasks.put(regionId, despawnTask);
    }

    public void checkAndStartWaves(Player player, World world) {
        UUID uuid = player.getUniqueId();
        RegionState region = sessionService.getRegionByPlayer(uuid);
        if (region == null) return;
        if (region.awaitingInfiniteChoice()) return;
        if (regionWaveTasks.containsKey(region.regionId())) return;

        startWaveCycle(region.regionId(), world, region.currentLevel(),
                region.arenaRegion(), region.structureId());
    }

    public boolean hasTaskFor(String regionId) {
        return regionWaveTasks.containsKey(regionId);
    }

    public void cancelAndCleanupFor(String regionId) {
        cancelTask(regionWaveTasks, regionId);
        cancelTask(regionDespawnTasks, regionId);
        removeSpawnedEntities(regionId);
        Map<MobSpawnRule, MobSpawnState> state = regionSpawnStates.remove(regionId);
        if (state != null) {
            state.values().forEach(MobSpawnState::reset);
        }
    }

    public void cancelAllTasks() {
        for (BukkitTask t : new ArrayList<>(regionWaveTasks.values())) {
            t.cancel();
        }
        regionWaveTasks.clear();
        for (BukkitTask t : new ArrayList<>(regionDespawnTasks.values())) {
            t.cancel();
        }
        regionDespawnTasks.clear();
    }

    public String findEntityRegion(UUID entityUuid) {
        return entityToRegion.get(entityUuid);
    }

    public void cleanupSpawnTracking(Entity entity) {
        if (entity == null) return;
        UUID entityUuid = entity.getUniqueId();

        MobSpawnRule rule = entityToRule.remove(entityUuid);
        if (rule == null) return;

        String regionId = entityToRegion.remove(entityUuid);

        if (regionId != null) {
            Set<UUID> entities = regionSpawnedEntities.get(regionId);
            if (entities != null) {
                entities.remove(entityUuid);
            }

            Map<MobSpawnRule, MobSpawnState> state = regionSpawnStates.get(regionId);
            if (state != null) {
                MobSpawnState s = state.get(rule);
                if (s != null) {
                    s.aliveCount = Math.max(0, s.aliveCount - 1);
                    s.totalSpawned = Math.max(0, s.totalSpawned - 1);
                }
            }
        }
    }

    private void runWaveTick(String regionId, CraftWorld world, ServerLevel nmsLevel,
                             int[] arenaRegion, LevelConfig config) {
        if (nmsLevel.players().isEmpty()) return;

        List<Player> onlineInRegion = onlineMembersInRegion(world, regionId, arenaRegion);
        int n = onlineInRegion.size();
        if (n == 0) return;

        long tick = Bukkit.getCurrentTick();
        Map<MobSpawnRule, MobSpawnState> state = regionSpawnStates.get(regionId);
        if (state == null) return;

        int alive = 0;
        for (MobSpawnState s : state.values()) alive += s.aliveCount;
        if (alive >= MAX_ALIVE) return;

        RegionState region = sessionService.getRegion(regionId);
        int currentLevel = (region != null) ? region.currentLevel() : 1;
        double mult = levelIntensityMultiplier(currentLevel, n);

        List<MobSpawnRule> shuffled = new ArrayList<>(config.mobs());
        Collections.shuffle(shuffled, ThreadLocalRandom.current());

        for (MobSpawnRule rule : shuffled) {
            if (alive >= MAX_ALIVE) return;

            MobSpawnState s = state.get(rule);
            if (s == null) continue;
            int simCap = Math.max(1, (int) Math.round(rule.profile().simultaneousCap(n) * mult));
            int totalCap = Math.max(0, (int) Math.round(rule.profile().totalCap(n) * mult));
            int interval = Math.max(5, (int) Math.round(rule.profile().intervalTicks() / mult));
            if (s.aliveCount >= simCap) continue;
            if (tick < s.nextSpawnTick) continue;
            if (s.totalSpawned >= totalCap) continue;

            Player target = pickRandomPlayer(onlineInRegion);
            if (target == null) return;

            StructureType ownerType = (region != null) ? region.structureType() : null;
            Entity entity = spawnMob(world, nmsLevel, target.getLocation(), rule.entityType(), s.nmsType, ownerType);
            if (entity != null) {
                s.totalSpawned++;
                s.aliveCount++;
                s.nextSpawnTick = tick + interval;
                alive++;

                Set<UUID> entities = regionSpawnedEntities.computeIfAbsent(regionId, _ -> ConcurrentHashMap.newKeySet());
                entities.add(entity.getUniqueId());
                entityToRule.put(entity.getUniqueId(), rule);
                entityToRegion.put(entity.getUniqueId(), regionId);
            } else {
                s.nextSpawnTick = tick + SPAWN_FAIL_COOLDOWN_TICKS;
            }
        }
    }

    private void runDespawnTick(String regionId, World world, int[] arenaRegion) {
        if (!Worlds.isLoaded(world)) return;

        Set<UUID> entities = regionSpawnedEntities.get(regionId);
        if (entities == null || entities.isEmpty()) return;

        List<Player> players = onlineMembersInRegion(world, regionId, arenaRegion);
        Set<UUID> toRemove = new HashSet<>();

        for (UUID entityUuid : entities) {
            Entity entity = Bukkit.getEntity(entityUuid);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                toRemove.add(entityUuid);
                recordDespawn(regionId, entityUuid);
                continue;
            }
            if (players.isEmpty()) {
                entity.remove();
                toRemove.add(entityUuid);
                recordDespawn(regionId, entityUuid);
                continue;
            }
            double minDist = Double.MAX_VALUE;
            for (Player p : players) {
                double dist = entity.getLocation().distanceSquared(p.getLocation());
                if (dist < minDist) minDist = dist;
            }
            if (minDist > DESPAWN_DISTANCE_SQUARED) {
                entity.remove();
                toRemove.add(entityUuid);
                recordDespawn(regionId, entityUuid);
            }
        }
        entities.removeAll(toRemove);
        toRemove.forEach(entityToRule::remove);
        toRemove.forEach(entityToRegion::remove);
    }

    private Player pickRandomPlayer(List<Player> nonSpec) {
        if (nonSpec.isEmpty()) return null;
        return nonSpec.get(ThreadLocalRandom.current().nextInt(nonSpec.size()));
    }

    private List<Player> onlineMembersInRegion(World world, String regionId, int[] regionBounds) {
        RegionState region = sessionService.getRegion(regionId);
        if (region == null) return List.of();
        List<Player> result = new ArrayList<>();
        for (UUID member : region.members()) {
            Player p = Players.online(member);
            if (p == null) continue;
            if (!p.isOnline()) continue;
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getWorld() != world) continue;
            if (regionBounds != null && !RegionBounds.isWithin2D(p.getLocation(), regionBounds)) continue;
            result.add(p);
        }
        return result;
    }

    private Entity spawnMob(World world, ServerLevel level, Location center,
                            EntityType type, net.minecraft.world.entity.EntityType<?> nmsType,
                            StructureType ownerType) {
        if (!world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) return null;
        try {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                double angle = rng.nextDouble() * 2 * Math.PI;
                double radius = 3 + rng.nextDouble() * (MAX_SPAWN_RADIUS - 3);
                double x = center.getX() + radius * Math.cos(angle);
                double z = center.getZ() + radius * Math.sin(angle);
                int cx = (int) Math.floor(x);
                int cz = (int) Math.floor(z);
                int baseY = center.getBlockY();
                int minY = world.getMinHeight() + 1;

                for (int dy = -3; dy <= 3; dy++) {
                    int y = baseY + dy;
                    if (y <= minY) continue;
                    Material below = world.getBlockAt(cx, y - 1, cz).getType();
                    if (!below.isSolid()) continue;
                    if (isUnsafeSpawnFloor(ownerType, below)) continue;

                    AABB aabb = nmsType.getSpawnAABB(x, y, z);
                    if (!level.noCollision(aabb)) continue;

                    Location spawnLoc = new Location(world, x, y, z);
                    return world.spawnEntity(spawnLoc, type);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("spawnMob failed: " + e.getMessage());
        }
        return null;
    }

    private boolean isUnsafeSpawnFloor(StructureType type, Material material) {
        if (UNSAFE_SPAWN_FLOORS.contains(material)) return true;
        if (type == null) return false;
        return type.unsafeSpawnFloors().contains(material);
    }

    private double levelIntensityMultiplier(int level, int n) {
        if (level <= 5) return 1.0;
        double levelPart = LEVEL_INTENSITY_LEVEL_COEFF * (level - 5);
        double nPart = LEVEL_INTENSITY_N_COEFF * Math.max(0, n - 1);
        return 1.0 + levelPart + nPart;
    }

    private void removeSpawnedEntities(String regionId) {
        Set<UUID> entities = regionSpawnedEntities.put(regionId, ConcurrentHashMap.newKeySet());
        if (entities == null) return;
        for (UUID eUuid : entities) {
            Entity e = Bukkit.getEntity(eUuid);
            if (e != null && e.isValid()) {
                e.remove();
            }
            entityToRule.remove(eUuid);
            entityToRegion.remove(eUuid);
        }
        entities.clear();
    }

    private void cancelTask(Map<String, BukkitTask> tasks, String regionId) {
        BukkitTask existing = tasks.remove(regionId);
        if (existing != null) existing.cancel();
    }

    private void recordDespawn(String regionId, UUID entityUuid) {
        MobSpawnRule rule = entityToRule.get(entityUuid);
        if (rule == null) return;
        Map<MobSpawnRule, MobSpawnState> state = regionSpawnStates.get(regionId);
        if (state == null) return;
        MobSpawnState s = state.get(rule);
        if (s == null) return;
        s.aliveCount = Math.max(0, s.aliveCount - 1);
    }
}
