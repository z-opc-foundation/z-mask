# z-mask

> **脱敏与遮蔽引擎** —— 按敏感命中位置执行遮蔽，策略即数据。
> 边界一句话：**只做「怎么遮」，不识别、不管密钥、不决定遮不遮。**

安全线四层能力栈的执行层。同族：`z-sense`（识别底座）· `z-watermark`（水印）· `z-audit`（审计）· `z-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-mask`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.2`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.mask.core.*` |
| 依赖 | `z-sense-core`（**识别结果为输入**，反向不依赖） |
| 端口 | 8095 |
| 模块 | `z-mask-core` · `z-mask-starter` · `z-mask-web` |

## 🎯 能力清单

| 能力 | 状态 | 代码位置 |
|---|---|---|
| 脱敏策略模型（policyId / 类别 / 类型 / 下限 / 参数） | ✅ | `domain/MaskPolicy` |
| 策略即数据（10 条内置默认策略） | ✅ | `support/DefaultPolicies` |
| 可插拔策略 SPI | ✅ | `spi/MaskStrategy` |
| FULL（全遮蔽） | ✅ | `BuiltinMaskStrategies$FullMaskStrategy` |
| MASK（保留首尾 N 位） | ✅ | `BuiltinMaskStrategies$KeepEdgeMaskStrategy` |
| HASH（SHA-256 截断，可做指纹比对） | ✅ | `BuiltinMaskStrategies$HashMaskStrategy` |
| ENCRYPT（AES-256-GCM） | ✅ | `BuiltinMaskStrategies$EncryptMaskStrategy` |
| 密钥按 `keyRef` 取（**明文密钥不入策略对象**） | ✅ | `spi/SecretResolver` |
| 倒序替换 + 明细排回升序 | ✅ | `service/MaskService#mask` |
| 脱敏明细自证（起止 / 原文 / 遮蔽后 / 策略 ID） | ✅ | `service/MaskResult$MaskedItem` |
| **按已有命中脱敏**（不重扫，M2） | ✅ | `service/MaskService#mask(String, List)` / `#mask(String, SenseResult)` |
| 策略集持久化（DB） | ❌ M2 | 当前策略集在代码里 |
| 按业务场景切换策略集 | ❌ M2 | 编排层 z-security 已留 `maskPolicySetId` 字段 |

## 🔑 核心口径

### 识别一次、脱敏一次（以 z-sense 结果为输入）

```
文本 ──sense.detect──▶ 命中列表（带 [start,end)）
                          │
                          ├─▶ 策略匹配（类别 + 分级 ⇒ 策略）
                          └─▶ 按位置替换（**倒序**）
                                  ──▶ 脱敏后文本
```

**倒序替换是关键**：正序替换会让后面命中的位置被前段长度变化推偏 ⇒ 替换错位。
替换完把明细排回**位置升序**返回（审计自证用）。

### ★ 两条入口：自识别 vs 按命中脱敏（M2）

| 入口 | 场景 | 识别次数 |
|---|---|---|
| `mask(text)` | 本仓自用（单仓直接调） | 1（内部识别） |
| `mask(text, hits)` | **调用方已有识别结果** | **0（不识别）** |
| `mask(text, senseResult)` | 同上，多一道长度一致性校验 | **0（不识别）** |

**为什么要有重载**：编排层为了做**拦截判定**必须先识别；若 MASK 阶段再识别一遍，
看似「口径一致无害」，实则有两个真问题：
1. **白扫两遍** —— 识别是「全文 × 规则集」线性扫描，规则集上量后是双倍开销；
2. **★ 可能打架** —— 两次识别之间若规则集热更，拦截判定用的分级与脱敏用的分级
   可能来自**不同规则集** ⇒ 「判了不拦，却按另一套规则脱了」⇒ 口径失真。

**重载的兜底口径**（外部传入的命中列表不可信）：

| 情况 | 处理 |
|---|---|
| 命中越界（start<0 / end≤start / end>文本长） | **跳过该条**（不抛异常） |
| 命中重叠 | **与 z-sense 同算法消解**：级别高者**替换**旧命中，同级先注册者留 |
| 识别结果与文本**长度对不上** | **退回自识别**（牺牲一次识别，换「不漏遮 + 不错位」） |
| 命中列表为空 | 原样返回（该类别未配策略 ⇒ 不脱敏） |

> ⚠ **消解口径必须与 z-sense 完全一致**：若本仓与上游口径不同 ⇒ 上游已消解的命中
> 在这里被再消一次可能消出**不同结果** ⇒ 脱敏位置与识别位置对不上 ⇒ 错位遮蔽。
> 这条是「按命中脱敏」路径最容易踩的坑，已写进单测。

### 策略匹配：minLevel 贯穿每一档

```
1) 按类别匹配，取「覆盖本命中级别」且下限最严格的一条
2) 没命中 ⇒ 按 category = null 的通用策略匹配（同样遵守下限）
3) 都没有 ⇒ **不脱敏**（该类别未配策略 ⇒ 保持原样）
```

> ⚠ **下限必须贯穿每一档**：早先实现在「按类别兜底」那档忽略了 minLevel，
> 导致 minLevel=L4 的策略把 L3 的数据也脱敏了 —— 下限形同虚设。已修并有单测兜底。

