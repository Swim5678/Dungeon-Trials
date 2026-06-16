package org.swim.dungeonTrials;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;
import org.jspecify.annotations.NonNull;
import org.swim.dungeonTrials.admin.AdminHandler;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.model.PlayerAggregate;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.service.*;
import org.swim.dungeonTrials.structure.StructureRegistry;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.structure.builtin.TrialChambersStructureType;
import org.swim.dungeonTrials.structure.builtin.WoodlandMansionStructureType;
import org.swim.dungeonTrials.util.PlayerResolver;
import org.swim.dungeonTrials.world.AcquiredArena;
import org.swim.dungeonTrials.world.WorldStrategy;
import org.swim.dungeonTrials.world.strategy.PerPlayerWorldStrategy;
import org.swim.dungeonTrials.world.strategy.SharedWorldStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

public final class DungeonTrials extends JavaPlugin implements Listener {

    private static final int DISTANCE_THRESHOLD = 5;

    @Getter
    private ConfigManager configManager;
    @Getter
    private DatabaseManager databaseManager;
    @Getter
    private WorldService worldService;
    @Getter
    private StructureRegistry structureRegistry;
    private StructureService structureService;
    private PlayerService playerService;
    @Getter
    private MobService mobService;
    @Getter
    private WorldStrategy worldStrategy;
    @Getter
    private DynamicLightService dynamicLightService;
    @Getter
    private StatsService statsService;
    @Getter
    private RewardService rewardService;
    @Getter
    private AdvancementService advancementService;
    private AdminHandler adminHandler;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        databaseManager = new DatabaseManager(this, getDataFolder());
        databaseManager.init();

        worldService = new WorldService(this);
        playerService = new PlayerService();
        structureRegistry = new StructureRegistry();
        structureRegistry.register(new TrialChambersStructureType(worldService, playerService));
        structureRegistry.register(new WoodlandMansionStructureType(worldService, playerService));
        structureService = new StructureService(this);

        rewardService = new RewardService(this, configManager);
        rewardService.reload();

        mobService = new MobService(this, databaseManager, configManager,
                worldStrategy, structureRegistry, rewardService);
        worldStrategy = createWorldStrategy();
        mobService.setWorldStrategy(worldStrategy);
        mobService.setStructureService(structureService);

        advancementService = new AdvancementService(this, configManager);
        advancementService.install();
        mobService.setAdvancementService(advancementService);

        statsService = new StatsService(databaseManager, configManager.topCacheSeconds());

        adminHandler = new AdminHandler(this, databaseManager, worldStrategy, mobService, structureRegistry);

        dynamicLightService = new DynamicLightService(this, configManager);

        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();

        if (worldStrategy instanceof SharedWorldStrategy shared) {
            shared.loadActiveArenasAsync().whenComplete((result, ex) -> {
                if (ex != null) {
                    getLogger().warning("Failed to load active arenas: " + ex.getMessage());
                }
            });
        }

