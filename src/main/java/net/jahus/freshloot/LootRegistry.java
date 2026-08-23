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

public class LootRegistry extends PersistentState {

    private static final String MOD_ID = "freshloot";

    public record DimPos(RegistryKey<World> dimension, BlockPos pos) {
        public static DimPos of(World world, BlockPos pos) {
            return new DimPos(world.getRegistryKey(), pos.toImmutable());
        }
    }

    private static final Map<DimPos, UUID> OPENED_BY = new ConcurrentHashMap<>();

    public record LootRecord(RegistryKey<LootTable> lootTable, Direction facing) {}

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
