# z-mask

> **脱敏与遮蔽引擎** —— 按敏感命中位置执行遮蔽，策略即数据。
> 边界一句话：**只做「怎么遮」，不识别、不管密钥、不决定遮不遮。**

安全线四层能力栈的执行层。同族：`z-sense`（识别底座）· `z-watermark`（水印）· `z-audit`（审计）· `z-biz-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-biz-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-mask`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.0`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.mask.core.*` |
| 依赖 | `z-sense-core`（**识别结果为输入**，反向不依赖） |
| 端口 | 8095 |
| 模块 | `z-mask-core` · `z-mask-web` |

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
| 策略集持久化（DB） | ❌ M2 | 当前策略集在代码里 |
| 按业务场景切换策略集 | ❌ M2 | 编排层 z-biz-security 已留 `maskPolicySetId` 字段 |

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
└── z-mask-web/
    └── src/main/java/com/zifang/z/mask/web/
        ├── config/    MaskAutoConfiguration · MaskModuleDataSource · MaskHealthIndicator
        └── api/       MaskHealthController
```

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-mask-core/pom.xml test
```

**15 例全绿**：四种脱敏类型、minLevel 门控、类别兜底、无策略不脱敏、
倒序替换位置正确、明细升序、幂等、null 安全。

> ⚠ 测试期望的星号数是**算出来的**：18 位 − prefix4 − suffix2 = **12 个星号**。
> 写期望时别拍脑袋。
