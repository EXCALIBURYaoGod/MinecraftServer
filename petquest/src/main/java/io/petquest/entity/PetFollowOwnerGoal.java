package io.petquest.entity;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * 宠物跟随主人 AI：
 * - 太远（> teleportDistance）→ 直接传送到主人身边 1 格；
 * - 中等距离（> stopFollowDistance）→ 主动朝主人走；
 * - 贴近（<= stopFollowDistance）→ 停下来休息。
 */
public class PetFollowOwnerGoal extends Goal {

    private final PetEntity pet;
    private final double speed;
    private final float stopFollowDistance;
    private final float startFollowDistance;
    private final float teleportDistance;
    private int cooldown;

    public PetFollowOwnerGoal(PetEntity pet, double speed,
                              float startFollowDistance, float stopFollowDistance,
                              float teleportDistance) {
        this.pet = pet;
        this.speed = speed;
        this.startFollowDistance = startFollowDistance;
        this.stopFollowDistance = stopFollowDistance;
        this.teleportDistance = teleportDistance;
    }

    @Override
    public boolean canStart() {
        ServerPlayerEntity owner = this.pet.getOwnerPlayer();
        if (owner == null) {
            return false;
        }
        if (owner.isSpectator()) {
            return false;
        }
        return this.pet.squaredDistanceTo(owner) > (double) (this.stopFollowDistance * this.stopFollowDistance);
    }

    @Override
    public void start() {
        this.cooldown = 0;
    }

    @Override
    public boolean shouldContinue() {
        return this.pet.getOwnerPlayer() != null &&
                this.pet.squaredDistanceTo(this.pet.getOwnerPlayer())
                        > (double) (this.stopFollowDistance * this.stopFollowDistance);
    }

    @Override
    public void tick() {
        ServerPlayerEntity owner = this.pet.getOwnerPlayer();
        if (owner == null) {
            return;
        }

        // 太远：直接传送
        if (this.pet.squaredDistanceTo(owner) > (double) (this.teleportDistance * this.teleportDistance)) {
            Vec3d target = owner.getPos().add(
                    this.pet.getWorld().random.nextFloat() * 2.0 - 1.0,
                    0.0,
                    this.pet.getWorld().random.nextFloat() * 2.0 - 1.0);
            this.pet.refreshPositionAndAngles(target.x, owner.getY(), target.z,
                    this.pet.getYaw(), this.pet.getPitch());
            this.pet.getNavigation().stop();
            this.cooldown = 0;
            return;
        }

        // 中等距离：走过去
        if (--this.cooldown <= 0) {
            this.cooldown = 10;
            this.pet.getNavigation().startMovingTo(owner, this.speed);
        }
    }
}