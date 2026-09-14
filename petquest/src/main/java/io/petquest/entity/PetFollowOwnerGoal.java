package io.petquest.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * 宠物跟随主人 AI：
 * - 太远（> teleportDistance）→ 直接传送到主人身边；
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
    public boolean canUse() {
        ServerPlayer owner = this.pet.getOwnerPlayer();
        if (owner == null || owner.isSpectator()) {
            return false;
        }
        return this.pet.distanceToSqr(owner) > (double) (this.stopFollowDistance * this.stopFollowDistance);
    }

    @Override
    public void start() {
        this.cooldown = 0;
    }

    @Override
    public boolean canContinueToUse() {
        ServerPlayer owner = this.pet.getOwnerPlayer();
        return owner != null &&
                this.pet.distanceToSqr(owner) > (double) (this.stopFollowDistance * this.stopFollowDistance);
    }

    @Override
    public void tick() {
        ServerPlayer owner = this.pet.getOwnerPlayer();
        if (owner == null) {
            return;
        }

        // 太远：直接传送
        if (this.pet.distanceToSqr(owner) > (double) (this.teleportDistance * this.teleportDistance)) {
            Vec3 target = owner.position().add(
                    this.pet.getRandom().nextFloat() * 2.0 - 1.0,
                    0.0,
                    this.pet.getRandom().nextFloat() * 2.0 - 1.0);
            this.pet.teleportTo(target.x, owner.getY() + 0.1, target.z);
            this.pet.getNavigation().stop();
            this.cooldown = 0;
            return;
        }

        // 中等距离：走过去
        if (--this.cooldown <= 0) {
            this.cooldown = 10;
            this.pet.getNavigation().moveTo(owner, this.speed);
        }
    }
}