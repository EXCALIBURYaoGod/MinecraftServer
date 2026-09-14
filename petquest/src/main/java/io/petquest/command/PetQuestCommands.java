package io.petquest.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.petquest.entity.PetEntity;
import io.petquest.quest.Quest;
import io.petquest.quest.QuestManager;
import io.petquest.quest.QuestRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.commands.Commands.literal;

/**
 * /petquest 命令组：
 * - /petquest tasks      列出所有任务与我的进度
 * - /petquest progress   查看自己每个任务当前进度
 * - /petquest spawnpet [variant]   立即在面前生成一只宠物猫（0/1/2 指定花色）
 */
public final class PetQuestCommands {

    private PetQuestCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("petquest")
                .then(literal("tasks").executes(ctx -> listTasks(ctx.getSource())))
                .then(literal("progress").executes(ctx -> progress(ctx.getSource())))
                .then(literal("spawnpet")
                        .executes(ctx -> spawnPet(ctx.getSource(), -1))
                        .then(Commands.argument("variant", IntegerArgumentType.integer(0, 2))
                                .executes(ctx -> spawnPet(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "variant"))))));
    }

    private static int listTasks(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c该命令仅玩家可用"));
            return 0;
        }
        source.sendSystemMessage(Component.literal("§e======== 宠物任务任务书 ========"));
        for (Quest q : QuestRegistry.QUESTS) {
            int cur = QuestManager.getProgress(player.getUUID(), q);
            boolean done = QuestManager.isComplete(player.getUUID(), q);
            String status = done ? "§a✔ 已完成" : "§7(" + cur + "/" + q.target + ")";
            source.sendSystemMessage(Component.literal("§r" + status + "  §f" + q.title));
            source.sendSystemMessage(Component.literal("§7      " + q.description));
        }
        source.sendSystemMessage(Component.literal("§e================================"));
        return 1;
    }

    private static int progress(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c该命令仅玩家可用"));
            return 0;
        }
        source.sendSystemMessage(Component.literal("§e我的任务进度："));
        for (Quest q : QuestRegistry.QUESTS) {
            int cur = QuestManager.getProgress(player.getUUID(), q);
            boolean done = QuestManager.isComplete(player.getUUID(), q);
            String line = done ? "§a✔ " + q.title : "§7" + q.title + " (" + cur + "/" + q.target + ")";
            source.sendSystemMessage(Component.literal(line));
        }
        return 1;
    }

    private static int spawnPet(CommandSourceStack source, int variant) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c该命令仅玩家可用"));
            return 0;
        }
        ServerLevel world = source.getLevel();
        PetEntity pet = PetEntity.TYPE.create(world, EntitySpawnReason.COMMAND);
        if (pet == null) {
            source.sendFailure(Component.literal("§c生成宠物失败"));
            return 0;
        }
        Vec3 pos = player.position();
        pet.teleportTo(pos.x, pos.y + 0.5, pos.z);
        pet.setOwner(player.getUUID());
        pet.setVariant(variant < 0 ? ThreadLocalRandom.current().nextInt(3) : variant);
        world.addFreshEntity(pet);
        source.sendSuccess(() -> Component.literal("§a宠物猫来啦！"), true);
        return 1;
    }
}