package io.petquest;

import io.petquest.client.PetEntityRenderer;
import io.petquest.entity.PetEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * 客户端入口：给宠物实体绑定渲染器（把 AI 猫猫立牌画出来）。
 */
public class PetQuestClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(PetEntity.TYPE, PetEntityRenderer::new);
    }
}