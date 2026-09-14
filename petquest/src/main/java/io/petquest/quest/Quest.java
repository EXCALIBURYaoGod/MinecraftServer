package io.petquest.quest;

/**
 * 单个任务的定义。
 */
public class Quest {
    public final int id;
    public final String title;
    public final String description;
    public final int target; // 需要完成的击杀数

    public Quest(int id, String title, String description, int target) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.target = target;
    }
}