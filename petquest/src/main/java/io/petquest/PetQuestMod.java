package io.petquest;

import io.petquest.command.PetQuestCommands;
import io.petquest.entity.PetEntity;
import io.petquest.quest.QuestManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.living.v1.ServerLivingEntityEvents;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PetQuestMod implements ModInitializer {
    public static final String MOD_ID = "petquest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("PetQuest 加载完成：猫宠任务系统已启用");

        // 注册宠物实体与属性
        PetEntity.register();

        // 注册命令
        CommandRegistrationCallback.EVENT.register(PetQuestCommands::register);

        // 击杀敌对生物 → 推进任务进度
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Monster && source.getAttacker() instanceof ServerPlayerEntity player) {
                QuestManager.onKill(player);
            }
        });
    }
}