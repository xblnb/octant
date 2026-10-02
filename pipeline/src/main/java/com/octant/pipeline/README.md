# pipeline — 二次数据处理管线与三形态导出

| 字段 | 值 |
| --- | --- |
| 模块 | `:pipeline`（`pipeline/`） |
| 拥有者 | 数据管线模块 |
| 权威验证命令 | `.\gradlew.bat :pipeline:test` |
| 冻结依据 | `docs/design/data-contracts.md` = `dc@1.0.0`；`docs/design/metrics-semantics.md` = `MS-1 v2.0.0`；`docs/design/hig-guidelines.md`；`docs/design/reporting-rules.md`；`docs/privacy/privacy-model.md` |

## 1. 为什么是独立模块

纯分析核心（会话化 / 特征求值 / 规则推理 / 指标 / 导出）与面向游戏的采集代码职责不同，
且必须**脱离平台层**可编译、可在无头 JVM 完整单测（`REQ-PLAT-05`、`REQ-NFR-05`）。
采集层覆盖 `common/src/`，故以模块边界切分。

零依赖：`pipeline` 的运行时/编译期依赖集合为空（`:pipeline:assertNoThirdPartyRuntimeDeps` 守卫），
JSON 读写器与 PDF 写入器均为自研，**不得**引入 Unomi / FeatureFu / URule 或任何第三方引擎。

## 2. 四层语义的原生重实现（对照实现位置）

| 外部语义 | 原生实现 | 说明 |
| --- | --- | --- |
| **Unomi**（会话化 / 事件上下文 / 属性聚合） | `raw.EventStream`、`raw.Sessionizer`、`raw.RawEventSchema` | 挂机间隔切分（`IDLE_GAP_THRESHOLD`）、归档内唯一性去重、`relative_day`、活跃时长 |
| **Unomi**（画像段与评分） | `analysis.Segment`、`analysis.AnalysisReport#buildSegments` | M9 七维 + 段门槛（析取）+ 段命名 |
| **FeatureFu**（可组合特征表达式） | `feature.Combinators`、`feature.Features`、`feature.FeatureValue` | §3.1.1 的**全部**组合子 + 每个特征的 `expr` 文本形态 |
| **URule**（规则集 / 条件动作 / 优先级 / 冲突消解 / 命中轨迹） | `rule.RuleSet`、`rule.Rule`、`rule.RuleCatalog` | 优先级降序 + 同优先级按 ID；同组内首条命中生效，其余留痕为 `shadowed` |

## 3. 目录与契约章节对应

| 包 | 契约章节 |
| --- | --- |
| `com.octant.pipeline.raw` | §2 原始事件 schema（23 型 + 11 字段封套 + payload 校验） |
| `com.octant.pipeline.feature` | §3 特征契约（组合子、窗口、阈值、§3.3 的 42 个指标键） |
| `com.octant.pipeline.rule` | §4.5/§4.6 规则集与命中轨迹 |
| `com.octant.pipeline.analysis` | §4 输出模型、§5 抑制表达（`ReasonCodes` = 26 项闭集） |
| `com.octant.pipeline.json` | §7.2 的"自研最小 JSON 读写器"（零依赖） |
| `com.octant.pipeline.report` | §6.4 章节映射、HIG 章节结构与图表形式 |
| `com.octant.pipeline.export` | §6 导出物结构、脱敏矩阵、三形态载体 |

## 4. 三条硬约束的可判定守卫

| 约束 | 守卫 | 强制它的命令 |
| --- | --- | --- |
| 零 `net.minecraft` 引用 | `:pipeline:assertNoMinecraftRefs`（主源码 + 测试源码） | `:pipeline:test`、`:pipeline:check`、`:common:build -x test` |
| 零联网 | `:pipeline:assertNoNetworkApis`（主源码禁 URL/套接字；测试源码默认同样禁 URL，仅**具名豁免清单**可放行 —— 当前 1 条：`PrivacyRedactionTest.java` 的 `https://example.invalid/`，RFC 2606 保留域探针，不会被访问） | `:pipeline:test`、`:pipeline:check` |
| 零第三方运行时依赖 | `:pipeline:assertNoThirdPartyRuntimeDeps`（运行时依赖集合必须为空） | `:pipeline:check` |

