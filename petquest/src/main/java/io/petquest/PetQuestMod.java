package io.petquest;

import io.petquest.command.PetQuestCommands;
import io.petquest.entity.PetEntity;
import io.petquest.quest.QuestManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端主入口：注册实体属性、监听击杀事件、注册 /petquest 命令。
 */
public class PetQuestMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("petquest");

    @Override
    public void onInitialize() {
        PetEntity.register();

        ServerLivingEntityEvents.AFTER_DEATH.register(PetQuestMod::onAfterDeath);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, selection) -> PetQuestCommands.register(dispatcher));
    }

    /** 玩家每击杀一只生物，就推进一次任务进度。 */
    private static void onAfterDeath(LivingEntity killed, DamageSource source) {
        if (killed.level().isClientSide()) {
            return;
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            QuestManager.onKill(player);
        }
    }
}