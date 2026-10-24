package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewingTippedArrowRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Milestone 0 gate (spec 12, 26.3): canonical potion data and the quality-resolved duration survive drinking, splash,
 * lingering clouds, tipped-arrow crafting, arrow impact and dispensers, with no double application, and untagged
 * vanilla potions behave exactly as before. Each mixin has at least one test here.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PotionPathGameTests {
    private static final int SWIFTNESS_TICKS = 3600;

    static ItemStack graded(net.minecraft.world.item.Item item, Potion potion, int quality) {
        ItemStack stack = PotionUtils.setPotion(new ItemStack(item), potion);
        BrewingMetadata.write(stack, BrewingMetadata.brewed(50, quality, quality, 0x1234L, UUID.randomUUID(), "Tester"));
        return stack;
    }

    static int expected(int base, int quality) {
        return (int) Math.round(base * (200 + quality) / 250.0);
    }

    private static Mob still(GameTestHelper h, EntityType<? extends Mob> type, double x, double y, double z) {
        Mob mob = h.spawnWithNoFreeWill(type, new Vec3(x, y, z));
        mob.setNoGravity(true);
        mob.setPos(h.absoluteVec(new Vec3(x, y, z)));
        mob.setDeltaMovement(Vec3.ZERO);
        return mob;
    }

    private static int duration(LivingEntity entity, MobEffect effect) {
        MobEffectInstance instance = entity.getEffect(effect);
        return instance == null ? -1 : instance.getDuration();
    }

    // ---- drinking ----

    @GameTest(template = "empty")
    public static void drinkingScalesFiniteDuration(GameTestHelper h) {
        for (int q : new int[] {0, 50, 100}) {
            Player player = h.makeMockPlayer();
            ItemStack stack = graded(Items.POTION, Potions.SWIFTNESS, q);
            ItemStack remainder = Items.POTION.finishUsingItem(stack, h.getLevel(), player);
            int got = duration(player, MobEffects.MOVEMENT_SPEED);
            h.assertTrue(got == expected(SWIFTNESS_TICKS, q), "quality " + q + ": expected " + expected(SWIFTNESS_TICKS, q) + " ticks, got " + got);
            h.assertTrue(remainder.is(Items.GLASS_BOTTLE), "drinking keeps the normal container remainder");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void drinkingUntaggedIsVanilla(GameTestHelper h) {
        Player player = h.makeMockPlayer();
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        Items.POTION.finishUsingItem(stack, h.getLevel(), player);
        h.assertTrue(duration(player, MobEffects.MOVEMENT_SPEED) == SWIFTNESS_TICKS, "untagged Swiftness lasts exactly 3600 ticks");
        h.assertTrue(PotionContentsService.effectiveEffects(stack).get(0).getDuration() == SWIFTNESS_TICKS, "untagged resolves to the vanilla list");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void drinkingUseTimeFollowsQuality(GameTestHelper h) {
        int[] qualities = {0, 50, 100};
        int[] ticks = {36, 32, 28};
        for (int i = 0; i < qualities.length; i++) {
            Player player = h.makeMockPlayer();
            player.setItemInHand(InteractionHand.MAIN_HAND, graded(Items.POTION, Potions.SWIFTNESS, qualities[i]));
            player.startUsingItem(InteractionHand.MAIN_HAND);
            h.assertTrue(player.getUseItemRemainingTicks() == ticks[i],
                    "quality " + qualities[i] + ": expected " + ticks[i] + " use ticks, got " + player.getUseItemRemainingTicks());
        }
        Player plain = h.makeMockPlayer();
        plain.setItemInHand(InteractionHand.MAIN_HAND, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS));
        plain.startUsingItem(InteractionHand.MAIN_HAND);
        h.assertTrue(plain.getUseItemRemainingTicks() == new ItemStack(Items.POTION).getUseDuration(), "untagged potions keep the vanilla use time");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void drinkingInstantHealthAppliesOnce(GameTestHelper h) {
        Player graded = h.makeMockPlayer();
        graded.setHealth(10);
        Items.POTION.finishUsingItem(graded(Items.POTION, Potions.HEALING, 100), h.getLevel(), graded);
        Player plain = h.makeMockPlayer();
        plain.setHealth(10);
        Items.POTION.finishUsingItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING), h.getLevel(), plain);
        h.assertTrue(graded.getHealth() == 14.0F, "Masterwork Healing heals the native 4 HP once, got " + graded.getHealth());
        h.assertTrue(plain.getHealth() == graded.getHealth(), "quality never changes an instant effect's magnitude");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bundleKeepsNativeDurations(GameTestHelper h) {
        Player player = h.makeMockPlayer();
        Items.POTION.finishUsingItem(graded(Items.POTION, Potions.TURTLE_MASTER, 100), h.getLevel(), player);
        h.assertTrue(duration(player, MobEffects.MOVEMENT_SLOWDOWN) == 400, "Turtle Master slowness keeps 400 ticks");
        h.assertTrue(duration(player, MobEffects.DAMAGE_RESISTANCE) == 400, "Turtle Master resistance keeps 400 ticks");
        h.succeed();
    }

    // ---- resolution invariants ----

    @GameTest(template = "empty")
    public static void resolutionIsIdempotent(GameTestHelper h) {
        ItemStack stack = graded(Items.POTION, Potions.LONG_SWIFTNESS, 100);
        CompoundTag before = stack.save(new CompoundTag());
        List<MobEffectInstance> first = PotionContentsService.effectiveEffects(stack);
        List<MobEffectInstance> second = PotionContentsService.effectiveEffects(stack);
        ItemStack reloaded = ItemStack.of(ItemStack.of(stack.save(new CompoundTag())).save(new CompoundTag()));
        List<MobEffectInstance> third = PotionContentsService.effectiveEffects(reloaded);
        h.assertTrue(first.get(0).getDuration() == expected(9600, 100), "Long Swiftness at 100 lasts " + expected(9600, 100));
        h.assertTrue(first.get(0).getDuration() == second.get(0).getDuration() && second.get(0).getDuration() == third.get(0).getDuration(),
                "repeated resolution and save/load never compound the multiplier");
        h.assertTrue(stack.save(new CompoundTag()).equals(before), "resolving never writes to the stack");
        h.assertTrue(Potions.LONG_SWIFTNESS.getEffects().get(0).getDuration() == 9600, "the registry potion is never mutated");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void customEffectsStayExact(GameTestHelper h) {
        ItemStack stack = graded(Items.POTION, Potions.SWIFTNESS, 100);
        PotionUtils.setCustomEffects(stack, List.of(new MobEffectInstance(MobEffects.NIGHT_VISION, 1000)));
        List<MobEffectInstance> effects = PotionContentsService.effectiveEffects(stack);
        h.assertTrue(effects.size() == 2, "registered plus custom effects");
        h.assertTrue(effects.get(0).getDuration() == expected(SWIFTNESS_TICKS, 100), "registered effect scales");
        h.assertTrue(effects.get(1).getDuration() == 1000, "foreign custom effects are never scaled");
        h.succeed();
    }

    // ---- splash ----

    private static ThrownPotion bottle(GameTestHelper h, ItemStack stack, Vec3 at) {
        ThrownPotion potion = new ThrownPotion(h.getLevel(), at.x, at.y, at.z);
        potion.setItem(stack);
        return potion;
    }

    private static void impact(ThrownPotion potion) {
        try {
            Method onHit = ThrownPotion.class.getDeclaredMethod("onHit", HitResult.class);
            onHit.setAccessible(true);
            BlockPos below = potion.blockPosition().below();
            onHit.invoke(potion, new BlockHitResult(potion.position(), Direction.UP, below, false));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not call ThrownPotion#onHit", e);
        }
    }

    @GameTest(template = "empty")
    public static void splashAttenuatesOnceByDistance(GameTestHelper h) {
        Vec3 centre = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        Mob near = still(h, EntityType.PIG, 4.5, 2, 4.5);
        Mob far = still(h, EntityType.PIG, 6.5, 2, 4.5);
        near.setPos(centre);
        far.setPos(centre.add(2, 0, 0));
        impact(bottle(h, graded(Items.SPLASH_POTION, Potions.SWIFTNESS, 100), centre));
        int full = expected(SWIFTNESS_TICKS, 100);
        int nearExpected = (int) (1.0 * full + 0.5);
        int farExpected = (int) ((1.0 - 2.0 / 4.0) * full + 0.5);
        h.assertTrue(duration(near, MobEffects.MOVEMENT_SPEED) == nearExpected, "point-blank splash: " + duration(near, MobEffects.MOVEMENT_SPEED) + " != " + nearExpected);
        h.assertTrue(duration(far, MobEffects.MOVEMENT_SPEED) == farExpected, "two blocks out: " + duration(far, MobEffects.MOVEMENT_SPEED) + " != " + farExpected);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void splashInstantDamageOnce(GameTestHelper h) {
        Vec3 centre = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        Mob graded = still(h, EntityType.PIG, 4.5, 2, 4.5);
        graded.setPos(centre);
        impact(bottle(h, graded(Items.SPLASH_POTION, Potions.HARMING, 100), centre));
        float afterGraded = graded.getHealth();
        Mob plain = still(h, EntityType.PIG, 4.5, 2, 4.5);
        plain.setPos(centre);
        graded.discard();
        impact(bottle(h, PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.HARMING), centre));
        h.assertTrue(afterGraded == plain.getHealth(), "Masterwork Harming deals the native damage once: " + afterGraded + " vs " + plain.getHealth());
        h.succeed();
    }

    // ---- lingering ----

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void lingeringCloudCarriesQualityOnce(GameTestHelper h) {
        Vec3 centre = h.absoluteVec(new Vec3(4.5, 1, 4.5));
        ThrownPotion potion = bottle(h, graded(Items.LINGERING_POTION, Potions.SWIFTNESS, 100), centre);
        impact(potion);
        List<AreaEffectCloud> clouds = h.getLevel().getEntitiesOfClass(AreaEffectCloud.class, new AABB(centre, centre).inflate(4));
        h.assertTrue(clouds.size() == 1, "one cloud, got " + clouds.size());
        AreaEffectCloud cloud = clouds.get(0);
        h.assertTrue(PotionContentsService.entityQuality(cloud).orElse(-1) == 100, "the cloud carries the bottle's quality");
        Mob pig = still(h, EntityType.PIG, 4.5, 1, 4.5);
        pig.setPos(centre);
        int quarter = expected(SWIFTNESS_TICKS, 100) / 4;
        h.succeedWhen(() -> {
            int got = duration(pig, MobEffects.MOVEMENT_SPEED);
            h.assertTrue(got > 0, "the cloud has not applied yet");
            h.assertTrue(got <= quarter && got > quarter - 40, "cloud applies a quarter of the resolved duration once: " + got + " vs " + quarter);
        });
    }

    // ---- tipped arrows ----

    @GameTest(template = "empty")
    public static void tippedArrowCraftingKeepsMetadata(GameTestHelper h) {
        TransientCraftingContainer grid = new TransientCraftingContainer(new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        }, 3, 3);
        ItemStack lingering = graded(Items.LINGERING_POTION, Potions.STRENGTH, 90);
        for (int i = 0; i < 9; i++) grid.setItem(i, i == 4 ? lingering : new ItemStack(Items.ARROW));
        Optional<CraftingRecipe> recipe = h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, h.getLevel());
        h.assertTrue(recipe.isPresent() && recipe.get() instanceof BrewingTippedArrowRecipe, "minecraft:tipped_arrow is this mod's override");
        ItemStack arrows = recipe.get().assemble(grid, h.getLevel().registryAccess());
        h.assertTrue(arrows.is(Items.TIPPED_ARROW) && arrows.getCount() == 8, "eight tipped arrows");
        h.assertTrue(PotionUtils.getPotion(arrows) == Potions.STRENGTH, "canonical potion identity kept");
        h.assertTrue(BrewingMetadata.read(arrows).map(BrewingMetadata::quality).orElse(-1) == 90, "brewing metadata kept");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void arrowImpactAppliesEighthOnce(GameTestHelper h) {
        Mob target = still(h, EntityType.PIG, 4.5, 2, 4.5);
        Arrow arrow = new Arrow(h.getLevel(), target.getX(), target.getY() + 3, target.getZ());
        arrow.setEffectsFromItem(graded(Items.TIPPED_ARROW, Potions.SWIFTNESS, 100));
        try {
            Method post = Arrow.class.getDeclaredMethod("doPostHurtEffects", LivingEntity.class);
            post.setAccessible(true);
            post.invoke(arrow, target);
            Method pickup = Arrow.class.getDeclaredMethod("getPickupItem");
            pickup.setAccessible(true);
            ItemStack picked = (ItemStack) pickup.invoke(arrow);
            h.assertTrue(BrewingMetadata.read(picked).map(BrewingMetadata::quality).orElse(-1) == 100, "a picked-up arrow keeps its metadata");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        int eighth = Math.max(expected(SWIFTNESS_TICKS, 100) / 8, 1);
        h.assertTrue(duration(target, MobEffects.MOVEMENT_SPEED) == eighth, "arrow: " + duration(target, MobEffects.MOVEMENT_SPEED) + " != " + eighth);
        Arrow plain = new Arrow(h.getLevel(), target.getX(), target.getY() + 3, target.getZ());
        plain.setEffectsFromItem(new ItemStack(Items.ARROW));
        h.assertTrue(PotionContentsService.entityQuality(plain).isEmpty(), "a plain arrow carries no metadata");
        h.succeed();
    }

    // ---- dispensers ----

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void dispenserThrowsTheSameContents(GameTestHelper h) {
        BlockPos pos = new BlockPos(4, 1, 4);
        h.setBlock(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP));
        DispenserBlockEntity dispenser = (DispenserBlockEntity) h.getBlockEntity(pos);
        ItemStack stack = graded(Items.SPLASH_POTION, Potions.SWIFTNESS, 100);
        dispenser.setItem(0, stack.copy());
        h.setBlock(pos.east(), Blocks.REDSTONE_BLOCK);
        h.succeedWhen(() -> {
            List<ThrownPotion> thrown = h.getLevel().getEntitiesOfClass(ThrownPotion.class, new AABB(h.absolutePos(pos)).inflate(6));
            h.assertTrue(!thrown.isEmpty(), "dispenser threw a bottle");
            ItemStack item = thrown.get(0).getItem();
            h.assertTrue(BrewingMetadata.read(item).map(BrewingMetadata::quality).orElse(-1) == 100, "the dispensed bottle carries the same metadata");
            h.assertTrue(PotionContentsService.effectiveEffects(item).get(0).getDuration() == expected(SWIFTNESS_TICKS, 100),
                    "and resolves to the same effective contents");
        });
    }

    private PotionPathGameTests() {}
}