**守卫一律不得挂 `compileJava`/`compileTestJava`**（实测事故）：平台适配层依赖本模块，
把校验挂编译会把一次文本误报升级成「三加载器 × 两版本全部不可构建」，并牵连
`:common:build -x test`。全仓库约定见 `docs/design/platform-matrix.md` §8.1。

## 4.1 隐私规范版本与 fail-closed 同意门禁（与规范侧的接线）

**版本不写死**：`ExportVersions.privacySpecVersion()` 按「构建期注入（`octant.privacy.specVersion`，
由 `pipeline/build.gradle` 从 `docs/privacy/SPEC-VERSION.txt` 读取）→ 运行时读文件
（`octant.privacy.specVersionFile`）→ **未验证**」解析。规范升版 ⇒ 构建期自动跟随，
不再需要人记得改一个 Java 字符串（写死会在升版时静默漂移，只有 `check_export.py` EX-11 能发现）。
单测断言 `manifest.privacySpecVersion` **等于** `SPEC-VERSION.txt` 当前值。

**兜底语义：未知必须显式可见，不得伪装成具体版本**（已约定）。两个来源都不可用时：
- `manifest.privacySpecVersion` 写 `privacy-spec@UNVERIFIED`，**不**回退到任何具体版本号；
- `manifest.versionSource` 写 `fallback`（机器可读诊断位）；
- 构建**不**因此硬失败 —— 避免把"文件没接上"升级成"整个模块不可构建"（与 §4 的教训同形）；
- EX-11 仍会 FAIL，但诊断语从「产物声明了错的版本」变成「版本来源未接线」，直接指向真因。

原理与"禁止用 null/0 伪装缺值"同源：**一个被明确标记的未知，比一个看起来合理的旧值更安全。**

**为什么不用 `-A`（注解处理器参数）注入版本**（留档以免后人重走）：

| 理由 | 说明 |
| --- | --- |
| 复杂度不成比例 | `-A` 要被 Java 代码读到，必须再配一个注解处理器生成常量类 —— 为读一个版本号引入注解处理，等于把"字符串注入"升级成"一整套构建插件" |
| Gradle 之外直接失效 | 直接 `java -cp …`、IDE 单测、将来的平台层都拿不到；而三层解析链在**所有**这些形态下都成立且每层可观测 |
| 不影响判定契约 | `check_export.py` 的 EX-11 只看 manifest 声明的值，注入机制属实现侧选择 |

取舍标准：**选"在更多运行形态下都正确"的机制，而不是"更符合对方设想"的机制。**

**`versionSource` 与 `UNVERIFIED` 配套**（两件都要有）：`UNVERIFIED` 解决"兜底时不该声明具体版本号"，`versionSource: injected|file|fallback` 解决"即使值正确也要能看出它来自哪里"。两者都已输出到 `manifest.json`。

**fail-closed 同意门禁**（`privacy-model` §5.1.1 的产物侧落点）：`ExportPipeline.write(..., ConsentSource)`
在**最前**执行 —— 同意状态的读取 / 解析 / 校验任一环节失败，或同意有效但不允许导出
（未确认 / 采集关闭 / 无类别 / 导出未开启）⇒ **拒绝导出且根本不创建目录**（比 T1 门禁更早拦下）。
`ConsentSource` 由平台/采集层实现（读 `privacy.json`）；本模块自带
`FileConsentSource`（真读盘 + 真解析 + 真校验 schemaVersion），因此故障场景可用**真实入口**构造，
而不是用测试替身冒充。管线只消费该接口，因此"不知道玩家同意了什么"与"玩家没同意"在效果上同处置；
不传来源的重载是**兼容路径**。故障场景目录按 `docs/privacy/contract-tests/README-failclosed.md`
落到 `<导出根>/negative/<场景名>/`，由 `check_export.py` EX-16 判"目录内零文件"；
当前覆盖 9 个场景（缺失 / 损坏 / schema 不识别 / 采集关闭 / 未确认 / 无类别 / 导出未开启 / 结构不全），
并配 1 个**对照组**证明同意有效时正常产出 9 个文件 —— 否则"永远拒绝一切导出"的实现也会通过（EX-16 现已双向断言）。

## 5. 与 `:common` 的接线（**当前未启用**）

`pipeline/build.gradle` 中的 `implementation project(':common')` 目前**被注释掉**，原因有二，
均可判定：

