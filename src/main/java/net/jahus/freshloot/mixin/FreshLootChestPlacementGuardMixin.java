package net.jahus.freshloot.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Denies placing a chest that vanilla would merge into a double chest with a
 * chest still carrying a loot table.
 *
 * Instead of re-implementing vanilla's merge logic (which diverged depending
 * on whether the player aimed at the chest side or at the adjacent floor),
 * we let vanilla compute the placement state, and only look at the result:
 * CHEST_TYPE != SINGLE means a merge is about to happen.
 *
 * To find the partner without depending on the LEFT/RIGHT convention, we check
 * both lateral neighbours and match on what vanilla requires to merge:
 * same block (chest vs trapped chest), SINGLE, same FACING.
 */
@Mixin(BlockItem.class)
public abstract class FreshLootChestPlacementGuardMixin {

    private static final String DENY_MESSAGE = "\u00a7cYou can't place a chest next to a loot chest!";

    @Shadow
    @Nullable
    protected abstract BlockState getPlacementState(ItemPlacementContext context);

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void freshloot$denyChestNextToLootChest(ItemPlacementContext context,
                                                    CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        if (world.isClient()) {
            return;
        }

        BlockItem self = (BlockItem) (Object) this;
        if (!(self.getBlock() instanceof ChestBlock)) {
            return;
        }

        ItemPlacementContext resolved = self.getPlacementContext(context);
        if (resolved == null) {
            return;
        }

        BlockState state = this.getPlacementState(resolved);
        if (state == null
                || !state.contains(ChestBlock.CHEST_TYPE)
                || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return;
        }

        BlockPos placedPos = resolved.getBlockPos();
        Direction facing = state.get(ChestBlock.FACING);

        if (freshloot$isLootPartner(world, placedPos.offset(facing.rotateYClockwise()), state)
                || freshloot$isLootPartner(world, placedPos.offset(facing.rotateYCounterclockwise()), state)) {
            cir.setReturnValue(ActionResult.FAIL);
            PlayerEntity player = context.getPlayer();
            if (player instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.sendMessage(Text.literal(DENY_MESSAGE), true);
            }
        }
    }

    private static boolean freshloot$isLootPartner(World world, BlockPos neighborPos, BlockState newState) {
        BlockState neighbor = world.getBlockState(neighborPos);
        if (neighbor.getBlock() != newState.getBlock()) {
            return false;
        }
        if (neighbor.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            return false;
        }
        if (neighbor.get(ChestBlock.FACING) != newState.get(ChestBlock.FACING)) {
            return false;
        }
        BlockEntity be = world.getBlockEntity(neighborPos);
        return be instanceof ChestBlockEntity chest && chest.getLootTable() != null;
    }
}
