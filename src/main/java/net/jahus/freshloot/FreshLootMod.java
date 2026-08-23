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

            // "This player is destroying a loot opportunity instead of
            // claiming it through the GUI" -- true whenever this exact
            // container instance still carries a live, never-rolled loot
            // table right now, regardless of whether this same player
            // already looted an earlier container at this position in a
            // previous rotation. hasLooted() is keyed by position, not by
            // block instance, so gating on it here would wrongly silence
            // the warning for a player who breaks a freshly-rotated,
            // still-unlooted replacement at a position they'd looted
            // before -- exactly the "no message" case reported. Read
            // getLootTable() -- and log/broadcast -- before
            // neutralizeLootOnBreak below can clear the table.
            boolean neverLooted = container.getLootTable() != null;

            if (neverLooted) {
                Text publicWarning = Text.literal(spe.getName().getString() + " broke a loot container before looting it!")
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
            UUID viewerUuid = LootRegistry.getViewer(dimPos);
            if (viewerUuid != null && !viewerUuid.equals(spe.getUuid())) {
                LootContainerHandler.forceCloseViewer(dimPos, server);
            }

            // Strip the loot table right before vanilla processes the
            // break: any container that still carries a live LootTable
            // NBT tag right now gets it cleared (see
            // neutralizeLootOnBreak), so the block drops empty instead of
            // granting loot to someone destroying it outside the normal
            // open/close cycle. Same predicate as the warning above, so
            // "message sent" and "loot denied" always agree.
            LootContainerHandler.neutralizeLootOnBreak(container);

            return true;
        });

        LOGGER.info("FreshLoot loaded!");
    }
}
