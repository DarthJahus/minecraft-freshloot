package net.jahus.freshloot;

import net.jahus.freshloot.LootRegistry.DimPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@Mod(FreshLootMod.MOD_ID)
public final class FreshLootMod {

    public static final String MOD_ID = "freshloot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FreshLootMod() {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("FreshLoot (NeoForge) loaded!");
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level world = event.getLevel();
        if (world.isClientSide()) {
            return;
        }
        var player = event.getEntity();
        if (player.isShiftKeyDown()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) {
            return;
        }

        BlockPos blockPos = event.getPos();
        BlockEntity be = world.getBlockEntity(blockPos);
        if (!(be instanceof RandomizableContainerBlockEntity container)
                || !(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
            return;
        }

        BlockState state = world.getBlockState(blockPos);
        if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return;
        }

        if (!LootContainerHandler.tryOpen(container, player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        LootContainerHandler.applyLoot(container, player);
    }

    @SubscribeEvent
    public void onBlockBreak(BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer spe)) {
            return;
        }
        if (spe.isSpectator()) {
            return;
        }
        Level world = spe.level();
        if (world.isClientSide()) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
            return;
        }

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
