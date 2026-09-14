package io.petquest.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.petquest.entity.PetEntity;
import io.petquest.quest.Quest;
import io.petquest.quest.QuestManager;
import io.petquest.quest.QuestRegistry;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /petquest 命令组：
 * - /petquest tasks      列出所有任务与我的进度
 * - /petquest progress   查看自己每个任务当前进度
 * - /petquest spawnpet   立刻在面前生成一只宠物猫（0/1/2 指定花色）
 */
public final class PetQuestCommands {

    private PetQuestCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("petquest")
                .then(literal("tasks").executes(ctx -> listTasks(ctx.getSource())))
                .then(literal("progress").executes(ctx -> progress(ctx.getSource())))
                .then(literal("spawnpet")
                        .executes(ctx -> spawnPet(ctx.getSource(), -1))
                        .then(CommandManager.argument("variant", IntegerArgumentType.integer(0, 2))
                                .executes(ctx -> spawnPet(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "variant"))))));
    }

    private static int listTasks(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        UUID id = player.getUuid();
        source.sendFeedback(() -> Text.literal("§e—— 猫宠任务列表 ——"), false);
        for (Quest q : QuestRegistry.QUESTS) {
            int cur = QuestManager.getProgress(id, q);
            boolean done = QuestManager.isComplete(id, q);
            String state = done ? "§a✔ 已完成" : "§7" + cur + "/" + q.target;
            source.sendFeedback(() -> Text.literal("§f[" + q.id + "] §r" + q.title
                    + " — " + q.description + "  §8(§r" + state + "§8)"), false);
        }
        source.sendFeedback(() -> Text.literal("§7奖励：完成任意任务，获得一只随机的 AI 猫猫宠物"), false);
        return 1;
    }

    private static int progress(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        UUID id = player.getUuid();
        for (Quest q : QuestRegistry.QUESTS) {
            int cur = QuestManager.getProgress(id, q);
            String bar = bar(cur, q.target);
            source.sendFeedback(() -> Text.literal("§f" + q.title + " §7" + cur + "/" + q.target
                    + "  " + bar), false);
        }
        return 1;
    }

    private static int spawnPet(ServerCommandSource source, int variant) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        ServerWorld world = player.getServerWorld();
        PetEntity pet = PetEntity.TYPE.create(world);
        if (pet == null) return 0;
        Vec3d pos = player.getEyePos();
        pet.refreshPositionAndAngles(pos.x, pos.y, pos.z, player.getYaw(), 0.0f);
        pet.setOwner(player.getUuid());
        pet.setVariant(variant < 0 ? ThreadLocalRandom.current().nextInt(3) : variant);
        world.spawnEntity(pet);
        source.sendFeedback(() -> Text.literal("§e一只猫猫宠物已经来到你身边 🐱"), false);
        return 1;
    }

    private static String bar(int cur, int target) {
        int full = (int) Math.round(10.0 * cur / target);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            sb.append(i < full ? "█" : "§7░");
        }
        return sb.toString();
    }
}