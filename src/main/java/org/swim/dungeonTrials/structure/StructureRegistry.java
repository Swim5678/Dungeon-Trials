package org.swim.dungeonTrials.structure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StructureRegistry {

    private final Map<String, StructureType> structures = new LinkedHashMap<>();

    public void register(StructureType structure) {
        if (structure == null) throw new IllegalArgumentException("structure must not be null");
        String id = structure.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("structure id must not be blank");
        }
        if (structures.containsKey(id)) {
            throw new IllegalStateException("structure already registered: " + id);
        }
        structures.put(id, structure);
    }

    public Optional<StructureType> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(structures.get(id));
    }

    public List<String> ids() {
        return List.copyOf(structures.keySet());
    }
}
