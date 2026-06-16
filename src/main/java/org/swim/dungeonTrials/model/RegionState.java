package org.swim.dungeonTrials.model;

import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.structure.StructureType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RegionState {

    private final String regionId;
    private final String worldName;
    private final String structureId;
    private final long startTime;
    private final UUID ownerUuid;
    private final Set<UUID> members;

    private int currentLevel;
    private int kills;
    private boolean infinite;
    private boolean awaitingInfiniteChoice;

    private int[] structureBounds;
    private int[] arenaRegion;
    private StructureType structureType;
    private List<BlockVector> spawners = new ArrayList<>();

    public RegionState(String regionId, String worldName, String structureId, UUID ownerUuid,
                       int currentLevel, long startTime) {
        this.regionId = regionId;
        this.worldName = worldName;
        this.structureId = structureId;
        this.ownerUuid = ownerUuid;
        this.currentLevel = currentLevel;
        this.startTime = startTime;
        this.members = ConcurrentHashMap.newKeySet();
        this.members.add(ownerUuid);
    }

    public String regionId() {
        return regionId;
    }

    public String worldName() {
        return worldName;
    }

    public String structureId() {
        return structureId;
    }

    public long startTime() {
        return startTime;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public Set<UUID> members() {
        return members;
    }

    public boolean isOwner(UUID uuid) {
        return ownerUuid.equals(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public boolean addMember(UUID uuid) {
        return members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        if (ownerUuid.equals(uuid)) return false;
        return members.remove(uuid);
    }

    public int memberCount() {
        return members.size();
    }

    public int currentLevel() {
        return currentLevel;
    }

    public void currentLevel(int v) {
        this.currentLevel = v;
    }

    public int kills() {
        return kills;
    }

    public void kills(int v) {
        this.kills = v;
    }

    public boolean infinite() {
        return infinite;
    }

    public void infinite(boolean v) {
        this.infinite = v;
    }

    public boolean awaitingInfiniteChoice() {
        return awaitingInfiniteChoice;
    }

    public void awaitingInfiniteChoice(boolean v) {
        this.awaitingInfiniteChoice = v;
    }

    public int[] structureBounds() {
        return (structureBounds == null) ? null : structureBounds.clone();
    }

    public void structureBounds(int[] v) {
        this.structureBounds = (v == null) ? null : v.clone();
    }

    public int[] arenaRegion() {
        return (arenaRegion == null) ? null : arenaRegion.clone();
    }

    public void arenaRegion(int[] v) {
        this.arenaRegion = (v == null) ? null : v.clone();
    }

    public StructureType structureType() {
        return structureType;
    }

    public void structureType(StructureType v) {
        this.structureType = v;
    }

    public void incrementKills() {
        this.kills++;
    }

    public List<BlockVector> spawners() {
        return Collections.unmodifiableList(spawners);
    }

    public void spawners(List<BlockVector> v) {
        this.spawners = (v != null) ? new ArrayList<>(v) : new ArrayList<>();
    }
}
