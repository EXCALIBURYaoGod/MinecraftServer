package io.petquest.client;

import io.petquest.entity.PetEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * 宠物渲染器：把宠物画成一个始终面向玩家的"立牌"，立牌贴图就是 AI 生成的猫猫图。
 */
public class PetEntityRenderer extends EntityRenderer<PetEntity> {

    private static final Identifier[] TEXTURES = {
            Identifier.of("petquest", "textures/entity/pet/cat_cream.png"),
            Identifier.of("petquest", "textures/entity/pet/cat_tuxedo.png"),
            Identifier.of("petquest", "textures/entity/pet/cat_ginger.png")
    };

    public PetEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    /** 按变体选中猫猫贴图。 */
    public static Identifier textureFor(int variant) {
        return TEXTURES[Math.floorMod(variant, TEXTURES.length)];
    }

    @Override
    public Identifier getTexture(PetEntity entity) {
        return textureFor(entity.getVariant());
    }

    @Override
    public void render(PetEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        matrices.push();
        // 抵消实体自身朝向，把立牌对准相机（billboard）
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-this.dispatcher.camera.getYaw()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(this.dispatcher.camera.getPitch()));

        float half = 0.5f;      // 半宽；猫图是正方形，画 1x1 立牌不拉伸
        float top = 1.0f;       // 顶部高度（底边贴地）
        float z = 0.0f;
        VertexConsumer vc = vertexConsumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(getTexture(entity)));
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        int overlay = OverlayTexture.DEFAULT_UV;

        quad(vc, matrix, light, overlay, -half, top, z, 0.0f, 0.0f); // 左上
        quad(vc, matrix, light, overlay,  half, top, z, 1.0f, 0.0f); // 右上
        quad(vc, matrix, light, overlay,  half, 0.0f, z, 1.0f, 1.0f); // 右下
        quad(vc, matrix, light, overlay, -half, 0.0f, z, 0.0f, 1.0f); // 左下
        matrices.pop();
    }

    private void quad(VertexConsumer vc, Matrix4f matrix, int light, int overlay,
                      float x, float y, float z, float u, float v) {
        vc.vertex(matrix, x, y, z)
                .color(1.0f, 1.0f, 1.0f, 1.0f)
                .texture(u, v)
                .overlay(overlay)
                .light(light)
                .normal(0.0f, 0.0f, 1.0f)
                .next();
    }
}