### 四种内置脱敏

| 类型 | 效果 | 例（身份证 18 位） |
|---|---|---|
| `FULL` | 等长全遮蔽 | `******************` |
| `MASK` | 保留首 N + 后 M 位 | prefix4 + suffix2 ⇒ `1101************15`（12 个星号） |
| `HASH` | SHA-256 截断 16 位 | `a3f5c2d9e8b7a6c4`（**可做指纹比对**） |
| `ENCRYPT` | AES-256-GCM | 密文（需 `SecretResolver` + `keyRef` + `iv`） |

**降级口径（关键）**：
- HASH 算法不可用 ⇒ **退化全遮蔽**，**不退回原文**
- ENCRYPT 无 resolver / 无 keyRef / 无 iv / 密钥长度不对 ⇒ **全遮蔽**

> **绝不返回原文**是硬约束：脱敏失败时宁可遮死，也不能把敏感数据放出去。

### 密钥永不进策略对象

`EncryptMaskStrategy` 只通过 `SecretResolver.resolve(keyRef)` 取密钥。
策略对象里只有 `keyRef` 字符串 —— 明文密钥落在策略表里等于把密钥写进数据库。

## 🏗️ 项目结构

```
z-mask/
├── pom.xml
├── z-mask-core/
│   └── src/main/java/com/zifang/z/mask/core/
│       ├── domain/    MaskPolicy · MaskType
│       ├── spi/       MaskStrategy · SecretResolver
│       ├── service/   MaskService（门面）· BuiltinMaskStrategies · MaskResult
│       └── support/   DefaultPolicies（10 条内置策略）
├── z-mask-starter/
│   └── src/main/java/com/zifang/z/mask/starter/config/
│       └── MaskStarterAutoConfiguration   引擎装配（仅此一处）：ObjectProvider 取 SenseService
└── z-mask-web/
    └── src/main/java/com/zifang/z/mask/web/
        ├── config/    MaskAutoConfiguration（只管部署关切：数据源 + 健康检查，依赖 starter）
        │              · MaskModuleDataSource · MaskHealthIndicator
        └── api/       MaskHealthController · MaskController
```

> **引擎装配只有一处**：`MaskService` 由 starter 装配（`AutoConfiguration.imports`），
> web 依赖 starter、不重复定义；识别服务（`SenseService`）由 starter 用 `ObjectProvider` 惰性取——
> 有就用、没有就 `new` 兜底，且不依赖 bean 注册顺序。

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-mask-core/pom.xml test      # 31 例
mvn -f z-mask-starter/pom.xml test   # 2 例
mvn -f z-mask-web/pom.xml test       # 4 例
```

> **CI**：push / PR 自动跑 GitHub Actions（`.github/workflows/ci.yml`：JDK 17 + `mvn -B -ntp verify`）。

**core 31 例**（15 + 16）：四种脱敏类型、minLevel 门控、类别兜底、无策略不脱敏、
倒序替换位置正确、明细升序、幂等、null 安全；
**「按命中脱敏」16 例**：与自识别结果一致、**识别器调用次数 = 0**、
空/ null 命中列表原样返回、越界命中跳过、重叠取更高级别、同级取先注册者、
明细升序、倒序替换不错位、**长度契约破坏 ⇒ 退回自识别**、null 识别结果原样返回、
minLevel 门控同样贯穿。

**starter 2 例**（嵌入式装配，宿主模拟应用放 `com.example.embedhost` 无关包树 ⇒ 组件扫描不可能兜底）：
纯宿主只靠 `AutoConfiguration.imports` 装出引擎且**真能遮蔽手机号**（不是空壳）、
**imports 完整性逐行 `Class.forName` 纯机械校验**（正向用例抓不住包名笔误——Spring 侧有容错，这个才判得红）。

> ⚠ 测试期望的星号数是**算出来的**：18 位 − prefix4 − suffix2 = **12 个星号**。
> 写期望时别拍脑袋。
>
> ⚠ **「真不识别」必须用计数器验**：光断言「脱出来一样」会被「偷偷识别了第二次但结果碰巧相同」
> 蒙混过去 ⇒ 用计数识别器数调用次数才是唯一可靠判据。

## 文档目录

本项目文档统一收口在 `_doc/` 下（lead 002 规范八类固定编号表，桶按需存在）:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与口径：
  - [`00-overview.md`](_doc/001_arch/00-overview.md) — 总体设计（模块与依赖方向、两条入口与四条兜底、倒序替换、minLevel 贯穿、四种遮蔽与降级硬约束、密钥永不进策略对象）。
- [`_doc/002_deploy/`](_doc/002_deploy/) — 部署与运行：
  - [`00-deploy.md`](_doc/002_deploy/00-deploy.md) — 端口 8095、`Z_MASK_*` 环境变量、`z.base.db.mask.*` 键名模式、密钥给法、建表口径（本仓无 DDL）、构建验证与「起进程」当前状态。
- [`_doc/005_testing/`](_doc/005_testing/) — 验收复跑：
  - [`001_验收复跑.md`](_doc/005_testing/001_验收复跑.md) — 五仓复跑命令、实测读数、已知边界与生产执行清单。
