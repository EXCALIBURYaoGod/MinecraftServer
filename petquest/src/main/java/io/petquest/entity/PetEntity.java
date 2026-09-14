package io.petquest.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * 自定义宠物实体。
 *
 * 一个会被主人跟随的被动实体：当主人走远时自动传送到身边，近距离时小步跟过来。
 * 造型皮肤通过 {@link #VARIANT} 数据位在客户端选择对应的 AI 猫猫图片贴图。
 */
public class PetEntity extends PathAwareEntity {

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(PetEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> VARIANT =
            DataTracker.registerData(PetEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private static final float TELEPORT_DISTANCE = 20.0f; // 超过此距离直接传送
    private static final float FOLLOW_RADIUS = 3.5f;       // 到达这个距离就停下来
    private static final float START_FOLLOW_DISTANCE = 10.0f;

    public static final EntityType<PetEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of("petquest", "pet"),
            EntityType.Builder.create(PetEntity::new, SpawnGroup.CREATURE)
                    .setDimensions(0.6f, 1.2f)
                    .maxTrackingRange(16)
                    .build()
    );

    public PetEntity(EntityType<? extends PetEntity> type, World world) {
        super(type, world);
        setPersistent(); // 不自然消失
    }

    public static void register() {
        FabricDefaultAttributeRegistry.register(TYPE, PetEntity::createPetAttributes);
    }

    public static DefaultAttributeContainer createPetAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32)
                .build();
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(OWNER, Optional.empty());
        this.dataTracker.startTracking(VARIANT, 0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new PetFollowOwnerGoal(this, 1.0,
                START_FOLLOW_DISTANCE, FOLLOW_RADIUS, TELEPORT_DISTANCE));
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceToClosestPlayer) {
        return false;
    }

    // ---- 主人 ----

    public void setOwner(UUID owner) {
        this.dataTracker.set(OWNER, Optional.ofNullable(owner));
    }

    public Optional<UUID> getOwnerUuid() {
        return this.dataTracker.get(OWNER);
    }

    /** 主人在线则返回其玩家，否则 null（离线时宠物留在原地）。 */
    public ServerPlayerEntity getOwnerPlayer() {
        UUID uuid = getOwnerUuid().orElse(null);
        if (uuid == null || !this.getWorld().isClient) {
            return uuid == null ? null : ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayer(uuid);
        }
        return null;
    }

    // ---- 变体（选用哪张猫猫贴图） ----

    public void setVariant(int variant) {
        this.dataTracker.set(VARIANT, Math.floorMod(variant, 3));
    }

    public int getVariant() {
        return this.dataTracker.get(VARIANT);
    }

    // ---- 存档 ----

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        getOwnerUuid().ifPresent(uuid -> nbt.putUuid("Owner", uuid));
        nbt.putInt("Variant", getVariant());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("Owner")) {
            setOwner(nbt.getUuid("Owner"));
        }
        if (nbt.contains("Variant")) {
            setVariant(nbt.getInt("Variant"));
        }
    }
}