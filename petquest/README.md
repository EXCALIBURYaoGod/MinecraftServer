# PetQuest — 猫宠任务系统 (Fabric Mod)

击杀敌对生物完成任务,奖励是你的 **AI 猫猫宠物**(会跟随主人的立牌宠物,贴图由 AI 生成)。

## 支持环境
- Minecraft **1.21.4** / Fabric Loader ≥ 0.16 / Fabric API
- Java 21 + Gradle(**Loom 需要 Gradle 9.x**,与 `mc-p2p/mod` 同一套工具链)

## 构建
```bash
# 在你自己的机器上(已有 gradle 9.x + JDK21)
cd petquest
/opt/gradle-9.5.0/bin/gradle build -x test   # 或直接 gradle build -x test
```
产物:`build/libs/petquest-1.0.0.jar`,放到客户端或服务端 `mods/`。

> 若默认 JDK 不是 21,在 `gradle.properties` 取消注释并指向你的 JDK21 路径。

## 功能
| 命令 | 说明 |
|------|------|
| `/petquest tasks` | 查看任务列表与进度 |
| `/petquest progress` | 查看进度条 |
| `/petquest spawnpet [0-2]` | 立刻生成一只宠物猫(0奶油/1燕尾/2橘) |

## 机制
- 任务 = 累计击杀敌对生物(5 / 20 / 50)
- 完成后自动在玩家身边生成一只**随机**猫猫宠物,并绑定为主人
- 宠物会跟随主人:走远自动传送到身边,近距离小跑跟来
- 任务进度保存在 `config/petquest_quests.json`,重启不丢
- 宠物本体随世界存档保存

## 更换/增加猫猫
把你的猫图放到:
`src/main/resources/assets/petquest/textures/entity/pet/<名字>.png`(建议正方形 ≤ 512px)
然后在 `PetEntityRenderer.TEXTURES` 与 `QuestManager.CAT_NAMES` 里按顺序补充即可。

## 宠物贴图
由 AI 生成的三只猫:奶油橘猫、黑白燕尾猫、薄荷橘猫(见 `src/main/resources/assets/petquest/textures/entity/pet/`)。