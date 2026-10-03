# Octant（卦限）

**本地、自愿、可撤回**的 Minecraft 玩家行为洞察模组：把一段存档里的游玩过程，变成本机生成的
**单文件 HTML 报告**，让整合包作者看到「玩家到底玩到了什么、卡在哪、怎么打的」。

> English: [README.en.md](README.en.md)

---

## 1. 它是什么

给**整合包作者**用的诊断工具。玩家在本地，把自己在某个存档里的游玩过程导出成一份报告；
报告里的每一句话都由**本地**算出来，玩家可以随时撤回授权、也可以随时删掉全部本地数据。

## 2. 隐私设计

| 机制 | 怎么做到的 |
|---|---|
| 默认不采集 | 同意门（`ConsentGate`）默认关闭；同意文件缺失/损坏一律拒绝 |
| 运行期零联网 | 构建期守卫逐文件扫源码，命中联网 API 即失败 |
| 只由玩家触发 | 导出只挂在命令与 ESC 菜单按钮上，无定时器 |
| 单文件、零外链 | 样式与字体子集**内联**，报告可离线打开、可整份转发 |
| 可随时撤回 | `/octant revoke` 立即停止采集；清除操作覆盖事件、账本与盐并给出**删除收据** |
| 标识最小化 | 玩家键 = 盐派生的假名；对手键 = **会话内序号** `<entityType>#<n>`（**禁止**用实体 UUID 或自定义名派生） |
| 未登记字段不落盘 | 脱敏管道对**字段表里没有的键**一律清空取值并记违规（fail-closed） |
| 抑制而不是编数 | 样本不足的指标写「未判定（样本不足）」并给原因码，绝不画成 0 |

本地数据落点（玩家可自行检查、可自行删除）：

```
<存档>/octant/events/raw/      已采集的原始事件（本机，永不导出）
<存档>/octant/meta/salt.bin    每存档假名盐（永不导出）
<存档>/octant/state/           会话与截断账本
<gameDir>/config/octant/       同意状态、同意履历账本
<gameDir>/octant/exports/      导出产物（报告在这里）
```

> 从旧版本（`mcinsight` 名称时期）升级时，上述目录会**一次性自动迁移**，授权状态不会丢失。
> 代码里的 `mcinsight` 字样因此不是漏改的残留：它是**遗留目录名与清理规则的判据**
> （`OctantPaths.LEGACY_NAME`、`DataEraser` 删 `mcinsight*.log`），改名会直接破坏迁移。

导出后的产物：

```
<gameDir>/octant/exports/<exportId>/
├── report.html              ← 直接双击打开就是最终报告（单文件、离线可用）
├── report.md                纯文本版
├── analysis.json            指标与结论（机器可读）
├── metrics.json / conclusions.json
├── events.anonymized.jsonl  脱敏后的事件流
├── redaction_report.json    脱敏明细
├── PRIVACY-README.txt       这份产物里有什么、没有什么
└── manifest.json            溯源与版本声明
```

## 3. 报告长什么样

正文四节 + 隐私条款 + **默认折叠**的机器附录；整份报告是**单文件、无远程引用**的 HTML。
图都是自绘的内联 SVG：

- **累积推进图**：各内容组的推进脊线 + 累积线，鼠标悬停看具体数值
- **玩家画像**：七轴雷达，四层同心网格 + 轴点悬停；**没有分值的轴不画进多边形**，
  并在图下与表格里标「未判定（样本不足）」——把"没测出来"画成 0 是本项目明令禁止的
- **卡点 / 死亡 / 经济与门槛**等条形图，每条都带"样本依据"与抑制原因

报告里的每条**小提示**都来自一个可核对的提示库（`pipeline/src/main/resources/content/tips.tsv`，
含出处与适用条件），语气尽量像"有人在旁边提一句"，而不是教你做事。

## 4. 从源码构建

模块形态：`common`（平台无关采集模型与契约）、`pipeline`（二次处理与三形态导出）、
`capture-core`（**六个组合共用的平台无关采集核心**，一行不含 `net.minecraft` 引用，由
`assertNoMinecraftRefs` 守卫）、`<加载器>/<MC 版本>`（平台适配层）。
平台模块默认不参与构建（避免解析 Minecraft 依赖拖慢纯 Java 单测），需要 `-Poctant.platforms=true` 打开。

```bash
# ① 五个组合（Gradle 8.9）
./gradlew -Poctant.platforms=true \
  :forge:mc1201:verifyModJar :fabric:mc1201:verifyModJar :fabric:mc1211:verifyModJar \
  :neoforge:mc1201:verifyModJar :neoforge:mc1211:verifyModJar

# ② Forge × 1.21.1（Gradle 9.6.1，独立开关）
tools/gradle96/gradlew -Poctant.platforms=true -Poctant.platforms.forge1211=true :forge:mc1211:verifyModJar

# ③ 纯 Java 测试（不需要 -Poctant.platforms，离线可跑）
./gradlew :common:test :capture-core:test :pipeline:test
```

`verifyModJar` 的意义：**逐条目断言 jar 里必须有**（平台元数据、图标、`content/tips.tsv`、
字体资产、平台入口类、`capture/*` 共享核心类），**并且必须没有**（PDF/图表库）。六个组合实测
产物均为 **5.7 MB 左右 / 404–411 个条目**，每个 jar 都含共享核心的 26 个类与 2 个字体子集。

## 5. 许可与第三方

- 本模组：**MIT**。
- 随 jar 附带的**报告字体**：SIL OFL-1.1，许可文本随包（`…/font/OFL-1.1-LICENSE.txt`）。
- 模组 jar **不含任何第三方运行时库**。
- Mojang 资源：**不随附任何**。
- 第三方清单与许可文本：`THIRD-PARTY-NOTICES.md`、`docs/verification/licenses/`。
