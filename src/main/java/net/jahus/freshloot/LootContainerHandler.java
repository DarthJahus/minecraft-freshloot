package net.jahus.freshloot;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.jahus.freshloot.LootRegistry.DimPos;


public final class LootContainerHandler {

    private LootContainerHandler() {}

    /**
     * Containers already reported as griefed by external extraction. A hopper
     * minecart calls removeStack many times per tick, so we must alert once.
     * Cleared when breakAndReplace re-arms the container with its loot table.
     */
    private static final Set<DimPos> SPOILED_REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * True iff be is a chest or barrel that still carries a loot table, i.e.
     * nobody has claimed it since it was last armed. Null-safe.
     */
    public static boolean isUnclaimedLootContainer(BlockEntity be) {
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
            return false;
        }
        return be instanceof LootableContainerBlockEntity lootable && lootable.getLootTable() != null;
    }

    /**
     * An unclaimed loot container was drained by something outside our
     * open/close flow (hopper, hopper minecart, dropper...). Same treatment as
     * a player breaking it: public warning, log, neutralize.
     */
    public static void reportSpoiledByExtraction(LootableContainerBlockEntity container, World world, BlockPos pos) {
        if (world.isClient() || !(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
            return;
        }
        DimPos key = DimPos.of(world, pos);
        if (!SPOILED_REPORTED.add(key)) {
            return;
        }

        String lootTableId = container.getLootTable() != null
                ? container.getLootTable().getValue().toString()
                : "unknown";

        MinecraftServer server = serverWorld.getServer();
        server.getPlayerManager().broadcast(
                Text.literal("A loot container was spoiled by an external source at "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "!")
                        .formatted(Formatting.RED),
                false);

        FreshLootMod.LOGGER.warn(
                "[FreshLoot] Unclaimed loot container spoiled by an external source at {} [{}, {}, {}] (loot table: {})",
                world.getRegistryKey().getValue(),
                pos.getX(), pos.getY(), pos.getZ(),
                lootTableId);

        neutralizeLootOnBreak(container);
    }

    public static boolean tryOpen(LootableContainerBlockEntity container, PlayerEntity player) {
        World world = player.getWorld();
        if (world.isClient()) {
            return true;
        }
        if (player.isSpectator()) {
            return true;
        }
        DimPos pos = DimPos.of(world, container.getPos());

        if (LootRegistry.isOpen(pos)) {
            UUID viewer = LootRegistry.getViewer(pos);
            if (player instanceof ServerPlayerEntity spe && !spe.getUuid().equals(viewer)) {
                spe.sendMessage(Text.literal("Someone else is already looting this."), true);
            }
            return false;
        }

        if (container.getLootTable() == null) {
            return true;
        }

        MinecraftServer server = Objects.requireNonNull(player.getServer());

        if (player instanceof ServerPlayerEntity spe && LootRegistry.hasLooted(server, spe.getUuid(), pos)) {
            spe.sendMessage(Text.literal("You have already looted this chest."), true);
            return false;
        }

        LootRegistry.markOpen(pos, player.getUuid());
        return true;
    }

    public static void applyLoot(LootableContainerBlockEntity container, PlayerEntity player) {
        World world = player.getWorld();
        if (world.isClient()) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        var lootTable = container.getLootTable();
        if (lootTable == null) {
            return;
        }

        DimPos pos = DimPos.of(world, container.getPos());

        Direction facing = readFacing(world, pos.pos());
        LootRegistry.capturePendingRoll(pos, lootTable, facing);

        container.generateLoot(player);
    }

    public static void onClose(LootableContainerBlockEntity container, PlayerEntity player, MinecraftServer server) {
        World world = player.getWorld();
        if (world.isClient()) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        DimPos dimPos = DimPos.of(world, container.getPos());

        UUID viewer = LootRegistry.getViewer(dimPos);
        if (viewer == null || !viewer.equals(player.getUuid())) {
            return;
        }

        LootRegistry.markLooted(server, player.getUuid(), dimPos);
        LootRegistry.markClosed(dimPos);

        LootRegistry.LootRecord record = LootRegistry.takePendingRoll(dimPos);
        if (record == null) {
            return;
        }

        server.execute(() -> breakAndReplace(dimPos, record, server));
    }

    private static Direction readFacing(World world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.contains(Properties.HORIZONTAL_FACING)
                ? state.get(Properties.HORIZONTAL_FACING)
                : Direction.NORTH;
    }

    private static void breakAndReplace(DimPos dimPos, LootRegistry.LootRecord record, MinecraftServer server) {
        if (!(server.getWorld(dimPos.dimension()) instanceof net.minecraft.server.world.ServerWorld world)) {
            return;
        }
        BlockPos pos = dimPos.pos();
        var blockState = world.getBlockState(pos);
        if (!(world.getBlockEntity(pos) instanceof LootableContainerBlockEntity)) {
            return;
        }

        var block = blockState.getBlock();

        world.breakBlock(pos, true, null, 512);

        net.minecraft.block.BlockState newState;
        if (block instanceof net.minecraft.block.ChestBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.ChestBlock.FACING, record.facing());
        } else if (block instanceof net.minecraft.block.BarrelBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, record.facing());
        } else {
            newState = block.getDefaultState();
        }
        world.setBlockState(pos, newState);

        if (world.getBlockEntity(pos) instanceof LootableContainerBlockEntity newContainer) {
            newContainer.setLootTable(record.lootTable(), 0L);
            SPOILED_REPORTED.remove(dimPos);
        }
    }

    public static void forceCloseViewer(DimPos pos, MinecraftServer server) {
        UUID viewer = LootRegistry.getViewer(pos);
        if (viewer == null) {
            return;
        }
        ServerPlayerEntity spe = server.getPlayerManager().getPlayer(viewer);
        if (spe != null) {
            spe.closeHandledScreen();
        } else {
            LootRegistry.markClosed(pos);
        }
    }

    public static void neutralizeLootOnBreak(LootableContainerBlockEntity container) {
        if (container.getLootTable() != null) {
            container.setLootTable(null, 0L);
        }
    }
}
