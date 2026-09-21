package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.jahus.freshloot.LootRegistry;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootableContainerBlockEntity.class)
public abstract class LootableContainerExtractionGuardMixin {

    @Inject(method = "getStack(I)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardGetStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStackWithAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guardExternal();
    }

    private void freshloot$guardExternal() {
        LootableContainerBlockEntity self = (LootableContainerBlockEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isClient()) {
            return;
        }
        if (!LootContainerHandler.isUnclaimedLootContainer(self)) {
            return;
        }
        if (LootRegistry.isOpen(LootRegistry.DimPos.of(world, self.getPos()))) {
            return;
        }
        LootContainerHandler.reportSpoiledByExtraction(self, world, self.getPos());
    }
}