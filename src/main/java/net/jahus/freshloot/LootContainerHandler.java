package net.jahus.freshloot;

import net.jahus.freshloot.LootRegistry.DimPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LootContainerHandler {

    private LootContainerHandler() {}

    private static final Set<DimPos> SPOILED_REPORTED = ConcurrentHashMap.newKeySet();

    public static boolean isUnclaimedLootContainer(BlockEntity be) {
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
            return false;
        }
        return be instanceof RandomizableContainerBlockEntity lootable && lootable.getLootTable() != null;
    }

    public static void reportSpoiledByExtraction(RandomizableContainerBlockEntity container, Level world, BlockPos pos) {
        if (world.isClientSide() || !(world instanceof ServerLevel serverWorld)) {
            return;
        }
        DimPos key = DimPos.of(world, pos);
        if (!SPOILED_REPORTED.add(key)) {
            return;
        }

        String lootTableId = container.getLootTable() != null
                ? container.getLootTable().identifier().toString()
                : "unknown";

        MinecraftServer server = serverWorld.getServer();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("A loot container was spoiled by an external source at "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "!")
                        .withStyle(ChatFormatting.RED),
                false);

        FreshLootMod.LOGGER.warn(
                "[FreshLoot] Unclaimed loot container spoiled by an external source at {} [{}, {}, {}] (loot table: {})",
                world.dimension().identifier(),
                pos.getX(), pos.getY(), pos.getZ(),
                lootTableId);

        neutralizeLootOnBreak(container);
    }

    public static boolean tryOpen(RandomizableContainerBlockEntity container, Player player) {
        Level world = player.level();
        if (world.isClientSide()) {
            return true;
        }
        if (player.isSpectator()) {
            return true;
        }
        DimPos pos = DimPos.of(world, container.getBlockPos());

        if (LootRegistry.isOpen(pos)) {
            UUID viewer = LootRegistry.getViewer(pos);
            if (player instanceof ServerPlayer spe && !spe.getUUID().equals(viewer)) {
                spe.sendSystemMessage(Component.literal("Someone else is already looting this."));
            }
            return false;
        }

        if (container.getLootTable() == null) {
            return true;
        }

        MinecraftServer server = Objects.requireNonNull(player.level().getServer());

        if (player instanceof ServerPlayer spe && LootRegistry.hasLooted(server, spe.getUUID(), pos)) {
            spe.sendSystemMessage(Component.literal("You have already looted this chest."));
            return false;
        }

        LootRegistry.markOpen(pos, player.getUUID());
        return true;
    }

    public static void applyLoot(RandomizableContainerBlockEntity container, Player player) {
        Level world = player.level();
        if (world.isClientSide()) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        var lootTable = container.getLootTable();
        if (lootTable == null) {
            return;
        }

        DimPos pos = DimPos.of(world, container.getBlockPos());

        Direction facing = readFacing(world, pos.pos());
        LootRegistry.capturePendingRoll(pos, lootTable, facing);

        container.unpackLootTable(player);
    }

    public static void onClose(RandomizableContainerBlockEntity container, Player player, MinecraftServer server) {
        Level world = player.level();
        if (world.isClientSide()) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        DimPos dimPos = DimPos.of(world, container.getBlockPos());

        UUID viewer = LootRegistry.getViewer(dimPos);
        if (viewer == null || !viewer.equals(player.getUUID())) {
            return;
        }

        LootRegistry.markLooted(server, player.getUUID(), dimPos);
        LootRegistry.markClosed(dimPos);

        LootRegistry.LootRecord record = LootRegistry.takePendingRoll(dimPos);
        if (record == null) {
            return;
        }

        server.execute(() -> breakAndReplace(dimPos, record, server));
    }

    private static Direction readFacing(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
    }

    private static void breakAndReplace(DimPos dimPos, LootRegistry.LootRecord record, MinecraftServer server) {
        if (!(server.getLevel(dimPos.dimension()) instanceof ServerLevel world)) {
            return;
        }
        BlockPos pos = dimPos.pos();
        BlockState blockState = world.getBlockState(pos);
        if (!(world.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity)) {
            return;
        }

        var block = blockState.getBlock();

        world.destroyBlock(pos, true, null);

        BlockState newState;
        if (block instanceof ChestBlock) {
            newState = block.defaultBlockState().setValue(ChestBlock.FACING, record.facing());
        } else if (block instanceof BarrelBlock) {
            newState = block.defaultBlockState().setValue(BarrelBlock.FACING, record.facing());
        } else {
            newState = block.defaultBlockState();
        }
        world.setBlock(pos, newState, 3);

        if (world.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity newContainer) {
            newContainer.setLootTable(record.lootTable());
            SPOILED_REPORTED.remove(dimPos);
        }
    }

    public static void forceCloseViewer(DimPos pos, MinecraftServer server) {
        UUID viewer = LootRegistry.getViewer(pos);
        if (viewer == null) {
            return;
        }
        ServerPlayer spe = server.getPlayerList().getPlayer(viewer);
        if (spe != null) {
            spe.closeContainer();
        } else {
            LootRegistry.markClosed(pos);
        }
    }

    public static void neutralizeLootOnBreak(RandomizableContainerBlockEntity container) {
        if (container.getLootTable() != null) {
            container.setLootTable(null);
        }
    }
}
