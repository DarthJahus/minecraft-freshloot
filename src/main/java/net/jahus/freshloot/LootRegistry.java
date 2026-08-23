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
 *   - "who currently has a position open" (OPENED_BY) is pure runtime
 *     state -- no container is open right after a server start. Kept in
 *     memory only.
 *   - "which loot table a position had, between this player closing the
 *     GUI and the deferred break+replace picking it back up" (PENDING_ROLL)
 *     is a short-lived, single-tick handoff -- NOT a durable index of
 *     managed positions. It exists only because vanilla clears a
 *     container's LootTable NBT the instant generateLoot() runs, so the
 *     table has to be captured before that happens and carried the one
 *     tick until breakAndReplace re-stamps it onto the fresh block. It is
 *     deliberately NOT the source of truth for "is this container managed"
 *     -- that would make every managed position amnesiac on restart, since
 *     nothing here was ever meant to persist. See breakAndReplace for how
 *     the fresh container gets a real LootTable NBT tag instead of relying
 *     on this map surviving.
 *   - "which players have already looted which positions" MUST persist:
 *     that's the one-roll-per-player-per-container guarantee, and losing it
 *     on restart would let everyone re-loot everything. Stored via
 *     PersistentState, written to the overworld's data storage.
 *
 * Whether a position is "managed" at all is intentionally NOT tracked here.
 * A container is FreshLoot's concern purely based on whether it currently
 * carries a non-null LootTable (vanilla NBT, readable straight off the
 * block) -- see LootContainerHandler. That, plus stamping the LootTable
 * back onto every replacement container at rotation time (breakAndReplace),
 * is what makes a rotated container survive a server restart exactly like
 * a freshly-generated vanilla one: the position never depends on anything
 * that lives only in this class's memory.
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
    private static final Map<DimPos, UUID> OPENED_BY = new ConcurrentHashMap<>();

    public record LootRecord(RegistryKey<LootTable> lootTable, Direction facing) {}

    /**
     * Short-lived handoff: capture the loot table + facing this container
     * had right before generateLoot() consumes the NBT tag, so
     * breakAndReplace can stamp the same table onto the replacement a tick
     * later. Not a durable registry -- see class javadoc.
     */
    private static final Map<DimPos, LootRecord> PENDING_ROLL = new ConcurrentHashMap<>();

    public static void capturePendingRoll(DimPos pos, RegistryKey<LootTable> lootTable, Direction facing) {
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
