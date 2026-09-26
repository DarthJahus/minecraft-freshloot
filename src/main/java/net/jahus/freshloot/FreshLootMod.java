package net.jahus.freshloot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.jahus.freshloot.LootRegistry.DimPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class FreshLootMod implements ModInitializer {
    public static final String MOD_ID = "freshloot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown()
                    && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) {
                return InteractionResult.PASS;
            }

            BlockPos blockPos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(blockPos);
            if (!(be instanceof RandomizableContainerBlockEntity container)
                    || !(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
                return InteractionResult.PASS;
            }

            BlockState state = world.getBlockState(blockPos);

            if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                return InteractionResult.PASS;
            }

            if (!LootContainerHandler.tryOpen(container, player)) {
                return InteractionResult.FAIL;
            }
            LootContainerHandler.applyLoot(container, player);

            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) {
                return true;
            }
            if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                return true;
            }
            if (!(player instanceof ServerPlayer spe)) {
                return true;
            }
            if (player.isSpectator()) {
                return true;
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

            return true;
        });

        LOGGER.info("FreshLoot loaded!");
    }
}
