package net.jahus.freshloot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import net.jahus.freshloot.LootRegistry.DimPos;

public class FreshLootMod implements ModInitializer {
    public static final String MOD_ID = "freshloot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }
            // Vanilla only opens a container when the player is NOT sneaking with
            // something in hand. Sneak + item means "place / use the item against
            // this block": the container must not be opened, so the loot table
            // must not be consumed here (the placement guards need it intact).
            // Sneaking with empty hands still opens the container normally.
            if (player.isSneaking()
                    && (!player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty())) {
                return ActionResult.PASS;
            }

            var blockPos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(blockPos);
            if (!(be instanceof LootableContainerBlockEntity container)
                    || !(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
                return ActionResult.PASS;
            }

            BlockState state = world.getBlockState(blockPos);

            // v1.0.x limitation: double chests are left untouched. Only single chests are managed.
            if (be instanceof ChestBlockEntity && state.contains(ChestBlock.CHEST_TYPE)
                    && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                return ActionResult.PASS;
            }

            if (!LootContainerHandler.tryOpen(container, player)) {
                return ActionResult.FAIL;
            }
            LootContainerHandler.applyLoot(container, player);

            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) {
                return true;
            }
            if (!(blockEntity instanceof LootableContainerBlockEntity container)) {
                return true;
            }
            if (!(player instanceof ServerPlayerEntity spe)) {
                return true;
            }
            if (player.isSpectator()) {
                return true;
            }

            DimPos dimPos = DimPos.of(world, pos);
            MinecraftServer server = spe.getServer();

            boolean neverLooted = container.getLootTable() != null;
            UUID viewerUuid = LootRegistry.getViewer(dimPos);
            boolean isCurrentlyOpen = viewerUuid != null;
            boolean openByOther = isCurrentlyOpen && !viewerUuid.equals(spe.getUuid());

            if (neverLooted || isCurrentlyOpen) {
                Text publicWarning = Text.literal(spe.getName().getString() + " broke a loot container!")
                        .formatted(Formatting.RED);
                server.getPlayerManager().broadcast(publicWarning, false);

                String lootTableId = container.getLootTable() != null
                        ? container.getLootTable().getValue().toString()
                        : "unknown";
                LOGGER.warn(
                        "[FreshLoot] {} broke an unlooted loot container at {} [{}, {}, {}] (loot table: {})",
                        spe.getName().getString(),
                        dimPos.dimension().getValue(),
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
