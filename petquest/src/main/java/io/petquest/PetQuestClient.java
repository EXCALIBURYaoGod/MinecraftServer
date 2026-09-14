package io.petquest;

import io.petquest.client.PetEntityRenderer;
import io.petquest.entity.PetEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class PetQuestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 注册宠物渲染器：把 AI 猫猫图渲染成跟随玩家的立牌
        EntityRendererRegistry.register(PetEntity.TYPE, PetEntityRenderer::new);
    }
}