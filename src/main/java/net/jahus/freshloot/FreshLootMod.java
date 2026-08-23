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
        // Single entry point for right-click interaction with a managed
        // container. Deciding open/deny here, before vanilla's own
        // interaction handling runs, matters: cancelling the block
        // entity's onOpen instead (an earlier approach) only skips our
        // loot logic, it does NOT stop vanilla from opening the
        // ScreenHandler (createScreenHandlerFactory/createMenu don't
        // consult onOpen at all). That let a denied player still see an
        // (empty) open GUI, and left the container's open/viewer-count
        // state stuck since onClose later found no tracked viewer to
        // match. Returning ActionResult.FAIL here instead genuinely blocks
        // the interaction -- no ScreenHandler ever opens for a denied
        // player.
        //
        // A container is "managed" purely based on carrying a non-null
        // LootTable NBT tag right now -- see LootContainerHandler's class
        // javadoc for why there's no separate registry of managed
        // positions to fall out of sync with the world after a restart.
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

            // Grief warning fires whenever the breaker is destroying a
            // loot opportunity instead of letting it run its normal
            // course, in either of two independent ways:
            //   - the container instance still carries a live,
            //     never-rolled LootTable NBT tag right now (never opened
            //     by anyone; a freshly-rotated replacement the breaker
            //     already looted once under a previous instance; or one
            //     the in-memory registry lost track of after a restart);
            //   - this exact position is currently open by ANYONE,
            //     including the breaker themselves. Destroying a container
            //     that's mid-open wipes out a loot opportunity that could
            //     otherwise exist for other players (the position's whole
            //     rotation cycle never completes), which is griefing
            //     regardless of whose GUI happens to be open when it
            //     happens -- applyLoot already cleared the NBT the moment
            //     that GUI opened, so the NBT alone can't signal this.
            // Read both before neutralizeLootOnBreak below can clear the
            // table, and before forceCloseViewer below can clear the
            // open/viewer record.
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

            // If this container is actively being looted by someone else
            // right now, force their screen closed so state cleans up
            // through the normal onClose path rather than leaving a stale
            // "open" record. A player breaking their own currently-open
            // container (e.g. mining through it) doesn't need this.
            if (openByOther) {
                LootContainerHandler.forceCloseViewer(dimPos, server);
            }

            // Strip the loot table right before vanilla processes the
            // break: any container that still carries a live LootTable
            // NBT tag right now gets it cleared (see
            // neutralizeLootOnBreak), so the block drops empty instead of
            // granting loot to someone destroying it outside the normal
            // open/close cycle. Note this is narrower than the warning
            // above on purpose: the isCurrentlyOpen-but-NBT-already-null
            // case has nothing left to clear (that viewer's loot was
            // already rolled the moment their GUI opened), so
            // neutralizeLootOnBreak is naturally a no-op there.
            LootContainerHandler.neutralizeLootOnBreak(container);

            return true;
        });

        LOGGER.info("FreshLoot loaded!");
    }
}
