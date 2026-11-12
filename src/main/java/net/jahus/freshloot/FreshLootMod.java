package net.jahus.freshloot;

import net.jahus.freshloot.LootRegistry.DimPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Forge EventBus 7: cancellable listeners return {@code boolean} ({@code true} = cancel).
 * No {@code setCanceled} / {@code setCancellationResult}.
 */
@Mod(FreshLootMod.MOD_ID)
@Mod.EventBusSubscriber(modid = FreshLootMod.MOD_ID)
public final class FreshLootMod {

    public static final String MOD_ID = "freshloot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FreshLootMod() {
        LOGGER.info("FreshLoot (Forge) loaded!");
    }

    @SubscribeEvent
    public static boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level world = event.getLevel();
        if (world.isClientSide()) {
            return false;
        }
        var player = event.getEntity();
        if (player.isShiftKeyDown()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) {
            return false;
        }

        BlockPos blockPos = event.getPos();
        BlockEntity be = world.getBlockEntity(blockPos);
        if (!(be instanceof RandomizableContainerBlockEntity container)
                || !(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
            return false;
        }

        BlockState state = world.getBlockState(blockPos);
        if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return false;
        }

        if (!LootContainerHandler.tryOpen(container, player)) {
            return true;  // cancel open
        }
        LootContainerHandler.applyLoot(container, player);
        return false;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Level world = event.getPlayer().level();
        if (world.isClientSide()) {
            return;
        }
        BlockEntity blockEntity = world.getBlockEntity(event.getPos());
        if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer spe)) {
            return;
        }
        if (spe.isSpectator()) {
            return;
        }

        BlockPos pos = event.getPos();
        DimPos dimPos = DimPos.of(world, pos);
        MinecraftServer server = spe.level().getServer();

        boolean neverLooted = container.getLootTable() != null;
        UUID viewerUuid = LootRegistry.getViewer(dimPos);
        boolean isCurrentlyOpen = viewerUuid != null;
        boolean openByOther = isCurrentlyOpen && !viewerUuid.equals(spe.getUUID());

        if (neverLooted || isCurrentlyOpen) {
            Component publicWarning = Component.literal(spe.getName().getString() + " broke a loot container!")
                    .withStyle(ChatFormatting.RED);
            server.getPlayerList().broadcastSystemMessage(publicWarning, false);

            String lootTableId = container.getLootTable() != null
                    ? container.getLootTable().identifier().toString()
                    : "unknown";
            LOGGER.warn(
                    "[FreshLoot] {} broke an unlooted loot container at {} [{}, {}, {}] (loot table: {})",
                    spe.getName().getString(),
                    dimPos.dimension().identifier(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    lootTableId
            );
        }

        if (openByOther) {
            LootContainerHandler.forceCloseViewer(dimPos, server);
        }

        LootContainerHandler.neutralizeLootOnBreak(container);
    }
}
