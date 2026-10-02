# `tools/gradle96/` —— 第二套 Gradle wrapper（9.6.1）

## 为什么仓库里有两套 wrapper

用户裁定（2026-10-02）原话：**「不要卡在同一个 gradle 了，用不同版本的 gradle 来跑不同的版本」**。

这条裁定的前提是**实测出来的硬事实**：本仓 6 个组合要用的构建插件，对 Gradle 版本的要求**互斥**。

| 插件（当前所用版本） | Gradle 8.9 | Gradle 9.6.1 |
| --- | --- | --- |
| ForgeGradle 6（`forge/1.20.1`） | ✅ **已实测跑通**（`verifyModJar` 绿） | ❌ 硬编码拒绝：`Found Gradle version Gradle 9.6.1. Versions Gradle 9.0 and newer are not supported yet.` |
| ForgeGradle 7（`forge/1.21.1`） | ❌ 要求 ≥9.3：`ForgeGradle 7 requires Gradle 9.3.0 or later` | ⚠️ 能配置（DSL 已迁移），但 **mavenizer 会卡住**（CPU 零增长、无下载） |
| Fabric Loom 1.7.4（`fabric/1.20.1`、`fabric/1.21.1`） | ✅ **已实测跑通** | ❌ apply 失败：`Could not create an instance of type net.fabricmc.loom.extension.LoomGradleExtensionImpl` |
| ModDevGradle 2.0.91 / 2.0.147（`neoforge/1.20.1`、`neoforge/1.21.1`） | 待实测 | 待实测 |

⇒ **不存在「一套 Gradle 服务全部组合」的可能**。曾经尝试过（把根 wrapper 升到 9.6.1，即用户最初的
选择），实测结果是**同时弄坏两个已经跑通的组合**，换来一个卡死的 mavenizer —— 净损失，不是更干净。
因此改为分工：

| wrapper | 版本 | 负责的组合 |
| --- | --- | --- |
| 仓库根 `gradlew.bat` | **8.9** | `forge/1.20.1`、`fabric/1.20.1`、`fabric/1.21.1`、`:common`、`:pipeline`、`:capture-core` |
| `tools/gradle96/gradlew.bat` | **9.6.1** | `forge/1.21.1`，以及任何要求 Gradle ≥ 9.3 的组合 |

## 用法

在**仓库根目录**执行（工作目录即工程目录，不必给 `-p`）：

```bat
tools\gradle96\gradlew.bat -Poctant.platforms=true -Poctant.platforms.forge1211=true :forge:mc1211:compileJava
```

要沿用那把**并发锁**（推荐，见下），设 `OCTANT_WRAPPER` 即可：

```bat
set OCTANT_WRAPPER=%CD%\tools\gradle96\gradlew.bat
gradlew-locked.bat -Poctant.platforms=true -Poctant.platforms.forge1211=true :forge:mc1211:compileJava
```

## 并发纪律（**两套 wrapper 共用同一把锁**）

两套 wrapper 指向的是**同一个工程**：同一份 `settings.gradle`、同一个 `build/`、同一份
`:capture-core` 产物。并行跑会互相读到**半写完的类**——这正是这把锁当初被引入的两个真实事故
（`cannot find symbol CaptureEventType`、`Unable to delete test-results/binary/output.bin`）。
**换一套 Gradle 不会让这两个事故消失**，所以 `gradlew-locked.bat` 的调用行已支持
`OCTANT_WRAPPER`，并且把用的是哪一套**写进锁记录**（`holder.txt` 的 `wrapper=` 行），
事后可判定"谁在跑什么"。

## 组成

| 文件 | 来源 |
| --- | --- |
| `gradlew` / `gradlew.bat` | **从仓库根原样复制**：它们与 Gradle 版本无关，会按自身所在目录找 `gradle/wrapper/` |
| `gradle/wrapper/gradle-wrapper.jar` | 同上（同样与版本无关，升级用的就是它自己） |
| `gradle/wrapper/gradle-wrapper.properties` | 指向 9.6.1，**摘要现算**（`9c0f7fae…`），不抄 |

## 备注：`distributionUrl` 指向官方分发

两套 wrapper 的 `distributionUrl` 都指向官方分发源
（`https://services.gradle.org/distributions/gradle-*-bin.zip`），
`distributionSha256Sum` 现算并与官方摘要一致（9.6.1 = `9c0f7fae…`）。

若改用内网镜像或离线分发包，**两处都要同时改**：`distributionUrl` 换地址、
`distributionSha256Sum` 跟着换成新包的摘要，否则 wrapper 会因取不到分发或摘要校验
失败而拒绝启动。
