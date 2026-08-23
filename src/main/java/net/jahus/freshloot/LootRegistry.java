package net.jahus.freshloot;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.loot.LootTable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for loot containers (chests, barrels).
 *
 * All positions are keyed by (dimension, BlockPos) via composite DimPos
 * keys -- a bare BlockPos is not unique across dimensions (e.g. the same
 * X/Y/Z exists independently in the overworld and the nether), so using it
 * alone would let a chest in one dimension collide with an unrelated chest
 * at the same coordinates in another.
 *
 * Split by what actually needs to survive a restart:
 *   - loot record per position (table + facing) and "who has it open right
 *     now" are pure runtime state -- no container is open right after a
 *     server start, and RECORDS gets rebuilt naturally the first time a
 *     player touches an unseen loot container again. Kept in memory only.
 *   - "which players have already looted which positions" MUST persist:
 *     that's the one-roll-per-player-per-container guarantee, and losing it
 *     on restart would let everyone re-loot everything. Stored via
 *     PersistentState, written to the overworld's data storage.
 */
public class LootRegistry extends PersistentState {

    private static final String MOD_ID = "freshloot";

    /** Composite key: a BlockPos alone is not unique across dimensions. */
    public record DimPos(RegistryKey<World> dimension, BlockPos pos) {
        public static DimPos of(World world, BlockPos pos) {
            return new DimPos(world.getRegistryKey(), pos.toImmutable());
        }
    }

    // --- Runtime-only state (not persisted) ---
    private static final Map<DimPos, LootRecord> RECORDS = new ConcurrentHashMap<>();
    private static final Map<DimPos, UUID> OPENED_BY = new ConcurrentHashMap<>();

    public record LootRecord(RegistryKey<LootTable> lootTable, Direction facing) {}

    public static void register(DimPos pos, RegistryKey<LootTable> lootTable, Direction facing) {
        RECORDS.put(pos, new LootRecord(lootTable, facing));
    }

    public static LootRecord get(DimPos pos) {
        return RECORDS.get(pos);
    }

    public static boolean isRegistered(DimPos pos) {
        return RECORDS.containsKey(pos);
    }

    public static void unregister(DimPos pos) {
        RECORDS.remove(pos);
        OPENED_BY.remove(pos);
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

    // --- Persisted state: per-player looted positions ---
    private final Map<UUID, Set<DimPos>> lootedByPlayer = new ConcurrentHashMap<>();

    public static boolean hasLooted(MinecraftServer server, UUID player, DimPos pos) {
        Set<DimPos> looted = getState(server).lootedByPlayer.get(player);
        return looted != null && looted.contains(pos);
    }

    public static void markLooted(MinecraftServer server, UUID player, DimPos pos) {
        LootRegistry state = getState(server);
        state.lootedByPlayer.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(pos);
        state.markDirty();
    }

    private static LootRegistry getState(MinecraftServer server) {
        PersistentStateManager manager = Objects.requireNonNull(server.getWorld(World.OVERWORLD)).getPersistentStateManager();
        return manager.getOrCreate(TYPE, MOD_ID);
    }

    // --- NBT (de)serialization ---

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        NbtCompound playersNbt = new NbtCompound();
        lootedByPlayer.forEach((uuid, positions) -> {
            NbtList posList = new NbtList();
            for (DimPos dp : positions) {
                NbtCompound entry = new NbtCompound();
                entry.putString("dim", dp.dimension().getValue().toString());
                entry.putLong("pos", dp.pos().asLong());
                posList.add(entry);
            }
            playersNbt.put(uuid.toString(), posList);
        });
        nbt.put("lootedByPlayer", playersNbt);
        return nbt;
    }

    public static LootRegistry createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        LootRegistry state = new LootRegistry();
        NbtCompound playersNbt = nbt.getCompound("lootedByPlayer");
        for (String key : playersNbt.getKeys()) {
            UUID uuid = UUID.fromString(key);
            NbtList posList = playersNbt.getList(key, NbtElement.COMPOUND_TYPE);
            Set<DimPos> positions = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < posList.size(); i++) {
                NbtCompound entry = posList.getCompound(i);
                RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(entry.getString("dim")));
                BlockPos pos = BlockPos.fromLong(entry.getLong("pos"));
                positions.add(new DimPos(dim, pos));
            }
            state.lootedByPlayer.put(uuid, positions);
        }
        return state;
    }

    private static final Type<LootRegistry> TYPE = new Type<>(
            LootRegistry::new,
            LootRegistry::createFromNbt,
            null
    );
}
