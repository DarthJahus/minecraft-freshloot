package net.jahus.freshloot;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.UUID;

import net.jahus.freshloot.LootRegistry.DimPos;

/**
 * Shared logic invoked by FreshLootMod's UseBlockCallback (open/loot) and
 * by ChestBlockEntityMixin/BarrelBlockEntityMixin's onClose injection
 * (close/replace).
 *
 * Lifecycle for a managed position:
 *   - tryOpen/applyLoot: called from UseBlockCallback, before vanilla opens
 *     any ScreenHandler for the interaction. Blocked (ActionResult.FAIL
 *     upstream) if this player already looted this position (ever,
 *     persisted), or if someone else currently has it open. Otherwise loot
 *     is rolled fresh every time, since a position can only be re-opened
 *     after a full close cycle (see onClose) -- there's no "re-open the
 *     same still-open session" path once a close has actually broken the
 *     block. Deciding this upstream of vanilla's own open logic (rather
 *     than cancelling the block entity's onOpen) matters: onOpen/onClose on
 *     LootableContainerBlockEntity only drive the viewer-count/animation
 *     bookkeeping, not whether a ScreenHandler gets created, so cancelling
 *     onOpen alone left a denied player looking at an empty open GUI with
 *     no tracked viewer for onClose to later match and clean up.
 *   - onClose: marks the closing player as having looted this position
 *     (persisted -- survives restarts), then breaks the block outright
 *     (world.breakBlock, item drops handled by vanilla) and places a fresh
 *     container with the same loot table + facing for the next player. The
 *     break+replace is deferred a tick (server.execute) to avoid re-entering
 *     this same block entity's onOpen/onClose from inside its own onClose.
 *   - onPlayerBreak (block-break event): if the breaker already looted this
 *     position, the freshly-placed replacement container's loot table is
 *     cleared right before vanilla processes the break, so it drops as an
 *     empty chest/barrel instead of rolling a second loot for them. This
 *     covers the case where the same player who just closed the container
 *     immediately breaks the replacement themselves -- without this, that
 *     replacement would still carry a live, never-rolled loot table and
 *     vanilla would generate loot for it on break, letting that player loot
 *     the same position twice (once via the GUI, once via breaking the
 *     freshly-spawned duplicate). Accepted as a known v1.0.0 tradeoff: the
 *     duplicate container itself still gets created and only its loot is
 *     neutralized, rather than preventing the duplicate from spawning at all.
 */
public final class LootContainerHandler {

    private LootContainerHandler() {}

    /**
     * Call from UseBlockCallback, before vanilla's own interaction handling
     * runs. Returns true if the caller should let vanilla proceed with
     * opening the container, false if the open must be blocked (the caller
     * should return ActionResult.FAIL so no ScreenHandler ever opens).
     *
     * Note: this does not itself generate loot. Call {@link #applyLoot}
     * separately once you've confirmed the open is allowed.
     */
    public static boolean tryOpen(LootableContainerBlockEntity container, PlayerEntity player) {
        World world = player.getWorld();
        if (world.isClient()) {
            return true;
        }
        DimPos pos = DimPos.of(world, container.getPos());

        if (!LootRegistry.isRegistered(pos)) {
            // Not a loot container we manage -- let vanilla behave normally.
            return true;
        }

        MinecraftServer server = Objects.requireNonNull(player.getServer());

        if (player instanceof ServerPlayerEntity spe && LootRegistry.hasLooted(server, spe.getUuid(), pos)) {
            spe.sendMessage(Text.literal("You have already looted this chest."), true);
            return false;
        }

        if (LootRegistry.isOpen(pos)) {
            UUID viewer = LootRegistry.getViewer(pos);
            if (player instanceof ServerPlayerEntity spe && !spe.getUuid().equals(viewer)) {
                spe.sendMessage(Text.literal("Someone else is already looting this."), true);
            }
            return false;
        }

        LootRegistry.markOpen(pos, player.getUuid());
        return true;
    }

    /**
     * Call from UseBlockCallback, after tryOpen has returned true. Rolls
     * loot with a fresh seed.
     *
     * Per LootableInventory's Javadoc, generateLoot() is a default method
     * that nothing calls automatically ("Implementations should call this
     * method whenever the inventory is accessed") -- so setLootTable alone
     * does NOT populate the container; generateLoot() must be called
     * explicitly, same as the original mod does.
     */
    public static void applyLoot(LootableContainerBlockEntity container, PlayerEntity player) {
        World world = player.getWorld();
        if (world.isClient()) {
            return;
        }
        DimPos pos = DimPos.of(world, container.getPos());
        LootRegistry.LootRecord record = LootRegistry.get(pos);
        if (record == null) {
            return;
        }

        if (container.getLootTable() == null) {
            container.setLootTable(record.lootTable(), world.getRandom().nextLong());
        }
        container.generateLoot(player);
    }

