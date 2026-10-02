# 埋点实机验证清单（层 3）

| 项 | 值 |
| --- | --- |
| 用途 | 验证**真实采集埋点**是否按契约工作。**这是唯一无法在本机无头环境完成的验证** |
| 执行者 | **用户**（需要在能加载模组的 MC 实例里操作） |
| 当前状态 | **待实机确认** —— 层 1/层 2 已通过，但那**不等于**埋点可用（见下方"验证等级"） |
| 来源 | `common/src/main/java/com/octant/common/platforms/EventBridgeCore.java`、`PlatformAdapter.java` |

---

## 0. 验证等级（先读这一段，避免把三层混成一句"已验证"）

| 层 | 内容 | 等级 | 依据 |
| --- | --- | --- | --- |
| 层 1 | 埋点结构：三份平台骨架委托到 `:common` 的 `EventBridgeCore` | **结构就绪** | 源码在、零 MC 类型引用（实测 0 命中） |
| 层 2 | 埋点契约测试：事件形状、`confirmed` 语义、失败计数、同意门拒绝计数、**不绕过同意门** | **只在假事件源下验证** | `EventBridgeContractTest`（43+ 套件内） |
| 层 3 | 真实加载器事件 → 真实采集 → 真实分析 → 真实报告 | **待实机确认** | 本清单 |

> **不得**把"编译通过"或"契约测试通过"说成"埋点可用" —— 真实加载器事件能否被正确翻译，
> 以及真实玩法下事件是否按预期产生，**只有实机才能回答**。
> 另外：`:forge:mc1201:compileJava` 在无缓存冷路径上会**挂死在加载器下载**（近 1 小时未完成）；
> `:fabric:mc1201` 同样是冷路径敏感。因此"能编译"这句话本身也只在**热缓存/已就绪**时成立。

---

## 1. 前置（装什么）

| 组合 | 状态 | 说明 |
| --- | --- | --- |
| **Fabric × 1.20.1** | **建议用这一组**（热态可编译；批次 1 的四个 jar 都是 Fabric） | 需 Fabric Loader + 本模组 |
| Fabric × 1.21.1 | 可编译（hot） | 无对应辅助模组 jar（那四个是 1.20.1） |
| Forge × 1.20.1 | 冷路径会挂 | 仅用于 FTB Quests 那一个 jar |
| NeoForge × 任意 | **不适用**：尚未实现 | — |
| Forge × 1.21.1 | **不适用**：FG7 需 Gradle ≥ 9.3.0，本机为 8.9 | — |

### 1.1 辅助模组怎么装（**注意加载器错配**）

仓库根有 5 个 jar，**不能全装在同一个实例里**：

| 装入 Fabric × 1.20.1 | 不要装入同一实例 |
| --- | --- |
| JEI（`jei-1.20.1-fabric-*`） | **FTB Quests 那个是 Forge 版**（`ftb-quests-forge-*`）—— 装进 Fabric 实例会启动失败 |
| Xaero 世界地图（`xaeroworldmap-fabric-*`） | |
| Xaero 小地图（`xaerominimap-fabric-*`） | |
| KubeJS（`kubejs-fabric-*`） | |

（这四个的 loader 归属由 `fabric.mod.json` 存在实测确认；FTB 那个由 `META-INF/mods.toml` 实测确认。）

---

## 2. 正向观察点（按顺序做，每步给判定）

| # | 步骤 | 预期输出 | 判定方式 |
| --- | --- | --- | --- |
| 1 | 装好并**首次启动**（尚未同意） | `<gameDir>/config/mcinsight/privacy.json` 出现且 `collection.enabled=false`；`<world>/mcinsight/events/` **不存在或为空** | 打开 `privacy.json` 看 `enabled`；列 `events/raw/` 应为空 |
| 2 | 在游戏内**显式同意**（按 UI 提示操作） | `privacy.json` 的 `collection.enabled=true` 且至少一个 `categories.C*=true`；`<world>/mcinsight/meta/consent.json` 镜像同步 | 两个文件都应反映同意态 |
| 3 | 同意后游玩 ≥ 2 分钟并**主动做几件事**（走路、进生物群系、拿物品、合成） | `<world>/mcinsight/events/raw/<playerKey>/s####.jsonl` 出现且**行数增长** | 每行恰一个 JSON 对象；`wc -l` 增长 |
| 4 | 打开其中一行检查封套 | 必含 `schemaVersion/eventId/sessionId/playerKey/type/tRelMs/tTick/cat/src/confirmed/payload`；`tTick == round(tRelMs/50)` | 逐字段核对；`playerKey` 是 **16 位小写十六进制**（不是 UUID） |
| 5 | 检查会话账本 | `<world>/mcinsight/state/sessions.json` 出现 | 字段：`sessionId/playerKey/startDate/wallMs/activeMs/afkMs/openSession/eventCount` |
| 6 | 退出游戏（正常登出） | 该会话出现 `session_end`，`closeCause=logout` | 看该文件最后一行 |
| 7 | 检查模组本地日志 | `<gameDir>/logs/mcinsight*.log` 出现（**含路径与玩家名 ⇒ 属敏感**） | 一键清除会删它（见第 4 节） |

