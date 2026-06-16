package org.swim.dungeonTrials.admin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.service.MobService;
import org.swim.dungeonTrials.structure.StructureRegistry;
import org.swim.dungeonTrials.structure.StructureType;
import org.swim.dungeonTrials.util.PlayerResolver;
import org.swim.dungeonTrials.world.WorldStrategy;
import org.swim.dungeonTrials.world.strategy.PerPlayerWorldStrategy;
import org.swim.dungeonTrials.world.strategy.SharedWorldStrategy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class AdminHandler {

    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
    private final DungeonTrials plugin;
    private final DatabaseManager databaseManager;
    private final WorldStrategy worldStrategy;
    private final MobService mobService;
    private final StructureRegistry structureRegistry;

    public AdminHandler(DungeonTrials plugin, DatabaseManager databaseManager,
                        WorldStrategy worldStrategy, MobService mobService,
                        StructureRegistry structureRegistry) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.worldStrategy = worldStrategy;
        this.mobService = mobService;
        this.structureRegistry = structureRegistry;
    }

    private static String formatTimestamp(long millis) {
        if (millis <= 0) return "?";
        return TS_FMT.format(new Date(millis));
    }

    public void handleArena(Player admin, String[] args) {
        if (!hasAdminPermission(admin)) {
            admin.sendMessage(plugin.getConfigManager().getMessage("command.no-permission"));
            return;
        }
        if (args.length < 2) {
            sendArenaHelp(admin);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> handleArenaList(admin);
            case "info" -> handleArenaInfo(admin, args);
            case "release" -> handleArenaRelease(admin, args);
            case "cleanup-orphans" -> handleArenaCleanupOrphans(admin);
            default -> sendArenaHelp(admin);
        }
    }

    public void handleSession(Player admin, String[] args) {
        if (!hasAdminPermission(admin)) {
            admin.sendMessage(plugin.getConfigManager().getMessage("command.no-permission"));
            return;
        }
        if (args.length < 2) {
            sendSessionHelp(admin);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> handleSessionList(admin);
            case "end" -> handleSessionEnd(admin, args);
            default -> sendSessionHelp(admin);
        }
    }

    private void handleArenaList(Player admin) {
        databaseManager.getAllArenaDetailsAsync()
                .thenAccept(arenas -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!admin.isOnline()) return;
                    if (arenas.isEmpty()) {
                        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.list.empty"));
                        return;
                    }
                    admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.list.header",
                            "count", String.valueOf(arenas.size())));
                    for (DatabaseManager.ArenaDetails a : arenas) {
                        String ownerName = PlayerResolver.resolveName(a.ownerUuid());
                        String structureName = structureDisplay(a.structureId());
                        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.list.entry",
                                "id", a.arenaId(),
                                "strategy", a.strategyId(),
                                "world", a.worldName(),
                                "owner", ownerName,
                                "structure", structureName,
                                "status", a.status(),
                                "last_used", formatTimestamp(a.lastUsed())));
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (admin.isOnline()) {
                            admin.sendMessage("Failed to load arenas: " + ex.getMessage());
                        }
                    });
                    return null;
                });
    }

    private void handleArenaInfo(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.info.usage"));
            return;
        }
        String arenaId = args[2];
        databaseManager.getArenaDetailsAsync(arenaId)
                .thenAccept(details -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!admin.isOnline()) return;
                    if (details == null) {
                        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.info.not-found",
                                "id", arenaId));
                        return;
                    }
                    admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.info.header",
                            "id", details.arenaId()));
                    sendInfoLine(admin, "arena_id", details.arenaId());
                    sendInfoLine(admin, "strategy_id", details.strategyId());
                    sendInfoLine(admin, "world", details.worldName());
                    sendInfoLine(admin, "owner", PlayerResolver.resolveName(details.ownerUuid())
                            + " <gray>(" + details.ownerUuid() + ")</gray>");
                    sendInfoLine(admin, "structure", structureDisplay(details.structureId()));
                    sendInfoLine(admin, "status", details.status());
                    sendInfoLine(admin, "created", formatTimestamp(details.createdAt()));
                    sendInfoLine(admin, "last_used", formatTimestamp(details.lastUsed()));

                    Location center = worldStrategy.arenaCenterFor(details.arenaId());
                    if (center != null) {
                        sendInfoLine(admin, "center",
                                center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ());
                    } else {
                        sendInfoLine(admin, "center", "<gray>(not loaded)</gray>");
                    }
                    int[] region = worldStrategy.regionBoundsFor(details.arenaId());
                    if (region != null) {
                        sendInfoLine(admin, "region",
                                "(" + region[0] + "," + region[2] + ") → ("
                                        + region[3] + "," + region[5] + ")");
                    }
                    String status = isArenaLoaded(details.arenaId()) ? "loaded" : "unloaded";
                    sendInfoLine(admin, "in_memory", status);
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (admin.isOnline()) {
                            admin.sendMessage("Failed to load arena: " + ex.getMessage());
                        }
                    });
                    return null;
                });
    }

    private void handleArenaRelease(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.release.usage"));
            return;
        }
        String target = args[2];
        CompletableFuture<String> resolveFuture = resolveArenaIdAsync(target);
        resolveFuture.thenAccept(arenaId -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline()) return;
            if (arenaId == null) {
                admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.release.not-found",
                        "target", target));
                return;
            }
            performRelease(admin, arenaId);
        })).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (admin.isOnline()) {
                    admin.sendMessage("Failed to release arena: " + ex.getMessage());
                }
            });
            return null;
        });
    }

    private void performRelease(Player admin, String arenaId) {
        UUID activeOwner = findActiveSessionOwner(arenaId);
        if (activeOwner != null) {
            Player victim = Bukkit.getPlayer(activeOwner);
            if (victim != null) {
                victim.sendMessage(plugin.getConfigManager().getMessage("admin.arena.release.kicked-player"));
            }
            mobService.endSession(activeOwner, "ADMIN_END", true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> finishReleaseAfterEnd(admin, arenaId, activeOwner), 20L);
            return;
        }
        finishReleaseAfterEnd(admin, arenaId, null);
    }

    private void finishReleaseAfterEnd(Player admin, String arenaId, UUID endedOwner) {
        databaseManager.getArenaDetailsAsync(arenaId).thenAccept(details -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline()) return;
            if (details == null) {
                admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.release.not-found",
                        "target", arenaId));
                return;
            }
            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(details.ownerUuid());
            } catch (IllegalArgumentException e) {
                ownerUuid = endedOwner;
            }
            if (worldStrategy != null) {
                worldStrategy.release(ownerUuid, arenaId, null);
            } else {
                databaseManager.deleteArena(arenaId);
            }
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.released",
                    "id", arenaId));
        }));
    }

    private void handleArenaCleanupOrphans(Player admin) {
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.cleanup.started"));
        databaseManager.getOrphanArenasAsync().thenAccept(orphanIds -> {
            AtomicInteger counter = new AtomicInteger(0);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!admin.isOnline()) return;
                for (String arenaId : orphanIds) {
                    if (worldStrategy instanceof SharedWorldStrategy shared) {
                        shared.cleanupTrackedArena(arenaId);
                        counter.incrementAndGet();
                    } else if (worldStrategy instanceof PerPlayerWorldStrategy perPlayer) {
                        perPlayer.cleanupTrackedArena(arenaId);
                        counter.incrementAndGet();
                    } else {
                        databaseManager.deleteArena(arenaId);
                        counter.incrementAndGet();
                    }
                }
                admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.cleanup.done",
                        "count", String.valueOf(counter.get())));
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (admin.isOnline()) {
                    admin.sendMessage("Failed to cleanup orphans: " + ex.getMessage());
                }
            });
            return null;
        });
    }

    private void handleSessionList(Player admin) {
        List<RegionState> active = mobService.allActiveSessions();
        if (active.isEmpty()) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.list.empty"));
            return;
        }
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.list.header",
                "count", String.valueOf(active.size())));
        long now = System.currentTimeMillis();
        for (RegionState s : active) {
            long uptimeSec = (now - s.startTime()) / 1000;
            long min = uptimeSec / 60;
            String ownerName = PlayerResolver.resolveName(s.ownerUuid().toString());
            String arenaShort = s.regionId() == null ? "?" : s.regionId();
            if (arenaShort.length() > 24) arenaShort = arenaShort.substring(0, 24) + "...";
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.list.entry",
                    "player", ownerName,
                    "level", String.valueOf(s.currentLevel()),
                    "kills", String.valueOf(s.kills()),
                    "uptime_min", String.valueOf(min),
                    "structure", structureDisplay(s.structureId()),
                    "arena", arenaShort,
                    "infinite", s.infinite() ? "∞" : "",
                    "members", String.valueOf(s.memberCount())));
        }
    }

    private void handleSessionEnd(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.end.usage"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null || !target.isOnline()) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.target-offline",
                    "target", args[2]));
            return;
        }
        UUID uuid = target.getUniqueId();
        if (!mobService.isInAnyRegion(uuid)) {
            admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.target-not-in-dungeon",
                    "target", target.getName()));
            return;
        }
        mobService.endSession(uuid, "ADMIN_END", true);
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.ended",
                "target", target.getName()));
        target.sendMessage(plugin.getConfigManager().getMessage("admin.session.ended-notice",
                "admin", admin.getName()));
    }

    private CompletableFuture<String> resolveArenaIdAsync(String target) {
        if (isArenaLoaded(target)) {
            return CompletableFuture.completedFuture(target);
        }
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            String ownerUuid = online.getUniqueId().toString();
            return databaseManager.getAllArenaDetailsAsync().thenApply(arenas -> {
                for (DatabaseManager.ArenaDetails a : arenas) {
                    if (ownerUuid.equals(a.ownerUuid())) return a.arenaId();
                }
                return null;
            });
        }
        return databaseManager.getArenaDetailsAsync(target).thenApply(d -> d != null ? d.arenaId() : null);
    }

    private UUID findActiveSessionOwner(String arenaId) {
        for (RegionState s : mobService.allActiveSessions()) {
            if (arenaId.equals(s.regionId())) {
                return s.ownerUuid();
            }
        }
        return null;
    }

    private boolean isArenaLoaded(String arenaId) {
        if (worldStrategy == null) return false;
        return worldStrategy.activeArenaIds().contains(arenaId);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean hasAdminPermission(Player p) {
        return p.hasPermission("dungeontrials.admin");
    }

    private void sendInfoLine(Player admin, String key, String value) {
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.info.line",
                "key", key, "value", value));
    }

    private void sendArenaHelp(Player admin) {
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.arena.help"));
    }

    private void sendSessionHelp(Player admin) {
        admin.sendMessage(plugin.getConfigManager().getMessage("admin.session.help"));
    }

    private String structureDisplay(String structureId) {
        if (structureId == null) return "?";
        return structureRegistry.get(structureId).map(StructureType::displayName).orElse(structureId);
    }
}