        mobService.loadActiveSessionsAsync();

        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (worldStrategy instanceof PerPlayerWorldStrategy pp) {
                pp.cleanupOrphanWorldFolders();
                pp.cleanupOrphans();
            } else if (worldStrategy instanceof SharedWorldStrategy shared) {
                shared.cleanupOrphans();
                shared.cleanupOrphanWorldFolders();
            }
        }), 20L);
    }

    private WorldStrategy createWorldStrategy() {
        String mode = configManager.worldStrategyMode();
        if ("shared".equalsIgnoreCase(mode)) {
            return new SharedWorldStrategy(this, worldService, structureService,
                    databaseManager, configManager);
        }
        if ("per-player".equalsIgnoreCase(mode)) {
            return new PerPlayerWorldStrategy(this, worldService, structureService,
                    databaseManager, configManager);
        }
        getLogger().warning("Unknown world-strategy mode '" + mode + "', falling back to shared");
        return new SharedWorldStrategy(this, worldService, structureService,
                databaseManager, configManager);
    }

    @Override
    public void onDisable() {
        if (mobService != null) {
            mobService.endAll();
            mobService.cancelAllTasks();
            mobService.saveAllActiveSessions();
        }
        if (dynamicLightService != null) {
            dynamicLightService.shutdown();
        }
        Bukkit.getScheduler().cancelTasks(this);

        if (worldStrategy instanceof SharedWorldStrategy shared) {
            shared.saveWorld();
        }

        if (worldStrategy instanceof PerPlayerWorldStrategy pp) {
            pp.cleanupOrphanWorldFolders();
        } else if (worldStrategy instanceof SharedWorldStrategy shared) {
            shared.cleanupOrphanWorldFolders();
        }

        worldStrategy.shutdown();

        if (databaseManager != null) {
            databaseManager.flushAsyncWrites();
            databaseManager.close();
        }
    }

    private void registerCommands() {
        CommandMap map = Bukkit.getCommandMap();

        map.register("dt", "dungeontrials", new Command("dt") {
            @Override
            public boolean execute(@NonNull CommandSender sender, @NonNull String label, String @NonNull [] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(configManager.getMessage("command.player-only"));
                    return true;
                }

                if (args.length == 0) {
                    sendHelp(player);
                    return true;
                }

                return switch (args[0].toLowerCase()) {
                    case "start" -> {
                        handleStart(player, args);
                        yield true;
                    }
                    case "next" -> {
                        handleNext(player, args);
                        yield true;
                    }
                    case "stay" -> {
                        handleStay(player);
                        yield true;
                    }
                    case "exit" -> {
                        handleExit(player);
                        yield true;
                    }
                    case "stats" -> {
                        handleStats(player, args);
                        yield true;
                    }
                    case "top" -> {
                        handleTop(player, args);
                        yield true;
                    }
                    case "invite" -> {
                        handleInvite(player, args);
                        yield true;
                    }
                    case "visit" -> {
                        handleVisit(player, args);
                        yield true;
                    }
                    case "decline" -> {
                        handleDecline(player);
                        yield true;
                    }
                    case "leave" -> {
                        handleLeave(player);
                        yield true;
                    }
                    case "kick" -> {
                        handleKick(player, args);
                        yield true;
                    }
                    case "infinite" -> {
                        handleInfinite(player);
                        yield true;
                    }
                    case "end" -> {
                        handleEnd(player);
                        yield true;
                    }
                    case "arena" -> {
                        adminHandler.handleArena(player, args);
                        yield true;
                    }
                    case "session" -> {
                        adminHandler.handleSession(player, args);
                        yield true;
                    }
                    case "reload" -> {
                        handleReload(player);
                        yield true;
                    }
                    default -> {
                        sendHelp(player);
                        yield true;
                    }
                };
            }

            @Override
            public @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String label, String @NonNull [] args) {
                if (args.length == 1) {
                    return List.of("start", "next", "stay", "exit", "stats",
                            "top", "invite", "visit", "decline", "leave", "kick",
                            "infinite", "end", "arena", "session", "help", "reload");
                }
                if (args.length == 2) {
                    String head = args[0].toLowerCase();
                    switch (head) {
                        case "start", "next" -> {
                            return List.of("1", "2", "3", "4", "5");
                        }
                        case "invite", "visit", "kick" -> {
                            return filterOnlinePlayers(args[1]);
                        }
                        case "stats" -> {
                            return List.of("structures");
                        }
                        case "top" -> {
                            return structureRegistry.ids();
                        }
                        case "arena" -> {
                            return List.of("list", "info", "release", "cleanup-orphans");
                        }
                        case "session" -> {
                            return List.of("list", "end");
                        }
                        default -> {
                        }
                    }
                }
                if (args.length == 3) {
                    String head = args[0].toLowerCase();
                    switch (head) {
                        case "start" -> {
                            return structureRegistry.ids();
                        }
                        case "top" -> {
                            return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
                        }
                        case "session" -> {
                            if (args[1].equalsIgnoreCase("end")) {
                                return filterOnlinePlayers(args[2]);
                            }
                        }
                        default -> {
                        }
                    }
                }
                return List.of();
            }
        });
    }

    private List<String> filterOnlinePlayers(String prefix) {
        String lower = (prefix == null) ? "" : prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            if (lower.isEmpty() || name.toLowerCase().startsWith(lower)) {
                matches.add(name);
            }
        }
        return matches;
    }

    private void sendHelp(Player player) {
        player.sendMessage(configManager.getMessage("help.header"));
        player.sendMessage(configManager.getMessage("help.start"));
        player.sendMessage(configManager.getMessage("help.next"));
        player.sendMessage(configManager.getMessage("help.stay"));
        player.sendMessage(configManager.getMessage("help.exit"));
        player.sendMessage(configManager.getMessage("help.stats"));
        player.sendMessage(configManager.getMessage("help.top"));
        player.sendMessage(configManager.getMessage("help.invite"));
        player.sendMessage(configManager.getMessage("help.visit"));
        player.sendMessage(configManager.getMessage("help.decline"));
        player.sendMessage(configManager.getMessage("help.leave"));
        player.sendMessage(configManager.getMessage("help.kick"));
        player.sendMessage(configManager.getMessage("help.infinite"));
        player.sendMessage(configManager.getMessage("help.end"));
        player.sendMessage(configManager.getMessage("help.help"));
    }

    private void handleStart(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        OptionalInt levelOpt = parseLevelArg(args, 1, player);
        if (levelOpt.isEmpty()) return;
        int level = levelOpt.getAsInt();

        String structureId = configManager.defaultStructure();
        if (args.length >= 3) {
            structureId = args[2];
        }
        StructureType finalType = structureRegistry.get(structureId).orElse(null);
        if (finalType == null) {
            player.sendMessage(configManager.getMessage("command.unknown-structure",
                    "structure", structureId,
                    "available", String.join(", ", structureRegistry.ids())));
            return;
        }

        if (mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.start.already-in-dungeon"));
            return;
        }

        if (mobService.isInAnyRegion(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getSession(uuid).ownerUuid());
            player.sendMessage(configManager.getMessage("command.start.already-in-region",
                    "owner", ownerName));
            return;
        }

        if (worldStrategy.isDungeonWorld(player.getWorld())) {
            player.sendMessage(configManager.getMessage("command.visitor-warning"));
        }

        player.sendMessage(configManager.getMessage("dungeon.creating"));

        AcquiredArena arena = worldStrategy.acquire(uuid, finalType);
        if (arena == null) {
            player.sendMessage(configManager.getMessage("dungeon.create-failed"));
            return;
        }

        mobService.saveOriginalRespawn(uuid, player.getRespawnLocation());

        worldStrategy.place(player, arena, finalType, result -> {
            int[] bounds = result.bounds();
            if (bounds[0] == Integer.MAX_VALUE) {
                worldStrategy.release(uuid, arena.arenaId(), null);
                return;
            }
            startSession(player, arena, level, finalType, result);
        });
    }

    private void handleNext(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        if (mobService.isVisitor(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getOwnerOf(uuid));
            player.sendMessage(configManager.getMessage("command.next.not-owner", "owner", ownerName));
            return;
        }
        if (!mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.next.not-in-dungeon"));
            return;
        }
        OptionalInt targetOpt = parseLevelArg(args, -1, player);
        if (targetOpt.isEmpty()) return;
        int targetLevel = targetOpt.getAsInt();
        RegionState session = mobService.getSession(uuid);
        if (targetLevel <= session.currentLevel()) {
            player.sendMessage(configManager.getMessage(
                    "command.next.must-be-higher",
                    "current", String.valueOf(session.currentLevel()),
                    "next", String.valueOf(session.currentLevel() + 1)));
            return;
        }
        if (session.currentLevel() == 5 && targetLevel == 6) {
            if (mobService.enterInfiniteMode(player, true)) {
                return;
            }
        }
        mobService.advanceLevel(player, targetLevel);
    }

    private OptionalInt parseLevelArg(String[] args, int defaultLevel, Player player) {
        if (args.length < 2) {
            if (defaultLevel < 0) {
                RegionState session = mobService.getSession(player.getUniqueId());
                return OptionalInt.of(session.currentLevel() + 1);
            }
            return OptionalInt.of(defaultLevel);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.getMessage("command.invalid-number"));
            return OptionalInt.empty();
        }
        if (parsed < 1 || parsed > 5) {
            player.sendMessage(configManager.getMessage("command.invalid-level"));
            return OptionalInt.empty();
        }
        return OptionalInt.of(parsed);
    }

    private void startSession(Player player, AcquiredArena arena, int level, StructureType type,
                              StructureService.PlaceResult result) {
        World world = arena.world();
        player.sendMessage(configManager.getMessage("dungeon.finding-bed"));

        mobService.saveOriginalRespawn(player.getUniqueId(), player.getRespawnLocation());

        int[] bounds = result.bounds();
        List<BlockVector> spawners = result.spawners();
        Location bedLocation = (type != null)
                ? type.spawnPointFinder().find(world, bounds, spawners)
                : playerService.findBed(world, bounds, spawners);

        boolean alreadyInBounds = (bedLocation != null)
                && PlayerService.isInsideBounds(player.getLocation(), bounds);

        if (alreadyInBounds) {
            player.setRespawnLocation(bedLocation, true);
        } else {
            playerService.teleportPlayer(player, world, bedLocation, arena.arenaCenter());
        }

        world.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);

        Location origin = player.getLocation();
        RegionState session = mobService.startSession(player, arena, level, origin, bounds, spawners, type);

        if (session == null) {
            player.sendMessage(configManager.getMessage("dungeon.session-failed"));
            return;
        }

        player.sendMessage(configManager.getMessage("dungeon.started", "level", String.valueOf(level)));
        player.sendMessage(configManager.getMessage("dungeon.started-hint",
                "structure", (type != null) ? type.displayName() : configManager.defaultStructure()));
    }

    private void handleStay(Player player) {
        UUID uuid = player.getUniqueId();
        if (mobService.isVisitor(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getOwnerOf(uuid));
            player.sendMessage(configManager.getMessage("command.stay.not-owner", "owner", ownerName));
            return;
        }
        if (!mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.not-in-dungeon"));
            return;
        }
        mobService.declineLevelUp(player);
    }

    private void handleExit(Player player) {
        UUID uuid = player.getUniqueId();
        if (mobService.isVisitor(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getOwnerOf(uuid));
            player.sendMessage(configManager.getMessage("command.exit.not-owner", "owner", ownerName));
            return;
        }
        if (!mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.not-in-dungeon"));
            return;
        }
        player.sendMessage(configManager.getMessage("dungeon.exiting"));
        mobService.endSession(uuid, "OWNER_EXIT", true);
    }

    private void handleStats(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("structures")) {
            handleStatsStructures(player);
        } else {
            handleStatsAggregate(player);
        }
    }

    private void handleStatsAggregate(Player player) {
        player.sendMessage(configManager.getMessage("stats.header"));
        String uuid = player.getUniqueId().toString();
        statsService.getPersonalAggregateAsync(uuid)
                .thenAccept(agg -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    if (agg.totalSessions() <= 0) {
                        player.sendMessage(configManager.getMessage("stats.empty"));
                        return;
                    }
                    long minutes = agg.totalDurationSeconds() / 60;
                    long seconds = agg.totalDurationSeconds() % 60;
                    player.sendMessage(configManager.getMessage("stats.aggregate-line",
                            "sessions", String.valueOf(agg.totalSessions()),
                            "max_lvl", String.valueOf(agg.highestLevelEver()),
                            "kills", String.valueOf(agg.totalKills()),
                            "duration", minutes + "m" + seconds + "s",
                            "l5_count", String.valueOf(agg.reachedL5Count()),
                            "l5_rate", String.format("%.1f", agg.l5Rate() * 100.0)));
                    player.sendMessage(configManager.getMessage("stats.aggregate-hint"));
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            player.sendMessage("Failed to load stats: " + ex.getMessage());
                        }
                    });
                    return null;
                });
    }

    private void handleStatsStructures(Player player) {
        player.sendMessage(configManager.getMessage("stats.struct-header"));
        String uuid = player.getUniqueId().toString();
        statsService.getPersonalAggregateAsync(uuid)
                .thenAccept(agg -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    if (agg.perStructure().isEmpty()) {
                        player.sendMessage(configManager.getMessage("stats.struct-empty"));
                        return;
                    }
                    for (PlayerAggregate.StructureStat s : agg.perStructure()) {
                        String display = structureDisplayName(s.structureId());
                        long minutes = s.totalDurationSeconds() / 60;
                        player.sendMessage(configManager.getMessage("stats.struct-line",
                                "structure", display,
                                "sessions", String.valueOf(s.sessions()),
                                "max_lvl", String.valueOf(s.highestLevel()),
                                "kills", String.valueOf(s.totalKills()),
                                "duration", minutes + "m"));
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            player.sendMessage("Failed to load stats: " + ex.getMessage());
                        }
                    });
                    return null;
                });
    }

    private void handleTop(Player player, String[] args) {
        String structureId = null;
        if (args.length >= 2) {
            structureId = args[1];
            if (structureId.isBlank() || structureId.equalsIgnoreCase("all")) {
                structureId = null;
            } else if (structureRegistry.get(structureId).isEmpty()) {
                player.sendMessage(configManager.getMessage("command.unknown-structure",
                        "structure", structureId,
                        "available", String.join(", ", structureRegistry.ids())));
                return;
            }
        }
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.clamp(Integer.parseInt(args[2]), 1, 50);
            } catch (NumberFormatException e) {
                player.sendMessage(configManager.getMessage("command.invalid-number"));
                return;
            }
        }
        String display = (structureId == null) ? "*" : structureDisplayName(structureId);
        player.sendMessage(configManager.getMessage("top.header",
                "structure", display,
                "limit", String.valueOf(limit)));
        statsService.getRawTopAsync(structureId, limit)
                .thenAccept(raw -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    if (raw.isEmpty()) {
                        player.sendMessage(configManager.getMessage("top.empty"));
                        return;
                    }
                    int rank = 1;
                    for (PlayerAggregate.RawTopEntry e : raw) {
                        String name = PlayerResolver.resolveName(e.playerUuid());
                        player.sendMessage(configManager.getMessage("top.line",
                                "rank", String.valueOf(rank),
                                "name", name,
                                "max_lvl", String.valueOf(e.highestLevel()),
                                "kills", String.valueOf(e.totalKills()),
                                "sessions", String.valueOf(e.sessions())));
                        rank++;
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            player.sendMessage("Failed to load leaderboard: " + ex.getMessage());
                        }
                    });
                    return null;
                });
    }

    private String structureDisplayName(String structureId) {
        return structureRegistry.get(structureId)
                .map(StructureType::displayName)
                .orElse(structureId);
    }

    private String ownerNameOrUuid(UUID ownerUuid) {
        Player owner = (ownerUuid != null) ? Bukkit.getPlayer(ownerUuid) : null;
        return (owner != null) ? owner.getName() : (ownerUuid != null ? ownerUuid.toString() : "?");
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("dungeontrials.admin")) {
            player.sendMessage(configManager.getMessage("command.no-permission"));
            return;
        }
        player.sendMessage(configManager.getMessage("reload.start"));
        try {
            configManager.reload();
            worldStrategy.refreshConfig();
            mobService.applyBossBarStyle();
            if (rewardService != null) {
                rewardService.reload();
            }
            if (statsService != null) {
                statsService.invalidateCache();
            }
            if (dynamicLightService != null) {
                dynamicLightService.onConfigReload();
            }
            player.sendMessage(configManager.getMessage("reload.success"));
        } catch (Exception e) {
            player.sendMessage(configManager.getMessage("reload.failed"));
        }
    }

    private void handleInvite(Player owner, String[] args) {
        if (args.length < 2) {
            owner.sendMessage(configManager.getMessage("command.invite.usage"));
            return;
        }
        UUID ownerUuid = owner.getUniqueId();
        if (mobService.isVisitor(ownerUuid)) {
            String realOwnerName = ownerNameOrUuid(mobService.getOwnerOf(ownerUuid));
            owner.sendMessage(configManager.getMessage("command.invite.not-owner", "owner", realOwnerName));
            return;
        }
        if (!mobService.hasActiveSession(ownerUuid)) {
            owner.sendMessage(configManager.getMessage("command.invite.not-in-dungeon"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            owner.sendMessage(configManager.getMessage("command.invite.target-offline",
                    "target", args[1]));
            return;
        }
        UUID targetUuid = target.getUniqueId();
        if (targetUuid.equals(ownerUuid)) {
            owner.sendMessage(configManager.getMessage("command.invite.self"));
            return;
        }
        if (mobService.isInAnyRegion(targetUuid)) {
            String targetOwnerName = ownerNameOrUuid(mobService.getSession(targetUuid).ownerUuid());
            owner.sendMessage(configManager.getMessage("command.invite.target-already-in-region",
                    "target", target.getName(),
                    "owner", targetOwnerName));
            return;
        }
        if (!mobService.invite(ownerUuid, targetUuid)) {
            owner.sendMessage(configManager.getMessage("command.invite.not-in-dungeon"));
            return;
        }
        owner.sendMessage(configManager.getMessage("command.invite.sent",
                "target", target.getName()));
        target.sendMessage(configManager.getMessage("command.invite.received",
                "owner", owner.getName()));
    }

    private void handleVisit(Player visitor, String[] args) {
        if (args.length < 2) {
            visitor.sendMessage(configManager.getMessage("command.visit.usage"));
            return;
        }
        UUID visitorUuid = visitor.getUniqueId();
        if (mobService.isInAnyRegion(visitorUuid)) {
            visitor.sendMessage(configManager.getMessage("command.visit.has-session"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            visitor.sendMessage(configManager.getMessage("command.visit.target-offline",
                    "target", args[1]));
            return;
        }
        UUID targetUuid = target.getUniqueId();
        if (!mobService.hasActiveSession(targetUuid)) {
            visitor.sendMessage(configManager.getMessage("command.visit.target-not-in-dungeon",
                    "target", target.getName()));
            return;
        }
        if (mobService.consumePendingInvite(visitorUuid, targetUuid) == null) {
            visitor.sendMessage(configManager.getMessage("command.visit.not-invited",
                    "owner", target.getName()));
            return;
        }
        if (mobService.getVisitorsOf(targetUuid).size() >= configManager.maxVisitors()) {
            visitor.sendMessage(configManager.getMessage("dungeon.full",
                    "owner", target.getName(), "max", String.valueOf(configManager.maxVisitors())));
            return;
        }
        String ownerArenaId = mobService.getActiveArenaId(targetUuid);
        World ownerWorld = (ownerArenaId != null) ? worldStrategy.resolveWorld(ownerArenaId) : null;
        if (ownerWorld == null) {
            visitor.sendMessage(configManager.getMessage("command.visit.world-missing"));
            return;
        }
        mobService.saveOriginalRespawn(visitorUuid, visitor.getRespawnLocation());
        if (!mobService.addVisitor(targetUuid, visitorUuid)) {
            visitor.sendMessage(configManager.getMessage("command.visit.has-session"));
            return;
        }

        int[] bounds = mobService.getOwnerBounds(targetUuid);
        List<BlockVector> spawners = mobService.getOwnerSpawners(targetUuid);
        Location bedLocation = (bounds != null)
                ? playerService.findBed(ownerWorld, bounds, spawners)
                : null;

        if (bedLocation != null) {
            visitor.setRespawnLocation(bedLocation, true);
            visitor.teleport(bedLocation);
        } else {
            Location center = worldStrategy.arenaCenterFor(ownerArenaId);
            if (center == null || !worldStrategy.isArenaReady(ownerArenaId)) {
                Location original = mobService.getOriginalRespawn(visitorUuid);
                mobService.removeVisitor(visitorUuid);
                mobService.restoreBedSpawn(visitorUuid, original);
                visitor.sendMessage(configManager.getMessage("command.visit.world-missing"));
                return;
            }
            Location safeTarget = playerService.findSafeFallback(ownerWorld, center);
            visitor.setRespawnLocation(safeTarget, true);
            visitor.teleport(safeTarget);
        }

        visitor.sendMessage(configManager.getMessage("dungeon.joined",
                "owner", target.getName()));
        target.sendMessage(configManager.getMessage("dungeon.visitor-joined",
                "visitor", visitor.getName()));
    }

    private void handleDecline(Player visitor) {
        UUID visitorUuid = visitor.getUniqueId();
        UUID pendingOwner = mobService.getPendingInviteOwner(visitorUuid);
        if (pendingOwner == null) {
            visitor.sendMessage(configManager.getMessage("command.decline.no-invite"));
            return;
        }
        mobService.clearPendingInvite(visitorUuid);
        visitor.sendMessage(configManager.getMessage("command.decline.declined"));
        Player owner = Bukkit.getPlayer(pendingOwner);
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(configManager.getMessage("command.decline.notified-owner",
                    "visitor", visitor.getName()));
        }
    }

    private void handleLeave(Player visitor) {
        UUID visitorUuid = visitor.getUniqueId();
        if (!mobService.isVisitor(visitorUuid)) {
            visitor.sendMessage(configManager.getMessage("dungeon.not-in-dungeon"));
            return;
        }
        UUID ownerUuid = mobService.getOwnerOf(visitorUuid);
        String ownerName = ownerNameOrUuid(ownerUuid);
        Player owner = (ownerUuid != null) ? Bukkit.getPlayer(ownerUuid) : null;
        mobService.removeMemberFromRegion(visitorUuid, true);
        visitor.sendMessage(configManager.getMessage("dungeon.left", "owner", ownerName));
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(configManager.getMessage("dungeon.visitor-left",
                    "visitor", visitor.getName()));
        }
    }

    private void handleKick(Player owner, String[] args) {
        if (args.length < 2) {
            owner.sendMessage(configManager.getMessage("command.kick.usage"));
            return;
        }
        UUID ownerUuid = owner.getUniqueId();
        if (mobService.isVisitor(ownerUuid)) {
            String realOwnerName = ownerNameOrUuid(mobService.getOwnerOf(ownerUuid));
            owner.sendMessage(configManager.getMessage("command.kick.not-owner", "owner", realOwnerName));
            return;
        }
        if (!mobService.hasActiveSession(ownerUuid)) {
            owner.sendMessage(configManager.getMessage("command.kick.not-in-dungeon"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            owner.sendMessage(configManager.getMessage("command.kick.target-offline",
                    "target", args[1]));
            return;
        }
        UUID targetUuid = target.getUniqueId();
        UUID visitorOwner = mobService.getOwnerOf(targetUuid);
        if (visitorOwner == null || !visitorOwner.equals(ownerUuid)) {
            owner.sendMessage(configManager.getMessage("command.kick.not-your-visitor",
                    "target", target.getName()));
            return;
        }
        String targetName = target.getName();
        mobService.removeMemberFromRegion(targetUuid, true);
        owner.sendMessage(configManager.getMessage("command.kick.kicked",
                "target", targetName));
    }

    private void handleInfinite(Player player) {
        UUID uuid = player.getUniqueId();
        if (mobService.isVisitor(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getOwnerOf(uuid));
            player.sendMessage(configManager.getMessage("command.infinite.not-owner", "owner", ownerName));
            return;
        }
        if (!mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.not-in-dungeon"));
            return;
        }
        if (!mobService.isAwaitingInfiniteChoice(uuid)) {
            player.sendMessage(configManager.getMessage("command.infinite.not-awaiting"));
            return;
        }
        if (!mobService.enterInfiniteMode(player)) {
            player.sendMessage(configManager.getMessage("command.infinite.not-owner",
                    "owner", player.getName()));
        }
    }

    private void handleEnd(Player player) {
        UUID uuid = player.getUniqueId();
        if (mobService.isVisitor(uuid)) {
            String ownerName = ownerNameOrUuid(mobService.getOwnerOf(uuid));
            player.sendMessage(configManager.getMessage("command.end.not-owner", "owner", ownerName));
            return;
        }
        if (!mobService.hasActiveSession(uuid)) {
            player.sendMessage(configManager.getMessage("command.not-in-dungeon"));
            return;
        }
        mobService.endSession(uuid, "OWNER_END", true);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!event.hasChangedBlock()) return;
        if (!mobService.isInAnyRegion(uuid)) return;

        Location origin = mobService.getOrigin(uuid);
        if (origin == null) return;

        Location current = player.getLocation();
        if (!origin.getWorld().equals(current.getWorld())) return;

        if (origin.distanceSquared(current)
                > DISTANCE_THRESHOLD * DISTANCE_THRESHOLD) {
            World world = origin.getWorld();
            mobService.checkAndStartWaves(player, world);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity killed = event.getEntity();
        if (rewardService != null && rewardService.isEnabled()) {
            UUID owner = mobService.findDungeonOwnerNear(killed.getLocation(), killed.getWorld());
            if (owner != null) {
                int baseExp = event.getDroppedExp();
                double mult = rewardService.xpMultiplier();
                if (mult != 1.0 && baseExp > 0) {
                    event.setDroppedExp((int) Math.round(baseExp * mult));
                }
            }
        }
        mobService.onKill(killed);
        mobService.cleanupSpawnTracking(killed);
    }

    @EventHandler
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        mobService.cleanupSpawnTracking(event.getEntity());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        if (!mobService.isInAnyRegion(uuid)) return;
        if (mobService.hasActiveSession(uuid)) {
            mobService.endSession(uuid, "OWNER_DIED");
        } else {
            mobService.removeMemberFromRegion(uuid, true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (dynamicLightService != null) {
            dynamicLightService.removeLightFor(uuid);
        }
        mobService.clearPendingInvite(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        mobService.loadActiveSessionsAsync().whenComplete((count, err) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    mobService.restoreSession(player);
                    mobService.applyPendingRespawn(uuid);
                }));
        if (dynamicLightService != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline() || dynamicLightService == null) return;
                dynamicLightService.clearZombieLightAt(player.getLocation());
                dynamicLightService.scheduleRefresh(player);
            }, 1L);
        }
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (dynamicLightService != null) {
            dynamicLightService.removeLightFor(uuid);
        }
        mobService.clearPendingInvite(uuid);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        World from = event.getFrom();
        World to = player.getWorld();
        if (dynamicLightService != null) {
            dynamicLightService.removeLightFor(uuid);
        }
        boolean wasInDungeon = worldStrategy.isDungeonWorld(from);
        boolean isInDungeon = worldStrategy.isDungeonWorld(to);

        RegionState region = mobService.getSession(uuid);
        if (region != null) {
            if (to.getName().equals(region.worldName())) {
                mobService.attachBossBar(uuid);
            } else {
                mobService.detachBossBar(uuid);
            }
        }

        if (wasInDungeon && !isInDungeon) {
            if (mobService.isInAnyRegion(uuid) && !mobService.hasActiveSession(uuid)) {
                mobService.removeMemberFromRegion(uuid, false);
            }
        }

        if (isInDungeon && !wasInDungeon) {
            mobService.saveOriginalRespawn(uuid, player.getRespawnLocation());

            if (!mobService.isInAnyRegion(uuid)) {
                UUID ownerUuid = mobService.findDungeonOwnerNear(player.getLocation(), player.getWorld());
                if (ownerUuid != null
                        && mobService.getVisitorsOf(ownerUuid).size() < configManager.maxVisitors()) {
                    if (mobService.addVisitor(ownerUuid, uuid)) {
                        String ownerName = ownerNameOrUuid(ownerUuid);
                        player.sendMessage(configManager.getMessage("dungeon.auto-joined",
                                "owner", ownerName));
                        Player owner = Bukkit.getPlayer(ownerUuid);
                        if (owner != null && owner.isOnline()) {
                            owner.sendMessage(configManager.getMessage("dungeon.visitor-auto-joined",
                                    "visitor", player.getName()));
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location respawn = event.getRespawnLocation();
        if (respawn.getWorld() == null) return;
        if (!worldStrategy.isDungeonWorld(respawn.getWorld())) return;

        Player player = event.getPlayer();
        Location original = mobService.getOriginalRespawn(player.getUniqueId());
        if (original == null) {
            original = worldStrategy.getReturnLocation(null);
        }
        if (original == null) return;
        event.setRespawnLocation(original);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (dynamicLightService == null) return;
        dynamicLightService.scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHand(PlayerSwapHandItemsEvent event) {
        if (dynamicLightService == null) return;
        dynamicLightService.scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMoveLight(PlayerMoveEvent event) {
        if (dynamicLightService == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        dynamicLightService.scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeathLight(PlayerDeathEvent event) {
        if (dynamicLightService == null) return;
        dynamicLightService.forceRemove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Material type = event.getClickedBlock().getType();
        if (!type.name().endsWith("_BED")) return;

        if (!worldStrategy.isDungeonWorld(event.getClickedBlock().getWorld())) return;

        event.setCancelled(true);
    }
}
