package io.petquest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.petquest.entity.PetEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * 宠物渲染器（MC 26.2 延迟渲染 API）：
 * 把宠物画成一个始终面向玩家的"立牌"，立牌贴图就是 AI 生成的猫猫图。
 *
 * 关键做法：在 {@link #submit} 里把 PoseStack 乘上相机朝向（billboard），
 * 再用 {@link SubmitNodeCollector#submitCustomGeometry} 画一个带贴图的四边形。
 */
public class PetEntityRenderer extends EntityRenderer<PetEntity, PetEntityRenderState> {

    private static final Identifier[] TEXTURES = {
            Identifier.fromNamespaceAndPath("petquest", "textures/entity/pet/cat_cream.png"),
            Identifier.fromNamespaceAndPath("petquest", "textures/entity/pet/cat_tuxedo.png"),
            Identifier.fromNamespaceAndPath("petquest", "textures/entity/pet/cat_ginger.png")
    };

    /** 全亮：blockLight=15 / skyLight=15，让 AI 猫图始终清楚可见。 */
    private static final int FULL_BRIGHT = 0xF000F;

    public static Identifier textureFor(int variant) {
        return TEXTURES[Math.floorMod(variant, TEXTURES.length)];
    }

    public PetEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f; // 立牌不需要投影
    }

    @Override
    public PetEntityRenderState createRenderState() {
        return new PetEntityRenderState();
    }

    @Override
    public void extractRenderState(PetEntity entity, PetEntityRenderState state, float tickDelta) {
        super.extractRenderState(entity, state, tickDelta);
        state.variant = entity.getVariant();
    }

    @Override
    public void submit(PetEntityRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        // 立牌旋转：把本地 XY 平面转到面向相机（经典 billboard）。
        poseStack.mulPose(camera.orientation);
        RenderType type = RenderTypes.entityCutout(textureFor(state.variant));
        collector.submitCustomGeometry(poseStack, type, PetEntityRenderer::drawBillboard);
        poseStack.popPose();

        super.submit(state, poseStack, collector, camera);
    }

    private static void drawBillboard(PoseStack.Pose pose, VertexConsumer vc) {
        float w = 0.5f;  // 半宽
        float h = 1.0f;  // 立牌高 1 格
        vertex(vc, pose, -w, h, 0.0f, 0.0f, 0.0f); // 左上
        vertex(vc, pose,  w, h, 0.0f, 1.0f, 0.0f); // 右上
        vertex(vc, pose,  w, 0.0f, 0.0f, 1.0f, 1.0f); // 右下
        vertex(vc, pose, -w, 0.0f, 0.0f, 0.0f, 1.0f); // 左下
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v) {
        vc.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(0.0f, 0.0f, 1.0f);
    }
}