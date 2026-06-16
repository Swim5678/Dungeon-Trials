package org.swim.dungeonTrials.service;

import lombok.Setter;
import org.bukkit.Location;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.world.WorldStrategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VisitorService {

    private final SessionService sessionService;
    private final RespawnService respawnService;
    private final ConfigManager configManager;
    private final Map<UUID, UUID> pendingInvites = new ConcurrentHashMap<>();
    @Setter
    private WorldStrategy worldStrategy;

    public VisitorService(SessionService sessionService,
                          RespawnService respawnService, ConfigManager configManager) {
        this.sessionService = sessionService;
        this.respawnService = respawnService;
        this.configManager = configManager;
    }

    public boolean invite(UUID ownerUuid, UUID visitorUuid) {
        RegionState region = sessionService.getRegionByPlayer(ownerUuid);
        if (region == null) return false;
        if (!region.isOwner(ownerUuid)) return false;
        pendingInvites.put(visitorUuid, ownerUuid);
        return true;
    }

    public UUID consumePendingInvite(UUID visitorUuid, UUID ownerUuid) {
        UUID owner = pendingInvites.get(visitorUuid);
        if (owner == null || !owner.equals(ownerUuid)) return null;
        pendingInvites.remove(visitorUuid, ownerUuid);
        return owner;
    }

    public void clearPendingInvite(UUID visitorUuid) {
        pendingInvites.remove(visitorUuid);
    }

    public UUID getPendingInviteOwner(UUID visitorUuid) {
        return pendingInvites.get(visitorUuid);
    }

    public void clearOwnerInvites(UUID ownerUuid) {
        pendingInvites.entrySet().removeIf(e -> e.getValue().equals(ownerUuid));
    }

    public boolean addVisitor(UUID ownerUuid, UUID visitorUuid) {
        RegionState region = sessionService.getRegionByPlayer(ownerUuid);
        if (region == null) return false;
        if (!region.isOwner(ownerUuid)) return false;
        if (sessionService.isInAnyRegion(visitorUuid)) return false;

        return sessionService.joinAsMember(visitorUuid, region.regionId());
    }

    public void removeVisitor(UUID visitorUuid) {
        RegionState region = sessionService.getRegionByPlayer(visitorUuid);
        if (region == null) return;
        if (region.isOwner(visitorUuid)) return;

        sessionService.removeMember(visitorUuid);
    }

    public void endIfVisitor(UUID playerUuid) {
        if (isVisitor(playerUuid)) {
            removeVisitor(playerUuid);
        }
    }

    public void endVisitorSession(UUID visitorUuid) {
        if (!isVisitor(visitorUuid)) return;
        removeVisitor(visitorUuid);
        Location original = respawnService.getOriginalRespawn(visitorUuid);
        respawnService.restoreBedSpawn(visitorUuid, original);
        respawnService.clearOriginalRespawn(visitorUuid);
    }

    public boolean isVisitor(UUID playerUuid) {
        RegionState region = sessionService.getRegionByPlayer(playerUuid);
        return region != null && !region.isOwner(playerUuid);
    }

    public UUID getOwnerOf(UUID visitorUuid) {
        RegionState region = sessionService.getRegionByPlayer(visitorUuid);
        if (region == null) return null;
        if (region.isOwner(visitorUuid)) return null;
        return region.ownerUuid();
    }

    public Set<UUID> getVisitorsOf(UUID ownerUuid) {
        RegionState region = sessionService.getRegionByPlayer(ownerUuid);
        if (region == null) return Collections.emptySet();
        Set<UUID> visitors = new LinkedHashSet<>();
        for (UUID member : region.members()) {
            if (!member.equals(ownerUuid)) visitors.add(member);
        }
        return visitors;
    }

    public boolean isInAnyRegion(UUID playerUuid) {
        return sessionService.isInAnyRegion(playerUuid);
    }

    public void clearAll() {
        pendingInvites.clear();
    }
}
