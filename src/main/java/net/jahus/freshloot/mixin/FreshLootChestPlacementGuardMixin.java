package net.jahus.freshloot.mixin;

import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class FreshLootChestPlacementGuardMixin {

    private static final String DENY_MESSAGE = "\u00a7cYou can't place a chest next to a loot chest!";

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void freshloot$denyChestNextToLootChest(ItemPlacementContext context,
                                                      CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        if (world.isClient()) {
            return;
        }

        BlockItem self = (BlockItem) (Object) this;
        if (!(self.getBlock() instanceof AbstractChestBlock<?>)) {
            return;
        }

        // BlockItem#place resolves the actual placement position through
        // getPlacementContext, not context.getBlockPos() directly.
        ItemPlacementContext resolvedContext = self.getPlacementContext(context);
        if (resolvedContext == null) {
            return;
        }

        BlockPos placedPos = resolvedContext.getBlockPos();
        Direction newChestFacing = resolvedContext.getHorizontalPlayerFacing().getOpposite();

        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighborPos = placedPos.offset(side);
            BlockState neighborState = world.getBlockState(neighborPos);

            if (!(neighborState.getBlock() instanceof AbstractChestBlock<?>)) {
                continue;
            }
            if (!(world.getBlockEntity(neighborPos) instanceof ChestBlockEntity neighborChest)) {
                continue;
            }
            if (neighborChest.getLootTable() == null) {
                continue;
            }
            if (!neighborState.contains(Properties.HORIZONTAL_FACING)) {
                continue;
            }

            Direction neighborFacing = neighborState.get(Properties.HORIZONTAL_FACING);
            boolean sameFacing = neighborFacing == newChestFacing;
            boolean isPerpendicularSide = side == neighborFacing.rotateYClockwise()
                    || side == neighborFacing.rotateYCounterclockwise();

            if (sameFacing && isPerpendicularSide) {
                cir.setReturnValue(ActionResult.FAIL);
                if (context.getPlayer() instanceof ServerPlayerEntity serverPlayer) {
                    serverPlayer.sendMessage(Text.literal(DENY_MESSAGE), true);
                }
                return;
            }
        }
    }
}
