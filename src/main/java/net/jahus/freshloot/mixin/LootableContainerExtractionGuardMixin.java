package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.jahus.freshloot.LootRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomizableContainerBlockEntity.class)
public abstract class LootableContainerExtractionGuardMixin {

    @Inject(method = "getItem(I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardGetStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    @Inject(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStackWithAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    private void freshloot$guardExternal() {
        RandomizableContainerBlockEntity self = (RandomizableContainerBlockEntity) (Object) this;
        Level world = self.getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }
        if (!LootContainerHandler.isUnclaimedLootContainer(self)) {
            return;
        }
        if (LootRegistry.isOpen(LootRegistry.DimPos.of(world, self.getBlockPos()))) {
            return;
        }
        LootContainerHandler.reportSpoiledByExtraction(self, world, self.getBlockPos());
    }
}
