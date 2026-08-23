package net.jahus.freshloot;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.UUID;

import net.jahus.freshloot.LootRegistry.DimPos;

/**
 * Shared logic invoked by FreshLootMod's UseBlockCallback (open/loot) and
 * by ChestBlockEntityMixin/BarrelBlockEntityMixin's onClose injection
 * (close/replace).
 *
 * A position is "managed" purely based on whether it currently carries a
 * non-null LootTable NBT tag -- there is no separate registry of managed
 * positions to fall out of sync with the world after a restart. Every
 * replacement container gets that same NBT tag stamped back onto it at
 * rotation time (see breakAndReplace), so a rotated container is
 * indistinguishable from a freshly-generated vanilla one on the next
 * server start: it just works the same way it did the first time.
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
 *
 *     applyLoot captures the container's loot table + facing into
 *     LootRegistry's short-lived PENDING_ROLL map *before* calling
 *     generateLoot() -- vanilla clears the LootTable NBT tag the instant
 *     loot is actually generated, so this is the last point at which that
 *     information is readable off the block itself. onClose picks it back
 *     up a tick later to stamp it onto the replacement.
 *   - onClose: marks the closing player as having looted this position
 *     (persisted -- survives restarts), then breaks the block outright
 *     (world.breakBlock, item drops handled by vanilla) and places a fresh
 *     container with the same loot table + facing for the next player,
 *     written straight into its LootTable NBT tag (not just kept in
 *     memory) so the position survives a restart on its own. The
 *     break+replace is deferred a tick (server.execute) to avoid
 *     re-entering this same block entity's onOpen/onClose from inside its
 *     own onClose.
 *   - onPlayerBreak (block-break event): any container that still carries
 *     a live LootTable NBT tag right now has that table cleared right
 *     before vanilla processes the break, so it drops as an empty
 *     chest/barrel instead of rolling loot for the breaker. This single
 *     rule covers every case that matters:
 *       - the breaker already looted this exact position and immediately
 *         breaks the freshly-spawned replacement themselves (that
 *         replacement carries a live, never-rolled table -- without this
 *         it would let that player loot the same position twice, once via
 *         the GUI and once via breaking the duplicate);
 *       - a container nobody has ever opened, broken outright instead of
 *         looted through the GUI (grief case);
 *       - a container the in-memory registry lost track of after a server
 *         restart, whose LootTable NBT nonetheless survived on the block
 *         itself.
 *     Accepted as a known v1.0.0 tradeoff for the first case: the
 *     duplicate container itself still gets created and only its loot is
 *     neutralized, rather than preventing the duplicate from spawning at
 *     all.
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
        if (player.isSpectator()) {
            // Spectators can already see into a container someone else has
            // open (vanilla behavior) without going through our tracking at
            // all -- letting them through tryOpen would incorrectly consume
            // "open" state (or worse, get treated as looting/closing the
            // container once their screen closes). Never register them as
            // a viewer and never generate loot for them.
            return true;
        }
        DimPos pos = DimPos.of(world, container.getPos());

        // Check "is someone already looting this position?" BEFORE the
        // getLootTable()==null shortcut below. generateLoot() (called from
        // applyLoot) clears the block's LootTable NBT the instant the
        // first viewer's loot is rolled -- well before the deferred
        // break+replace (next tick) re-stamps a fresh table onto the
        // replacement. During that window the NBT-based "is this managed"
        // check alone can't tell a mid-session container apart from an
        // unmanaged one, so a second player clicking the same still-open
        // container in that window must still be caught here, not fall
        // through to vanilla's normal open behavior.
        if (LootRegistry.isOpen(pos)) {
            UUID viewer = LootRegistry.getViewer(pos);
            if (player instanceof ServerPlayerEntity spe && !spe.getUuid().equals(viewer)) {
                spe.sendMessage(Text.literal("Someone else is already looting this."), true);
            }
            return false;
        }

        if (container.getLootTable() == null) {
            // No loot table on this block right now -- either not a
            // container FreshLoot manages, or already mid-generation from
            // another path. Let vanilla behave normally.
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
        if (player.isSpectator()) {
            // Never roll loot for a spectator peeking in -- that would
            // consume the container's fresh-seed roll (or prematurely
            // populate it) for someone who isn't actually looting it.
            return;
        }
        var lootTable = container.getLootTable();
        if (lootTable == null) {
            return;
        }

        DimPos pos = DimPos.of(world, container.getPos());

        // Capture table + facing now, before generateLoot() below clears
        // the LootTable NBT tag -- this is the last point it's readable
        // off the block. onClose picks it back up a tick later to stamp
        // it onto the replacement container.
        Direction facing = readFacing(world, pos.pos());
        LootRegistry.capturePendingRoll(pos, lootTable, facing);

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
        if (player.isSpectator()) {
            // A spectator was never registered as the viewer (see tryOpen),
            // so this would already fall through the viewer check below --
            // but guard explicitly: a spectator closing their peek-in must
            // never be treated as having looted this position or trigger
            // the break+replace rotation.
            return;
        }
        DimPos dimPos = DimPos.of(world, container.getPos());

        UUID viewer = LootRegistry.getViewer(dimPos);
        if (viewer == null || !viewer.equals(player.getUuid())) {
            // Not the tracked viewer closing (defensive: shouldn't normally happen).
            return;
        }

        // This player has now used their one roll on this position, forever.
        LootRegistry.markLooted(server, player.getUuid(), dimPos);
        LootRegistry.markClosed(dimPos);

        LootRegistry.LootRecord record = LootRegistry.takePendingRoll(dimPos);
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
        // untouched. ChestBlock and BarrelBlock each declare their own
        // FACING property.
        net.minecraft.block.BlockState newState;
        if (block instanceof net.minecraft.block.ChestBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.ChestBlock.FACING, record.facing());
        } else if (block instanceof net.minecraft.block.BarrelBlock) {
            newState = block.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, record.facing());
        } else {
            newState = block.getDefaultState();
        }
        world.setBlockState(pos, newState);

        // Stamp the preserved loot table directly onto the new block's
        // NBT -- not just an in-memory record -- so this position is a
        // self-describing, ordinary-looking loot container again. If the
        // server restarts before anyone opens it, FreshLootMod's
        // UseBlockCallback will recognize it exactly like a fresh vanilla
        // container, with no dependency on anything surviving in memory.
        //
        // Seed is 0L here deliberately -- an unrolled seed, same as a
        // fresh vanilla loot container that has never been opened. The
        // real seed is only generated in applyLoot, right before
        // generateLoot() actually rolls the loot for whoever opens this
        // container next (world.getRandom().nextLong() there). Stamping a
        // real seed here, at placement time, would fix this container's
        // roll the moment it's placed -- before anyone has opened it --
        // instead of leaving it fresh until the actual roll happens.
        if (world.getBlockEntity(pos) instanceof LootableContainerBlockEntity newContainer) {
            newContainer.setLootTable(record.lootTable(), 0L);
        }
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
     * Call from a block-break event (BEFORE), for any loot container about
     * to be broken. Any container that currently carries a live LootTable
     * NBT tag -- regardless of why (never opened by anyone, or a
     * freshly-rotated replacement the breaker already looted once under a
     * previous instance, or a container the in-memory registry lost track
     * of after a server restart) -- has that table cleared right now, so
     * vanilla's break-time loot generation (if any) has nothing to roll
     * and the block drops empty.
     */
    public static void neutralizeLootOnBreak(LootableContainerBlockEntity container) {
        if (container.getLootTable() != null) {
            container.setLootTable(null, 0L);
        }
    }
}