1. `:common:compileTestJava` 当前失败 —— `common/src/test/.../RedactionPipelineTest.java`
   引用了 `Transforms` 上尚未写入的方法（`languageMainTagSafe`、`reasonDetailSafe`、`isAsciiDetail`）。
   一旦 `.\gradlew.bat :common:test` 通过，取消注释即可接线，不会引入任何其它改动。
2. 在接线之前，`pipeline` 需要一层**适配器**把采集层类型翻译到管线内部模型，避免两套模型
   各自成为事实来源。映射表如下（逐字段、无猜测；采集层类型见
   `common/src/main/java/com/octant/common/model/CaptureEvent.java`）：

| `common` 采集层 | `pipeline` 管线内部 | 变换 |
| --- | --- | --- |
| `CaptureEvent.schemaVersion` | （由 `RawEventSchema.SCHEMA_VERSION` 常量校验） | 断言相等，不落库 |
| `CaptureEvent.eventId` / `sessionId` | `RawEvent.eventId()` / `sessionId()` | 原样（形态已由采集层校验） |
| `CaptureEvent.playerKey` | `RawEvent.playerKey()` | 原样；**导出层必须丢弃**（`RedactionMatrix` 的 `PSEUDONYMIZE` 档） |
| `CaptureEvent.type`（`CaptureEventType`） | `EventType`（按 `wireName`） | 按**线名**映射（`CaptureEventType.eventId()`），不按枚举序 |
| `CaptureEvent.tRelMs` / `tTick` | 同名 | 原样（`tTick == round(tRelMs/50)` 两侧一致） |
| `CaptureEvent.category` | `EventType.category()` | 断言一致 |
| `CaptureEvent.source` | `Features.Context.source()` | 原样（`forge`/`neoforge`/`fabric`） |
| `CaptureEvent.confirmed` | `RawEvent.confirmed()` | 原样 |
| `CaptureEvent.durMs`（`Long`，可空） | `RawEvent.durMs()`（`long`，非区间型为 0） | `null` → `0L`；非区间型携带非 0 值应判定 schema 违反 |
| `CaptureEvent.payload` | `RawEvent.payload()` | 原样（两侧字段名相同；键集合必须与 §2.4 一致） |
| `ContentCatalog`（采集层） | `raw.ContentCatalog` | 逐轴 `register(kind, ids, approximate)` |
| `privacy.SaltProvider` / `Transforms` | `export.PrivacyRedactor` | 假名化与 T1 门禁**复用采集层实现**，管线侧只消费 |

**接线完成后的验证命令**：`.\gradlew.bat :pipeline:test`（本模块测试）+ `.\gradlew.bat :common:test`
（采集层测试）。两者都必须为 exit 0。

## 6. 端到端取证与产物

`EndToEndExportTest` 会在 `pipeline/build/e2e-out/<exportId>/` 真实落盘 9 个文件，
并把路径与字节数打印到标准输出（`gradlew :pipeline:test --rerun-tasks -i` 可见）：

```
manifest.json  report.md  report.pdf  analysis.json  metrics.json
conclusions.json  events.anonymized.jsonl  redaction_report.json  PRIVACY-README.txt
```

PDF 为**自研写入器**产物（非嵌入 CJK 字体 + Identity-H + ToUnicode），
实测：`%PDF-1.4` 头、29 页、pdfminer 可提取 43k 字符（含 5.2k 汉字）与全部 6 个章节标题。

## 7. 已知限制（诚实登记）

1. `internal` 事件模型与采集层类型在接线前是**并列**存在（pipeline/src/main/java/com/octant/pipeline/README.md 的映射表即为接线依据）；
2. `ContentCatalog` 的整合包类别表属占位项 P-2（需作者提供实例）；
3. 规则集（`RuleCatalog`）是**最小可用集**：覆盖 9 族指标的抑制门禁、结论、建议与画像段命名，
   未实现 MS-1 的全部次级规则（如 M3e 的流失阈值分档、M8 的战术细分判定）；
4. M8d 的"力量比"使用 `combat_started.opponentThreat` 作为代理量（`proxyQuality = 0.60`），
   因此 M8c–M8f 的置信度等级最高只能到 `heuristic`；
5. 图表在 Markdown 载体中以等价表格呈现（HIG-REP-16 允许），PDF 载体的图表按同一行序列渲染为
   矢量文本层，尚未实现坐标轴/网格线等图形装饰。
