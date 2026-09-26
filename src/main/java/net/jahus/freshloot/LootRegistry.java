package net.jahus.freshloot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent "who looted which position" + ephemeral open/pending maps.
 * Uses SavedDataType + Codec (26.x API).
 */
public class LootRegistry extends SavedData {

    public record DimPos(ResourceKey<Level> dimension, BlockPos pos) {
        public static DimPos of(Level world, BlockPos pos) {
            return new DimPos(world.dimension(), pos.immutable());
        }

        public static final Codec<DimPos> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(DimPos::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(DimPos::pos)
        ).apply(i, DimPos::new));
    }

    private static final Map<DimPos, UUID> OPENED_BY = new ConcurrentHashMap<>();

    public record LootRecord(ResourceKey<LootTable> lootTable, Direction facing) {}

    private static final Map<DimPos, LootRecord> PENDING_ROLL = new ConcurrentHashMap<>();

    public static void capturePendingRoll(DimPos pos, ResourceKey<LootTable> lootTable, Direction facing) {
        PENDING_ROLL.put(pos, new LootRecord(lootTable, facing));
    }

    public static LootRecord takePendingRoll(DimPos pos) {
        return PENDING_ROLL.remove(pos);
    }

    public static boolean isOpen(DimPos pos) {
        return OPENED_BY.containsKey(pos);
    }

    public static UUID getViewer(DimPos pos) {
        return OPENED_BY.get(pos);
    }

    public static void markOpen(DimPos pos, UUID viewer) {
        OPENED_BY.put(pos, viewer);
    }

    public static void markClosed(DimPos pos) {
        OPENED_BY.remove(pos);
    }

    private final Map<UUID, Set<DimPos>> lootedByPlayer = new ConcurrentHashMap<>();

    public LootRegistry() {}

    private LootRegistry(Map<UUID, List<DimPos>> loaded) {
        loaded.forEach((uuid, list) -> {
            Set<DimPos> set = ConcurrentHashMap.newKeySet();
            set.addAll(list);
            lootedByPlayer.put(uuid, set);
        });
    }

    private static final Codec<Map<UUID, List<DimPos>>> LOOTED_CODEC =
            Codec.unboundedMap(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString),
                    DimPos.CODEC.listOf()
            );

    public static final Codec<LootRegistry> CODEC = LOOTED_CODEC.xmap(
            LootRegistry::new,
            reg -> {
                Map<UUID, List<DimPos>> out = new HashMap<>();
                reg.lootedByPlayer.forEach((uuid, set) -> out.put(uuid, new ArrayList<>(set)));
                return out;
            }
    );

    public static final SavedDataType<LootRegistry> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("freshloot", "looted"),
            LootRegistry::new,
            CODEC,
            null
    );

    public static boolean hasLooted(MinecraftServer server, UUID player, DimPos pos) {
        Set<DimPos> looted = getState(server).lootedByPlayer.get(player);
        return looted != null && looted.contains(pos);
    }

    public static void markLooted(MinecraftServer server, UUID player, DimPos pos) {
        LootRegistry state = getState(server);
        state.lootedByPlayer.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(pos);
        state.setDirty();
    }

    private static LootRegistry getState(MinecraftServer server) {
        ServerLevel overworld = Objects.requireNonNull(server.getLevel(Level.OVERWORLD));
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }
}
