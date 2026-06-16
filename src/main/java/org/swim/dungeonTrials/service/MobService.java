package org.swim.dungeonTrials.service;

import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.config.LevelConfig;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.model.SessionHistory;
import org.swim.dungeonTrials.structure.StructureRegistry;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.util.MonsterTypes;
import org.swim.dungeonTrials.util.Players;
import org.swim.dungeonTrials.world.AcquiredArena;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class MobService implements SessionService.RestoredListener {

    private final DungeonTrials plugin;
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;

    private final RespawnService respawnService;
    private final BossBarService bossBarService;
    private final SessionService sessionService;
    private final WaveService waveService;
    private final VisitorService visitorService;
    private final RewardService rewardService;

    private final Set<String> endingRegions = ConcurrentHashMap.newKeySet();

    @Setter(lombok.AccessLevel.NONE)
    private WorldStrategy worldStrategy;
    @Setter(lombok.AccessLevel.NONE)
    private StructureService structureService;
    @Setter(lombok.AccessLevel.NONE)
    private AdvancementService advancementService;

    public MobService(DungeonTrials plugin, DatabaseManager databaseManager,
                      ConfigManager configManager,
                      WorldStrategy worldStrategy, StructureRegistry structureRegistry,
                      RewardService rewardService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.worldStrategy = worldStrategy;
        this.rewardService = rewardService;

        this.respawnService = new RespawnService();
        this.bossBarService = new BossBarService(configManager);
        this.sessionService = new SessionService(plugin, databaseManager, configManager, structureRegistry, () -> this);
        this.waveService = new WaveService(plugin, configManager, sessionService);
        this.visitorService = new VisitorService(sessionService, respawnService, configManager);

        this.respawnService.setWorldStrategy(worldStrategy);
        this.sessionService.setWorldStrategy(worldStrategy);
        this.sessionService.setBossBarService(bossBarService);
        this.visitorService.setWorldStrategy(worldStrategy);
    }

    public void setWorldStrategy(WorldStrategy worldStrategy) {
        this.worldStrategy = worldStrategy;
        respawnService.setWorldStrategy(worldStrategy);
        sessionService.setWorldStrategy(worldStrategy);
        visitorService.setWorldStrategy(worldStrategy);
    }

    public void setStructureService(StructureService structureService) {
        this.structureService = structureService;
        sessionService.setStructureService(structureService);
    }

    public void setAdvancementService(AdvancementService advancementService) {
        this.advancementService = advancementService;
    }

    @Override
    public void onRestored(SessionService.RestoredInfo info) {
        bossBarService.onMemberAdded(info.region(), info.uuid());
        if (!waveService.hasTaskFor(info.region().regionId())) {
            waveService.startWaveCycle(info.region().regionId(), info.world(),
                    info.region().currentLevel(), info.regionBounds(), info.region().structureId());
        }
        bossBarService.onStateChanged(info.region());
        if (info.region().awaitingInfiniteChoice() && info.region().isOwner(info.uuid())) {
            Player owner = Players.online(info.uuid());
            if (owner != null) {
                owner.sendMessage(configManager.getMessage("infinite.prompt",
                        "kills", String.valueOf(info.region().kills()),
                        "owner", owner.getName()));
            }
        }
    }

    public boolean isAwaitingInfiniteChoice(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        return region != null && region.awaitingInfiniteChoice();
    }

    public CompletableFuture<Integer> loadActiveSessionsAsync() {
        return sessionService.loadActiveRegionsAsync();
    }

    public void cancelAllTasks() {
        waveService.cancelAllTasks();
    }

    public void saveAllActiveSessions() {
        for (RegionState region : sessionService.allRegions()) {
            databaseManager.saveRegionState(region);
        }
    }

    public List<RegionState> allActiveSessions() {
        return sessionService.allRegions();
    }

    public void applyBossBarStyle() {
        bossBarService.applyStyle();
    }

    public void endAll() {
        for (RegionState region : new ArrayList<>(sessionService.allRegions())) {
            endRegion(region.regionId(), "SHUTDOWN");
        }
        respawnService.clearAllMaps();
        bossBarService.clearAll();
        visitorService.clearAll();
    }

    public boolean hasActiveSession(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        return region != null && region.isOwner(playerUuid);
    }

    public boolean isInAnyRegion(UUID playerUuid) {
        return sessionService.isInAnyRegion(playerUuid);
    }

    public RegionState getSession(UUID playerUuid) {
        return sessionService.getRegionByPlayer(playerUuid);
    }

    public boolean isVisitor(UUID playerUuid) {
        return visitorService.isVisitor(playerUuid);
    }

    public String getActiveArenaId(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        return (region != null) ? region.regionId() : null;
    }

    public Location getOrigin(UUID playerUuid) {
        return sessionService.getOrigin(playerUuid);
    }

    public int[] getOwnerBounds(UUID ownerUuid) {
        RegionState region = sessionService.getRegionByPlayer(ownerUuid);
        return (region != null) ? region.structureBounds() : null;
    }

    public List<BlockVector> getOwnerSpawners(UUID ownerUuid) {
        RegionState region = sessionService.getRegionByPlayer(ownerUuid);
        return (region != null) ? region.spawners() : null;
    }

    public void saveOriginalRespawn(UUID playerUuid, Location respawn) {
        respawnService.saveOriginalRespawn(playerUuid, respawn);
    }

    public Location getOriginalRespawn(UUID playerUuid) {
        return respawnService.getOriginalRespawn(playerUuid);
    }

    public void clearOriginalRespawn(UUID playerUuid) {
        respawnService.clearOriginalRespawn(playerUuid);
    }

    public void restoreBedSpawn(UUID playerUuid, Location original) {
        respawnService.restoreBedSpawn(playerUuid, original);
    }

    public void applyPendingRespawn(UUID playerUuid) {
        respawnService.applyPendingRespawn(playerUuid);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean tryBeginEnd(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region == null) return true;
        return endingRegions.add(region.regionId());
    }

    public void endEnd(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region != null) {
            endingRegions.remove(region.regionId());
        }
    }

    public RegionState startSession(Player player, AcquiredArena arena, int level, Location origin,
                                    int[] bounds, List<BlockVector> spawners, StructureType type) {
        UUID uuid = player.getUniqueId();

        visitorService.endIfVisitor(uuid);
        respawnService.consumePending(uuid);

        RegionState region = sessionService.createOrJoinRegion(player, arena, level, bounds, spawners, type, origin);
        if (region == null) return null;

        waveService.startWaveCycle(region.regionId(), arena.world(), level,
                region.arenaRegion(), region.structureId());
        bossBarService.onStateChanged(region);
        return region;
    }

    public void attachBossBar(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region == null) return;
        bossBarService.onMemberAdded(region, playerUuid);
    }

    public void detachBossBar(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region == null) return;
        bossBarService.onMemberRemoved(region, playerUuid);
    }

    public void checkAndStartWaves(Player player, World world) {
        waveService.checkAndStartWaves(player, world);
    }

    public void restoreSession(Player player) {
        sessionService.restorePlayer(player);
    }

    public UUID findDungeonOwnerNear(Location loc, World world) {
        RegionState region = sessionService.findNearestRegion(loc, world);
        return (region != null) ? region.ownerUuid() : null;
    }

    public void onKill(Entity killed) {
        if (!(killed instanceof LivingEntity living)) return;
        if (!MonsterTypes.isMonster(killed.getType())) return;

        Player killer = living.getKiller();
        if (killer != null) {
            rewardService.grantKillRewards(killer, living.getLocation(), living.getType());
        }

        String regionId = waveService.findEntityRegion(killed.getUniqueId());
        RegionState region = (regionId != null) ? sessionService.getRegion(regionId) : null;
        if (region == null) {
            region = sessionService.findNearestRegion(killed.getLocation(), killed.getWorld());
            if (region == null) return;
        }

        if (region.awaitingInfiniteChoice()) return;

        LevelConfig config = configManager.getEffectiveLevelConfig(region.currentLevel(), region.structureId());
        if (config == null) return;

        region.incrementKills();
        sessionService.persistRegion(region);

        int requirement = config.clearRequirement();
        if (requirement > 0 && region.kills() >= requirement) {
            offerLevelUp(region);
        }

        bossBarService.onStateChanged(region);
    }

    public void cleanupSpawnTracking(Entity entity) {
        waveService.cleanupSpawnTracking(entity);
    }

    public void declineLevelUp(Player player) {
        UUID uuid = player.getUniqueId();
        RegionState region = sessionService.getRegionByPlayer(uuid);
        if (region == null) return;
        if (!region.isOwner(uuid)) return;

        region.kills(0);
        region.awaitingInfiniteChoice(false);
        sessionService.persistRegion(region);
        Player owner = Players.online(uuid);
        if (owner != null) {
            String ownerName = owner.getName();
            owner.sendMessage(configManager.getMessage("dungeon.staying",
                    "level", String.valueOf(region.currentLevel()),
                    "owner", ownerName));
        }
        bossBarService.onStateChanged(region);
    }

    public boolean enterInfiniteMode(Player player) {
        return enterInfiniteMode(player, false);
    }

    public boolean enterInfiniteMode(Player player, boolean force) {
        UUID uuid = player.getUniqueId();
        RegionState region = sessionService.getRegionByPlayer(uuid);
        if (region == null) return false;
        if (!region.isOwner(uuid)) return false;
        if (!force && !region.awaitingInfiniteChoice()) return false;

        if (advancementService != null) {
            advancementService.awardLevelClear(player, 5);
        }
        region.infinite(true);
        region.awaitingInfiniteChoice(false);
        advanceLevel(player, 6);
        return true;
    }

    public boolean declineInfiniteMode(Player player) {
        UUID uuid = player.getUniqueId();
        RegionState region = sessionService.getRegionByPlayer(uuid);
        if (region == null) return false;
        if (!region.isOwner(uuid)) return false;
        if (!region.awaitingInfiniteChoice()) return false;

        player.sendMessage(configManager.getMessage("infinite.declined"));
        endRegion(region.regionId(), "DECLINED_INFINITE");
        return true;
    }

    public void advanceLevel(Player player, int newLevel) {
        UUID uuid = player.getUniqueId();
        RegionState region = sessionService.getRegionByPlayer(uuid);
        if (region == null) return;
        if (!region.isOwner(uuid)) return;

        World world = (worldStrategy != null) ? worldStrategy.resolveWorld(region.regionId()) : null;
        if (world == null) {
            plugin.getLogger().warning("advanceLevel: region " + region.regionId() + " is no longer loaded");
            return;
        }

        int oldLevel = region.currentLevel();
        boolean wasInfinite = region.infinite();

        region.currentLevel(newLevel);
        region.kills(0);
        region.awaitingInfiniteChoice(false);
        sessionService.persistRegion(region);

        if (wasInfinite) {
            rewardService.grantInfiniteBonus(player);
            if (advancementService != null) {
                advancementService.awardInfinite(player);
            }
        } else if (newLevel > oldLevel) {
            rewardService.grantLevelClearBonus(player, oldLevel);
            if (advancementService != null) {
                advancementService.awardLevelClear(player, oldLevel);
                if (oldLevel == 5 && newLevel == 6) {
                    advancementService.awardInfinite(player);
                }
            }
        }

        waveService.startWaveCycle(region.regionId(), world, newLevel,
                region.arenaRegion(), region.structureId());

        player.sendMessage(configManager.getMessage("dungeon.advanced",
                "level", String.valueOf(newLevel)));
        bossBarService.onStateChanged(region);
    }

    public void endSession(UUID playerUuid, String reason) {
        endSession(playerUuid, reason, true);
    }

    public void endSession(UUID playerUuid, String reason, boolean teleportPlayer) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region == null) return;
        if (!region.isOwner(playerUuid)) {
            removeMemberFromRegion(playerUuid, teleportPlayer);
            return;
        }
        endRegion(region.regionId(), reason);
    }

    public void endRegion(String regionId, String reason) {
        if (!endingRegions.add(regionId)) return;
        try {
            RegionState region = sessionService.getRegion(regionId);
            if (region == null) return;

            Set<UUID> members = new LinkedHashSet<>(region.members());
            UUID ownerUuid = region.ownerUuid();
            Player owner = Players.online(ownerUuid);
            String ownerName = (owner != null) ? owner.getName() : ownerUuid.toString();

            if ("OWNER_DIED".equals(reason)) {
                for (UUID m : members) {
                    Player p = Players.online(m);
                    if (p != null) {
                        p.sendMessage(configManager.getMessage("dungeon.owner-died-broadcast",
                                "owner", ownerName));
                        p.sendMessage(configManager.getMessage("dungeon.closed",
                                "owner", ownerName));
                    }
                }
            } else {
                for (UUID m : members) {
                    if (m.equals(ownerUuid)) continue;
                    Player p = Players.online(m);
                    if (p != null) {
                        p.sendMessage(configManager.getMessage("command.kick.owner-ended-session",
                                "owner", ownerName));
                    }
                }
            }

            for (UUID member : members) {
                Player p = Players.online(member);
                Location original = respawnService.getOriginalRespawn(member);
                respawnService.restoreBedSpawn(member, original);
                respawnService.clearOriginalRespawn(member);
                if (p != null && worldStrategy != null) {
                    Location target = worldStrategy.getReturnLocation(original);
                    if (target != null) {
                        p.teleport(target);
                    }
                }
            }

            visitorService.clearOwnerInvites(ownerUuid);

            waveService.cancelAndCleanupFor(regionId);

            int[] structureBounds = region.structureBounds();
            if (worldStrategy != null) {
                worldStrategy.releaseRegion(regionId, structureBounds);
            }

            long duration = (System.currentTimeMillis() - region.startTime()) / 1000;
            databaseManager.saveSessionHistory(new SessionHistory(
                    ownerUuid.toString(),
                    region.structureId(),
                    region.currentLevel(),
                    region.kills(),
                    duration,
                    System.currentTimeMillis(),
                    reason
            ));

            sessionService.endRegion(regionId, reason);
        } finally {
            endingRegions.remove(regionId);
        }
    }

    public void removeMemberFromRegion(UUID playerUuid, boolean teleportPlayer) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        if (region == null) return;
        if (region.isOwner(playerUuid)) return;

        Player player = Players.online(playerUuid);
        if (player != null) {
            String ownerName = ownerNameOrUuid(region.ownerUuid());
            player.sendMessage(configManager.getMessage("command.kick.you-were-kicked",
                    "owner", ownerName));
        }
        Location original = respawnService.getOriginalRespawn(playerUuid);
        sessionService.removeMember(playerUuid);
        respawnService.restoreBedSpawn(playerUuid, original);
        respawnService.clearOriginalRespawn(playerUuid);
        if (teleportPlayer && player != null && worldStrategy != null) {
            Location target = worldStrategy.getReturnLocation(original);
            if (target != null) {
                player.teleport(target);
            }
        }
    }

    private String ownerNameOrUuid(UUID ownerUuid) {
        if (ownerUuid == null) return "?";
        Player p = Players.online(ownerUuid);
        return (p != null) ? p.getName() : ownerUuid.toString();
    }

    private void offerLevelUp(RegionState region) {
        if (region.infinite()) {
            Player owner = Players.online(region.ownerUuid());
            if (owner != null) {
                advanceLevel(owner, region.currentLevel() + 1);
            }
            return;
        }

        if (region.currentLevel() == 5) {
            region.awaitingInfiniteChoice(true);
            sessionService.persistRegion(region);
            waveService.cancelAndCleanupFor(region.regionId());
            Player owner = Players.online(region.ownerUuid());
            if (owner != null) {
                String ownerName = owner.getName();
                owner.sendMessage(configManager.getMessage("infinite.prompt",
                        "kills", String.valueOf(region.kills()),
                        "owner", ownerName));
            }
            bossBarService.onStateChanged(region);
            return;
        }

        int nextLevel = region.currentLevel() + 1;
        Player owner = Players.online(region.ownerUuid());
        if (owner != null) {
            String ownerName = owner.getName();
            owner.sendMessage(configManager.getMessage("level-up.prompt",
                    "kills", String.valueOf(region.kills()),
                    "next", String.valueOf(nextLevel),
                    "owner", ownerName));
        }
    }

    public boolean invite(UUID ownerUuid, UUID visitorUuid) {
        return visitorService.invite(ownerUuid, visitorUuid);
    }

    public UUID consumePendingInvite(UUID visitorUuid, UUID ownerUuid) {
        return visitorService.consumePendingInvite(visitorUuid, ownerUuid);
    }

    public void clearPendingInvite(UUID visitorUuid) {
        visitorService.clearPendingInvite(visitorUuid);
    }

    public UUID getPendingInviteOwner(UUID visitorUuid) {
        return visitorService.getPendingInviteOwner(visitorUuid);
    }

    public boolean addVisitor(UUID ownerUuid, UUID visitorUuid) {
        return visitorService.addVisitor(ownerUuid, visitorUuid);
    }

    public void removeVisitor(UUID visitorUuid) {
        visitorService.removeVisitor(visitorUuid);
    }

    public void endVisitorSession(UUID visitorUuid) {
        visitorService.endVisitorSession(visitorUuid);
    }

    public UUID getOwnerOf(UUID visitorUuid) {
        return visitorService.getOwnerOf(visitorUuid);
    }

    public Set<UUID> getVisitorsOf(UUID ownerUuid) {
        return visitorService.getVisitorsOf(ownerUuid);
    }
}
