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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.jahus.freshloot.LootRegistry.DimPos;

public class FreshLootMod implements ModInitializer {
    public static final String MOD_ID = "freshloot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Single entry point for right-click interaction with a managed
        // container. This used to only register the loot table, leaving the
        // actual open/deny decision to ChestBlockEntityMixin/
        // BarrelBlockEntityMixin's onOpen injection -- but cancelling
        // onOpen only skips our loot logic, it does NOT stop vanilla from
        // opening the ScreenHandler (createScreenHandlerFactory/createMenu
        // don't consult onOpen at all). That let a denied player still see
        // an (empty) open GUI, and left the container's open/viewer-count
        // state stuck since onClose later found no tracked viewer to match.
        //
        // Doing the tryOpen/applyLoot check here instead, before vanilla's
        // own handling runs, lets us return ActionResult.FAIL to genuinely
        // block the interaction -- no ScreenHandler ever opens for a denied
        // player, so there's nothing left open and nothing for onClose to
        // clean up.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }
            var blockPos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(blockPos);
            if (!(be instanceof LootableContainerBlockEntity container)
                    || !(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) {
                return ActionResult.PASS;
            }

            BlockState state = world.getBlockState(blockPos);

            // v1.0.0 limitation: double chests are left untouched (vanilla
            // shared-loot behavior applies). Only single chests are managed.
            if (be instanceof ChestBlockEntity && state.contains(ChestBlock.CHEST_TYPE)
                    && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                return ActionResult.PASS;
            }

            DimPos dimPos = DimPos.of(world, blockPos);

            // Register this container's loot table the first time anyone
            // interacts with it, while it still has vanilla's original
            // table/seed intact (i.e. before vanilla's own one-shot
            // mechanic consumes it).
            if (!LootRegistry.isRegistered(dimPos) && container.getLootTable() != null) {
                Direction facing = state.contains(Properties.HORIZONTAL_FACING)
                        ? state.get(Properties.HORIZONTAL_FACING)
                        : Direction.NORTH;
                LootRegistry.register(dimPos, container.getLootTable(), facing);
            }

            if (!LootRegistry.isRegistered(dimPos)) {
                // Not (or no longer) a container we manage -- let vanilla
                // behave normally.
                return ActionResult.PASS;
            }

            if (!LootContainerHandler.tryOpen(container, player)) {
                // Genuinely block the interaction: vanilla never opens a
                // ScreenHandler for this click, so there's no stray open
                // GUI and no dangling viewer state to reconcile later.
                return ActionResult.FAIL;
            }
            LootContainerHandler.applyLoot(container, player);

            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) {
                return true;
            }
            DimPos dimPos = DimPos.of(world, pos);
            if (!(blockEntity instanceof LootableContainerBlockEntity container) || !LootRegistry.isRegistered(dimPos)) {
                return true;
            }
            if (!(player instanceof ServerPlayerEntity spe)) {
                return true;
            }

            // If someone else currently has this open, force their screen
            // closed first so state cleans up through the normal onClose
            // path rather than leaving a stale "open" record.
            if (LootRegistry.isOpen(dimPos)) {
                LootContainerHandler.forceCloseViewer(dimPos, spe.getServer());
            }

            // If the breaker already looted this exact position (e.g. this
            // is the freshly-spawned replacement container right after they
            // closed their own GUI on it), strip its loot table before
            // vanilla processes the break, so it drops empty instead of
            // granting a second roll.
            LootContainerHandler.neutralizeIfAlreadyLooted(container, spe, spe.getServer());

            return true;
        });

        LOGGER.info("FreshLoot loaded!");
    }
}
