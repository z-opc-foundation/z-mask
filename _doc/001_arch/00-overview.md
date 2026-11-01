# 总体设计

## 1. 这仓解决什么问题

拿到「哪里是敏感的」之后，需要一种**可插拔、可自证**的遮蔽手段。z-mask 只做这件事：

```
(text, hits) ──▶ 策略匹配（类别 × 分级 → MaskPolicy）──▶ 按位置倒序替换 ──▶ 脱敏后文本 + 明细
```

边界一句话：**只做「怎么遮」**——不识别（z-sense 的活）、不管密钥（`SecretResolver` 的活）、
不决定遮不遮（编排层 z-security 的活）。

## 2. 模块与依赖方向

```
z-mask/
├── z-mask-core/                    纯 Java 领域层，可脱离 Spring 单测（31 例）
│   └── src/main/java/com/zifang/z/mask/core/
│       ├── domain/    MaskPolicy · MaskType
│       ├── spi/       MaskStrategy（遮蔽算法扩展点）· SecretResolver（密钥外置）
│       ├── service/   MaskService（门面）· BuiltinMaskStrategies · MaskResult
│       └── support/   DefaultPolicies（10 条内置默认策略）
├── z-mask-starter/                 引擎装配（仅此一处，无 web/健康检查/数据源，2 例）
│   └── config/    MaskStarterAutoConfiguration（ObjectProvider 惰性取 SenseService）
└── z-mask-web/                     管控面部署：依赖 starter，只管部署关切（4 例）
    └── src/main/java/com/zifang/z/mask/web/
        ├── config/    MaskAutoConfiguration（数据源 + 健康检查）· MaskModuleDataSource · MaskHealthIndicator
        └── api/       MaskController（/z-team/api/mask/apply）· MaskHealthController（/api/mask/health）
```

依赖方向是**单向**的：`z-mask-core → z-sense-core`（识别结果作为输入），z-sense 反向不依赖本仓。
全族四层（sense / mask / watermark / audit）互不循环，编排层是唯一横向拼装点。
**引擎装配只有一处**（starter）：web 不重复定义 `MaskService`；识别服务由 starter 的
`ObjectProvider` 惰性取，有就用、没有就 `new` 兜底。

## 3. 关键口径

### 3.1 两条入口：自识别 vs 按命中脱敏

| 入口 | 场景 | 识别次数 |
|---|---|---|
| `mask(text)` | 单仓直接调用 | 1（内部识别） |
| `mask(text, hits)` | **调用方已有识别结果** | **0** |
| `mask(text, senseResult)` | 同上，多一道文本长度一致性校验 | **0** |

存在的理由不是「省一次扫描」这么简单：编排层为了做拦截判定**必须先识别一遍**，
若脱敏阶段再识别一次，两次之间规则集若热更 ⇒ 拦截判定用的分级和脱敏用的分级来自**不同规则集**
⇒ 「判了不拦，却按另一套规则脱了」⇒ 口径失真。所以 M2 的重载是**一致性**需求，不是性能需求。

### 3.2 外部传入的命中列表不可信 —— 四条兜底

| 情况 | 处理 |
|---|---|
| 越界（`start<0` / `end≤start` / `end>文本长`） | **跳过该条**，不抛异常 |
| 命中重叠 | **与 z-sense 同算法消解**：级别高者替换旧命中，同级留先注册者 |
| 结果与文本长度对不上 | **退回自识别**（牺牲一次识别换「不漏遮 + 不错位」） |
| 命中列表为空 | 原样返回（该类别未配策略 ⇒ 不脱敏） |

> ⚠ 消解口径**必须与 z-sense 完全一致**。上游已经消解过的命中，这里若按另一套规则再消一次
> 可能消出不同结果 ⇒ 脱敏位置与识别位置对不上 ⇒ 错位遮蔽。这是「按命中脱敏」路径最容易踩的坑，
> 已有单测钉住。

### 3.3 倒序替换

按位置替换必须**从后往前**：正序替换会让后面命中的偏移被前段长度变化推偏 ⇒ 替换错位。
替换完把明细按**位置升序**排回返回（`MaskResult$MaskedItem`：起止 / 原文 / 遮蔽后 / 策略 ID），供审计自证。

### 3.4 策略匹配：minLevel 贯穿每一档

```
1) 按类别匹配，取「覆盖本命中级别」且下限最严格的一条
2) 没命中 ⇒ 按 category = null 的通用策略匹配（同样遵守下限）
3) 都没有 ⇒ 不脱敏（该类别未配策略 ⇒ 保持原样）
```

> ⚠ 早先实现在「按类别兜底」那档漏了 minLevel ⇒ `minLevel=L4` 的策略把 L3 数据也脱了，下限形同虚设。
> 已修并有单测兜底。

### 3.5 四种内置遮蔽与降级硬约束

| 类型 | 效果 | 例（18 位身份证） |
|---|---|---|
| `FULL` | 等长全遮蔽 | `******************` |
| `MASK` | 保留首 N + 后 M 位 | prefix4 + suffix2 ⇒ `1101************15` |
| `HASH` | SHA-256 截断 16 位，可做指纹比对 | `a3f5c2d9e8b7a6c4` |
| `ENCRYPT` | AES-256-GCM | 密文（需 `SecretResolver` + `keyRef` + `iv`） |

**降级口径**：HASH 算法不可用 ⇒ 退化全遮蔽；ENCRYPT 缺 resolver / keyRef / iv / 密钥长度不对 ⇒ 全遮蔽。
**绝不返回原文**是硬约束——脱敏失败宁可遮死，也不能把敏感数据放出去。

### 3.6 明文密钥永不进策略对象

`EncryptMaskStrategy` 只经 `SecretResolver.resolve(keyRef)` 取密钥；`MaskPolicy` 里只有 `keyRef` 字符串。
密钥落在策略表里等于把密钥写进数据库（lead 008 凭证管理规范的同一条红线）。

## 4. 当前实现边界（M1/M2）

| 事项 | 状态 | 说明 |
|---|---|---|
| 按命中脱敏重载 | ✅ M2 | `mask(String, List)` / `mask(String, SenseResult)` |
| 策略集持久化（DB） | ❌ M2 | 10 条默认策略在 `support/DefaultPolicies` 代码里，改策略要发版 |
| 按业务场景切策略集 | ❌ M2 | 编排层已留 `maskPolicySetId` 字段 |
| 制品 | 不发 | `io.github.yuku123:z-mask:1.0.0` 仅仓内/本地消费 |

## 5. 相关文档

- 复跑与验收读数：[`_doc/005_testing/001_验收复跑.md`](_doc/005_testing/001_验收复跑.md)
- 端口 / 环境变量 / 启动方式：[`_doc/002_deploy/00-deploy.md`](_doc/002_deploy/00-deploy.md)
- 上游识别口径：z-sense [`_doc/001_arch/00-overview.md`](../../../z-sense/_doc/001_arch/00-overview.md)
- 立项口径：lead [`002_项目需求/001_特性需求/015_z-biz-security_数据安全线`](../../../z-opc-foundation-lead/002_项目需求/001_特性需求/015_z-biz-security_数据安全线/)