---

## 3. 反例步骤（**比正向更重要**，这三条是 INV-3 的实机验证）

| # | 步骤 | 预期输出 | 判定方式 | 若不符 |
| --- | --- | --- | --- | --- |
| R1 | **未同意**状态下完整游玩一轮（≥ 5 分钟，含拿物品/合成/战斗） | `events/raw/` **完全为空**；`privacy.json` 仍 `enabled=false` | 目录里**一个 `.jsonl` 都没有** | **严重缺陷**：默认不采集被破坏 |
| R2 | 同意并游玩一段 → **撤回** → 在同一次游戏内继续玩 ≥ 5 分钟 | 撤回后**不再新增任何事件行**；`revocation` 四项语义为 `true` | 记下撤回时的行数，之后行数**不得增长** | 严重缺陷：撤回未即时生效 |
| R3 | **一键清除** | `<world>/mcinsight/events/` 为空、`state/` 内账本被删、`meta/salt.bin` **被覆盖并重新生成**（内容与清除前不同）；`config/mcinsight/privacy.json` 重置、`consent-ledger.jsonl` 与 `deletion-ledger.jsonl` 被删、**`logs/mcinsight*.log` 被删** | 逐项列目录比对 | 严重缺陷：删除残留 |
| R4 | 在**未同意**状态下尝试导出 | **零文件产出**（导出被拒绝） | 导出目录为空 | 严重缺陷 |

> **R3 的对照提示**：同一个 `<gameDir>/logs/` 目录下**其他模组的日志必须幸存** ——
> 若 `othermod.log` 之类被一起删掉，那是"越界删除"，属缺陷（实现只删 `mcinsight*.log`）。

---

## 4. 需要用户回传的内容

1. 用的哪个组合（建议 Fabric × 1.20.1）、装了哪几个 jar 及版本；
2. 每步的**实际观察**（有/无、行数变化、关键字段值）；
3. 任一不符时：**贴原始文件片段**（可脱敏，但请保留结构）；
4. 游戏日志 `/logs/latest.log`（若启动失败）。

**不要回传**：完整的 `events/raw/*.jsonl`（那是原始数据，含行为序列）。
只需要**行数**与**单行样例的结构**。

---

## 5. 我在本机做不到的部分（如实登记）

| 做不到的事 | 原因 |
| --- | --- |
| 跑真实埋点 | 需要能加载模组的客户端或专用服务端；本机 `versions/` 里**没有 1.20.1**（Fabric 只到 1.16.5），客户端还需账户 |
| 验证"真实加载器事件 → 契约事件"的翻译正确性 | 同上 |
| 验证加载器事件总线/Mixin 注入点 | 同上（且我连 `Mod`/`EventBusSubscriber` 的一手 API 路径都没取到，见 `ForgeEventBridge` 类注释） |
| 枚举 JEI/Xaero/KubeJS 的**落盘形态与顶层键** | 属运行期事实；只做过 jar 内省（loader 元数据、内部布局、AW/mixin 清单） |

**这些不会被我写成"应该可以"** —— 它们留在 `UNVERIFIED`。

---

## 6. 已实现 / 未实现

- ✅ **层 1**：`EventBridgeCore`（`:common`，平台无关）+ 三份平台骨架（`fabric/1.20.1`、`fabric/1.21.1`、`forge/1.20.1`）均已改为**委托同一个核心** ⇒ 差异化被压到最小。
- ✅ **层 2**：`EventBridgeContractTest` —— 11 条断言覆盖事件形状 / `confirmed` 语义 / 失败计数 / 同意门拒绝计数 / **不绕过同意门** / 原始标识在类型层面不可表达。
- ⏳ **层 3**：本清单（等你或用户在实机执行）。
- ❌ **未做**：真实 `EventTranslator` 实现（把具体加载器事件翻成契约事件）—— 它需要真实加载器 API 与运行期观察，属层 3 的产物。
