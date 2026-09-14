package io.petquest.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;

/**
 * 自定义宠物实体（MC 26.2 / Mojang 官方映射）。
 *
 * 一个会跟随主人的被动实体：主人在线时若走远则自动传送回身边，近距离小步跟来。
 * 皮肤通过 {@link #VARIANT} 数据位在客户端选择对应的 AI 猫猫贴图。
 * 主人以 {@link #ownerUuid} 字段记录并写进存档，重启/卸载重载后依然跟随。
 */
public class PetEntity extends PathfinderMob {

    private static final EntityDataAccessor<Integer> VARIANT =
            SynchedEntityData.defineId(PetEntity.class, EntityDataSerializers.INT);

    private static final float TELEPORT_DISTANCE = 20.0f; // 超过此距离直接传送
    private static final float FOLLOW_RADIUS = 3.5f;       // 到达这个距离就停下来
    private static final float START_FOLLOW_DISTANCE = 10.0f;

    private static final ResourceKey<EntityType<?>> PET_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("petquest", "pet"));

    /** 宠物实体类型：以 Mojang 官方命名注册进内置实体注册表。 */
    public static final EntityType<PetEntity> TYPE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            PET_KEY,
            EntityType.Builder.of(PetEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.2f)
                    .clientTrackingRange(16)
                    .build(PET_KEY)
    );

    private UUID ownerUuid;

    public PetEntity(EntityType<? extends PetEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired(); // 不自然消失
    }

    /** 注册实体默认属性（由 Fabric 提供挂接）。 */
    public static void register() {
        FabricDefaultAttributeRegistry.register(TYPE, createPetAttributes());
    }

    public static AttributeSupplier createPetAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .build();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VARIANT, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new PetFollowOwnerGoal(this, 1.0,
                START_FOLLOW_DISTANCE, FOLLOW_RADIUS, TELEPORT_DISTANCE));
    }

    // ---- 主人 ----

    public void setOwner(UUID owner) {
        this.ownerUuid = owner;
    }

    public Optional<UUID> getOwnerUuid() {
        return Optional.ofNullable(this.ownerUuid);
    }

    /** 主人在线则返回其玩家，否则返回 null（离线时宠物留在原地）。 */
    public ServerPlayer getOwnerPlayer() {
        UUID uuid = this.ownerUuid;
        if (uuid == null || this.level().isClientSide() || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(uuid);
    }

    // ---- 变体（选用哪张猫猫贴图） ----

    public void setVariant(int variant) {
        this.entityData.set(VARIANT, Math.floorMod(variant, 3));
    }

    public int getVariant() {
        return this.entityData.get(VARIANT);
    }

    // ---- 存档（MC 26.2 的 ValueInput / ValueOutput 值存储） ----

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (this.ownerUuid != null) {
            out.putString("Owner", this.ownerUuid.toString());
        }
        out.putInt("Variant", getVariant());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        String owner = in.getStringOr("Owner", "");
        if (!owner.isEmpty()) {
            setOwner(UUID.fromString(owner));
        }
        setVariant(in.getIntOr("Variant", 0));
    }
}