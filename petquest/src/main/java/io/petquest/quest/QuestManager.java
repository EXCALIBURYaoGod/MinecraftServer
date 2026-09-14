package io.petquest.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.petquest.entity.PetEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务进度管理：以「玩家 UUID → 任务 ID → 当前击杀数」的形式记录，
 * 持久化到 mod 配置目录下的 JSON 文件，服务重启后进度不丢失。
 *
 * 任务完成时发放奖励：在玩家身边生成一只随机的 AI 猫猫宠物。
 */
public final class QuestManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("petquest");
    private static final String[] CAT_NAMES = {"奶油橘猫", "黑白燕尾猫", "薄荷橘猫"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // uuid -> questId -> progress
    private static Map<UUID, Map<Integer, Integer>> progress = new LinkedHashMap<>();
    private static Path dataFile;
    private static boolean loaded = false;

    private QuestManager() {
    }

    private static void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        dataFile = FabricLoader.getInstance().getConfigDir().resolve("petquest_quests.json");
        if (!Files.exists(dataFile)) {
            return;
        }
        try {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>() {
            }.getType();
            Map<String, Map<String, Integer>> raw = GSON.fromJson(Files.readString(dataFile), type);
            if (raw != null) {
                Map<UUID, Map<Integer, Integer>> parsed = new LinkedHashMap<>();
                raw.forEach((uuid, m) -> {
                    Map<Integer, Integer> inner = new LinkedHashMap<>();
                    m.forEach((k, v) -> inner.put(Integer.parseInt(k), v));
                    parsed.put(UUID.fromString(uuid), inner);
                });
                progress = parsed;
            }
        } catch (Exception e) {
            LOGGER.warn("读取任务存档失败，将使用空进度：{}", e.getMessage());
        }
    }

    private static void save() {
        if (dataFile == null) {
            return;
        }
        try {
            Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
            progress.forEach((uuid, m) -> {
                Map<String, Integer> inner = new LinkedHashMap<>();
                m.forEach((k, v) -> inner.put(String.valueOf(k), v));
                out.put(uuid.toString(), inner);
            });
            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, GSON.toJson(out));
        } catch (IOException e) {
            LOGGER.warn("保存任务存档失败：{}", e.getMessage());
        }
    }

    public static int getProgress(UUID player, Quest quest) {
        loadIfNeeded();
        return progress.getOrDefault(player, Map.of()).getOrDefault(quest.id, 0);
    }

    /** 完成态即进度达到目标。 */
    public static boolean isComplete(UUID player, Quest quest) {
        return getProgress(player, quest) >= quest.target;
    }

    /** 玩家击杀一只敌对生物时触发。 */
    public static void onKill(ServerPlayerEntity player) {
        loadIfNeeded();
        UUID id = player.getUuid();
        Map<Integer, Integer> mine = progress.computeIfAbsent(id, k -> new LinkedHashMap<>());

        for (Quest q : QuestRegistry.QUESTS) {
            int cur = mine.getOrDefault(q.id, 0);
            if (cur >= q.target) {
                continue; // 已完成
            }
            cur++;
            mine.put(q.id, cur);
            if (cur >= q.target) {
                reward(player, q);
            }
        }
        save();
    }

    private static void reward(ServerPlayerEntity player, Quest q) {
        ServerWorld world = player.getServerWorld();
        int variant = ThreadLocalRandom.current().nextInt(CAT_NAMES.length);
        PetEntity pet = PetEntity.TYPE.create(world);
        if (pet == null) {
            return;
        }
        Vec3d pos = player.getPos();
        pet.refreshPositionAndAngles(
                pos.x + ThreadLocalRandom.current().nextDouble(-1.0, 1.0),
                pos.y + 0.5,
                pos.z + ThreadLocalRandom.current().nextDouble(-1.0, 1.0),
                player.getYaw(), 0.0f);
        pet.setOwner(player.getUuid());
        pet.setVariant(variant);
        world.spawnEntity(pet);

        player.sendMessage(Text.literal("§a✔ 任务「§r§l" + q.title + "§r§a」完成！"));
        player.sendMessage(Text.literal("§e宠物猫来到你身边：§r" + CAT_NAMES[variant]));
    }
}