    /**
     * Call from onClose (HEAD). Marks the closing player as having used
     * their roll on this position (persisted), then schedules a break+
     * replace for the next tick.
     */
    public static void onClose(LootableContainerBlockEntity container, PlayerEntity player, MinecraftServer server) {
        World world = player.getWorld();
        if (world.isClient()) {
            return;
        }
        DimPos dimPos = DimPos.of(world, container.getPos());

        if (!LootRegistry.isRegistered(dimPos)) {
            return;
        }

        UUID viewer = LootRegistry.getViewer(dimPos);
        if (viewer == null || !viewer.equals(player.getUuid())) {
            // Not the tracked viewer closing (defensive: shouldn't normally happen).
            return;
        }

        LootRegistry.LootRecord record = LootRegistry.get(dimPos);

        // This player has now used their one roll on this position, forever.
        LootRegistry.markLooted(server, player.getUuid(), dimPos);
        LootRegistry.markClosed(dimPos);

        if (record == null) {
            return;
        }

        // Defer the actual break+replace to the next server tick. Doing it
        // synchronously here would mean calling world.breakBlock() on this
        // exact position from inside this block entity's own onClose --
        // breakBlock() removes the block entity and would very plausibly
        // re-enter onOpen/onClose on it (or on whatever replaces it) before
        // this call has even returned. Scheduling it via server.execute
        // runs it as a fresh top-level tick task instead, off this call stack.
        server.execute(() -> breakAndReplace(dimPos, record, server));
    }

    private static void breakAndReplace(DimPos dimPos, LootRegistry.LootRecord record, MinecraftServer server) {
        if (!(server.getWorld(dimPos.dimension()) instanceof net.minecraft.server.world.ServerWorld world)) {
            return;
        }
        BlockPos pos = dimPos.pos();
        var blockState = world.getBlockState(pos);
        if (!(world.getBlockEntity(pos) instanceof LootableContainerBlockEntity)) {
            // Something else already changed what's at this position (e.g.
            // another player/mod broke it in the meantime) -- don't force
            // our container back over it.
            return;
        }

        var block = blockState.getBlock();

        // Break the block outright -- vanilla's own break logic drops the
        // current contents, we don't scatter anything ourselves.
        // breakBlock(pos, drop, breakingEntity, maxUpdateDepth) -- no
        // specific breaking entity (null) since this isn't attributable to
        // a single player's action. maxUpdateDepth=512 is used here as a
        // generous ceiling on block-update propagation depth, not a
        // confirmed vanilla default -- worth double-checking against actual
        // call sites if block-update behavior looks off in testing.
        world.breakBlock(pos, true, null, 512);

        // Place a fresh container of the same type/facing so it looks
        // untouched, then re-register it with the preserved loot table for
        // whoever opens it next. ChestBlock and BarrelBlock each declare
        // their own FACING property.
        net.minecraft.block.BlockState newState;
        if (block instanceof net.minecraft.block.ChestBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.ChestBlock.FACING, record.facing());
        } else if (block instanceof net.minecraft.block.BarrelBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, record.facing());
        } else {
            newState = block.getDefaultState();
        }
        world.setBlockState(pos, newState);
        LootRegistry.register(dimPos, record.lootTable(), record.facing());
    }

    /**
     * Call from a block-break event, before the break is allowed to
     * proceed, if the container is currently open by someone else. Forces
     * their screen closed so the state cleans up via the normal onClose
     * path (which also handles the break+replace itself) instead of
     * leaving a dangling "open" record.
     */
    public static void forceCloseViewer(DimPos pos, MinecraftServer server) {
        UUID viewer = LootRegistry.getViewer(pos);
        if (viewer == null) {
            return;
        }
        ServerPlayerEntity spe = server.getPlayerManager().getPlayer(viewer);
        if (spe != null) {
            spe.closeHandledScreen();
        } else {
            // Viewer no longer online -- clean up directly, onClose won't fire for them.
            LootRegistry.markClosed(pos);
        }
    }

    /**
     * Call from a block-break event (BEFORE), for any managed container
     * that's about to be broken. If the breaking player already looted this
     * position, clears the container's loot table right now so vanilla's
     * break-time loot generation (if any) has nothing to roll -- the block
     * still breaks and drops as an empty container, rather than granting a
     * second loot roll to a player who already had their turn.
     */
    public static void neutralizeIfAlreadyLooted(LootableContainerBlockEntity container, ServerPlayerEntity player, MinecraftServer server) {
        World world = player.getWorld();
        DimPos pos = DimPos.of(world, container.getPos());
        if (!LootRegistry.isRegistered(pos)) {
            return;
        }
        if (LootRegistry.hasLooted(server, player.getUuid(), pos)) {
            container.setLootTable(null, 0L);
        }
    }
}
