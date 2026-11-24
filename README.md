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

---

<!-- z-opc-foundation-sibling-registry:v1 -->
## 🔗 兄弟仓全景（z-opc-foundation 组织）

本仓隶属于 [z-opc-foundation](https://github.com/orgs/z-opc-foundation/repositories) 组织——一人公司基座的全部自研件（现存 61 仓，含私有仓；口径 2026-11-24 GitHub org API 实读）。

| 仓 | 一句话说明 |
|---|---|
| [z-agent](https://github.com/z-opc-foundation/z-agent) | 云端 Agent 平台的库与 starter——ReAct/Plan 实现 + 注册中心 + REST 网关 + 只读控制面 |
| [z-agent-kernel](https://github.com/z-opc-foundation/z-agent-kernel) | Agent 框架基座——消息/工具/记忆/provider/会话 SPI + 轻量默认实现（纯 POJO 无 Spring） |
| [z-asset](https://github.com/z-opc-foundation/z-asset) | 企业资产台账——实物设备全生命周期 + 域名/证书/许可到期提醒 |
| [z-audit](https://github.com/z-opc-foundation/z-audit) | 审计与合规留痕——事件模型 + 哈希链 + 事件出口 SPI + 留存期策略 |
| [z-biz](https://github.com/z-opc-foundation/z-biz) | 商务外壳域——客户/合同/票据/对账四聚合 |
| [z-biz-erp](https://github.com/z-opc-foundation/z-biz-erp) | 进销存 ERP——商品/客商/采购/销售/库存流水/应收应付核销（z-biz 业务线） |
| [z-biz-security](https://github.com/z-opc-foundation/z-biz-security) | 业务数据安全防护入口——场景化策略注册 + 安全线四段流水线对外编排 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | Spring Boot Starter 聚合仓 + 版本权威（火车制）——唯一消费入口 z-boot-parent |
| [z-bot](https://github.com/z-opc-foundation/z-bot) | 本地 agent 应用——终端 CLI + HTTP/SSE web 控制台 + 多通道 gateway（飞书/钉钉/webhook） |
| [z-cache](https://github.com/z-opc-foundation/z-cache) | RESP2 协议兼容的分布式内存缓存——Java 8 + Netty，Redis 客户端零修改直连 |
| [z-camuda](https://github.com/z-opc-foundation/z-camuda) | 【已退役·归档】基于 Camunda 的工作流封装层（原 z-wf 改名，与现行 z-wf 无共同历史） |
| [z-cognition](https://github.com/z-opc-foundation/z-cognition) | 业务认知域/认知舱——术语/概念/证据链 + memory/kb/ontology 渐进式模块 |
| [z-config](https://github.com/z-opc-foundation/z-config) | 分布式配置中心 + 服务注册中心（类 Nacos）——Netty 长连接推送 + React 控制台 |
| [z-ctc](https://github.com/z-opc-foundation/z-ctc) | 4A 中心——认证/授权/账户/审计，多租户 JWT + SSO 跨子域 |
| [z-dms](https://github.com/z-opc-foundation/z-dms) | 数据源管控底座——统一登记、统一建池、统一探活、变更统一推送 |
| [z-env](https://github.com/z-opc-foundation/z-env) | 本地中间件与基础设施环境——31 个 docker-compose 场景 + make 一键运维 |
| [z-etl](https://github.com/z-opc-foundation/z-etl) | ETL 引擎——DataX 式线性作业 + Flink 式 DAG 流管道（键控状态/水位/窗口） |
| [z-ext](https://github.com/z-opc-foundation/z-ext) | Java 扩展点框架——@ExtPoint/@ExtImpl 运行期选实现，五档侵入方式 |
| [z-frontend-common](https://github.com/z-opc-foundation/z-frontend-common) | 全仓共享前端基座——AppLayout/LoginPage 等前端规范的唯一承载 |
| [z-frontend-markdown](https://github.com/z-opc-foundation/z-frontend-markdown) | Markdown 通用共享组件包（npm）——MarkdownLiveEditor/NoteMarkdown/MermaidBlock 单一真源 |
| [z-frontend-render](https://github.com/z-opc-foundation/z-frontend-render) | 平台共享前端渲染引擎——后端 schema 是唯一事实源，报表与低代码共用 |
| [z-graph](https://github.com/z-opc-foundation/z-graph) | 独立图数据库——MVCC 版本链存储 + OpenCypher 子集 + Bolt 风格协议 + React 控制台 |
| [z-gw](https://github.com/z-opc-foundation/z-gw) | 自研高性能 API 网关——Netty 手写运行时，Route/Predicate/Filter 模型可嵌 Spring Boot |
| [z-indexer](https://github.com/z-opc-foundation/z-indexer) | 代码索引服务——仓库注册表 + 定时重建索引 + 符号/调用链查询 |
| [z-introduce](https://github.com/z-opc-foundation/z-introduce) | 组织仓库展示站——全仓实测元数据汇总成可搜索静态站点 |
| [z-lc](https://github.com/z-opc-foundation/z-lc) | 低代码平台——DB 驱动通用 CRUD 引擎 + 报表/模型设计框架 |
| [z-llm-agent](https://github.com/z-opc-foundation/z-llm-agent) | CLI Agent 服务化——把本机 CLI agent（CC/Hermes/Qoder 等）暴露成 OpenAI 兼容对话 API |
| [z-llm-gateway](https://github.com/z-opc-foundation/z-llm-gateway) | LLM 网关——OpenAI/Anthropic 双协议面 + 多 vendor 凭据池、限流与 token 记账 |
| [z-llm-msghub](https://github.com/z-opc-foundation/z-llm-msghub) | msgHub 中心消息路由——WebSocket 中心枢纽 + Agent 侧客户端 SDK |
| [z-mask](https://github.com/z-opc-foundation/z-mask) | 脱敏与遮蔽引擎——按敏感命中位置遮蔽，策略即数据 **（本仓）** |
| [z-mcp](https://github.com/z-opc-foundation/z-mcp) | MCP 服务端 + 上游聚合网关——Java 8 手写协议层，Spring Boot 自动装配 |
| [z-meta](https://github.com/z-opc-foundation/z-meta) | 元数据管理平台——租户/应用/接口清单登记 + @BizModel 业务模型采集 |
| [z-mill](https://github.com/z-opc-foundation/z-mill) | 资料磨坊——全格式文档转 Markdown/JSON/纯文本/HTML（对标 MinerU） |
| [z-mist](https://github.com/z-opc-foundation/z-mist) | KMS 分布式密钥/秘密管理平台——集中加密存储 + Netty 协议 + starter 嵌入 |
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 自研分布式消息队列——NameServer+Broker+Store，形状对标 RocketMQ、自有 Netty 协议 |
| [z-msg](https://github.com/z-opc-foundation/z-msg) | 多通道消息 + WebSocket 实时下发基础设施——短信/邮件/站内信/Webhook/IM 平台 |
| [z-observer](https://github.com/z-opc-foundation/z-observer) | 可观测性域——指标/日志/链路 + 告警引擎 |
| [z-opc](https://github.com/z-opc-foundation/z-opc) | 一人公司统一后端+统一前端——单 JVM all-in-one 承载协作/Agent/RPA/交易/资产等业务域与自研中间件 |
| [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) | 组织治理大脑——事实表、强制规范、跨仓工具集、新机器恢复钥匙（本清单权威持有处） |
| [z-opcs](https://github.com/z-opc-foundation/z-opcs) | 任务撮合/服务市场——用户付费发布诉求，持有者接单完成，金额自动瓜分 |
| [z-ops](https://github.com/z-opc-foundation/z-ops) | 个人公司运维平台——部署单元/镜像构建/WebShell/K8s 巡检 + Ops Agent 执行器 |
| [z-oss](https://github.com/z-opc-foundation/z-oss) | 对象存储服务——自持 local 后端 + aliyun/s3 对接层，starter 注入 OssTemplate |
| [z-product](https://github.com/z-opc-foundation/z-product) | 产品设计信息汇总仓（开发域 PM 工件） |
| [z-qa](https://github.com/z-opc-foundation/z-qa) | 步骤式 API 链路测试平台——用例/计划/执行/报告/质量看板（对标 TestRail·MeterSphere） |
| [z-rpa](https://github.com/z-opc-foundation/z-rpa) | RPA 产品皮——应用市场/需求中心（执行归 z-script，触发归 z-schedule） |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | Java 8 + Netty RPC 框架——微内核 + SPI 插件化，形状参照 Dubbo/gRPC |
| [z-schedule](https://github.com/z-opc-foundation/z-schedule) | 分布式任务调度中枢（对标 XXL-Job）——选主 + 时间环排期，starter 嵌入宿主 |
| [z-script](https://github.com/z-opc-foundation/z-script) | 多 DSL 脚本平台 + Mock 平台——沙箱执行/版本灰度/可注册 MCP Tool，starter 内嵌 |
| [z-security](https://github.com/z-opc-foundation/z-security) | 数据安全域编排层——场景→策略→识别→脱敏→水印→审计 四段流水线编排 |
| [z-sense](https://github.com/z-opc-foundation/z-sense) | 敏感识别与分类分级引擎——数据安全线底座（规则目录 + 可插拔识别器 + 分类分级） |
| [z-skill](https://github.com/z-opc-foundation/z-skill) | Skill 平台聚合器——归一化各家 Skill 形状，再按各家 API 形状发回 |
| [z-surl](https://github.com/z-opc-foundation/z-surl) | 短网址服务——短码生成/解析/命中统计，starter 一行集成 |
| [z-task](https://github.com/z-opc-foundation/z-task) | 任务协作平台——项目空间/看板/任务卡片（看板范式，对标 Trello·Teambition） |
| [z-team](https://github.com/z-opc-foundation/z-team) | 数字员工协同域——全栈独立仓，自 z-opc 毕业 |
| [z-trade](https://github.com/z-opc-foundation/z-trade) | 交易域独立仓——自 z-opc reactor 迁出 |
| [z-util](https://github.com/z-opc-foundation/z-util) | 底层 Java 工具库——89 个模块 POM，Java 8/17 口径，按需引用按需升级 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 自研 Java 向量数据库——嵌入式+可独立部署，ANN+Payload 过滤，兼容 Qdrant REST |
| [z-voice-demo](https://github.com/z-opc-foundation/z-voice-demo) | 语音链路 demo——流式识别→kernel LLM→流式 TTS→可打断 的契约验证实现 |
| [z-watermark](https://github.com/z-opc-foundation/z-watermark) | 水印引擎——显式文字/图片隐写/盲水印，三种技术成对「打+验」 |
| [z-webide](https://github.com/z-opc-foundation/z-webide) | 企业级 WebIDE 后端——Git 仓/共享目录秒变 code-server 实例，全生命周期管理 |
| [z-wf](https://github.com/z-opc-foundation/z-wf) | 自研流程引擎——不依赖 Camunda/Flowable，定义层与运行时全自研 |

**已退役仓**（迁至 [z-trash](https://github.com/z-trash) 组织，archived 只读）：

| 仓 | 说明 |
|---|---|
| [z-agent-proxy](https://github.com/z-trash/z-agent-proxy) | 三方 coding agent 适配代理（Aider/Cursor → kernel Agent SPI），2026-10-26 退役 |
| [z-kb](https://github.com/z-trash/z-kb) | 自研知识库系统（对标 LightRAG/RAGFlow），2026-11 并入 z-cognition 认知舱后退役 |
| [z-ontology](https://github.com/z-trash/z-ontology) | 本体层（TBox/ABox/RDFS 推理），并入 z-cognition 后退役 |

另有 [z-report](https://github.com/z-opc-foundation/z-report)（报表域，2026-10-11 撤销并入 z-lc（远端已删除））。

总清单权威持有处在 [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) `008_组织规范/005_兄弟仓清单.md`。
