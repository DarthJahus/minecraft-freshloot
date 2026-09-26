package net.jahus.freshloot.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class FreshLootChestPlacementGuardMixin {

    private static final String DENY_MESSAGE = "\u00a7cYou can't place a chest next to a loot chest!";

    @Shadow
    @Nullable
    protected abstract BlockState getPlacementState(BlockPlaceContext context);

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true)
    private void freshloot$denyChestNextToLootChest(BlockPlaceContext context,
                                                    CallbackInfoReturnable<InteractionResult> cir) {
        Level world = context.getLevel();
        if (world.isClientSide()) {
            return;
        }

        BlockItem self = (BlockItem) (Object) this;
        if (!(self.getBlock() instanceof ChestBlock)) {
            return;
        }

        BlockPlaceContext resolved = self.updatePlacementContext(context);
        if (resolved == null) {
            return;
        }

        BlockState state = this.getPlacementState(resolved);
        if (state == null
                || !state.hasProperty(ChestBlock.TYPE)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return;
        }

        BlockPos placedPos = resolved.getClickedPos();
        Direction facing = state.getValue(ChestBlock.FACING);

        if (freshloot$isLootPartner(world, placedPos.relative(facing.getClockWise()), state)
                || freshloot$isLootPartner(world, placedPos.relative(facing.getCounterClockWise()), state)) {
            cir.setReturnValue(InteractionResult.FAIL);
            Player player = context.getPlayer();
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal(DENY_MESSAGE));
            }
        }
    }

    private static boolean freshloot$isLootPartner(Level world, BlockPos neighborPos, BlockState newState) {
        BlockState neighbor = world.getBlockState(neighborPos);
        if (neighbor.getBlock() != newState.getBlock()) {
            return false;
        }
        if (neighbor.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return false;
        }
        if (neighbor.getValue(ChestBlock.FACING) != newState.getValue(ChestBlock.FACING)) {
            return false;
        }
        BlockEntity be = world.getBlockEntity(neighborPos);
        return be instanceof ChestBlockEntity chest && chest.getLootTable() != null;
    }
}
