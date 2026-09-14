package io.petquest.quest;

import java.util.List;

/**
 * 任务列表。当前任务统一使用"击杀敌对生物"这一判定，
 * 便于作为一个可靠、自洽的第一版任务系统。想扩展更多任务类型，
 * 在此处追加项并同步 {@link QuestManager#onKill} 的判定逻辑即可。
 */
public final class QuestRegistry {
    public static final List<Quest> QUESTS = List.of(
            new Quest(0, "初出茅庐", "击杀 5 只敌对生物", 5),
            new Quest(1, "小小猎手", "累计击杀 20 只敌对生物", 20),
            new Quest(2, "狩猎大师", "累计击杀 50 只敌对生物", 50)
    );

    private QuestRegistry() {
    }

    public static Quest byId(int id) {
        for (Quest q : QUESTS) {
            if (q.id == id) return q;
        }
        return null;
    }
}