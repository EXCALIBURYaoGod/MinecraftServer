package io.petquest.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * 宠物渲染状态：从实体抽取到渲染管线时暂存变体，供 {@link PetEntityRenderer} 挑选贴图。
 */
public class PetEntityRenderState extends EntityRenderState {
    public int variant;
}