# 部署与运行

## 1. 运行形态

`z-mask-core` 是纯领域库，编排层（z-security）按坐标直接依赖并在自己的请求路径上调用，**不必起进程**。
`z-mask-web` 提供独立可跑的 REST 管控面（`ZMaskWebApplication`）：

| 项 | 值 |
|---|---|
| 端口 | `8095`（`server.port=${Z_MASK_SERVER_PORT:8095}`） |
| Java / 框架 | 17 / Spring Boot 3.3.13 火车（parent `z-boot-parent:1.2.0`） |
| HTTP 面 | `POST /z-team/api/mask/apply`（脱敏）· `GET /api/mask/health`（健康） |
| 自动装配 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → `MaskAutoConfiguration` |
| 上游 | 依赖 `z-sense-core`（识别结果为输入；z-sense 端口 `8094` 与本仓无运行时耦合） |
| 制品 | 本轮**不发 Maven Central** |

## 2. 环境变量

数据源键名模式 `z.base.db.mask.*`（`ModuleDataSourceTemplate` 的权威前缀常量，缺省回落 `z.base.db.default.*`）。
**凭据一律走环境变量注入，不落明文**（lead 008 凭证管理规范）。

| 变量 | 默认 | 用途 |
|---|---|---|
| `Z_MASK_SERVER_PORT` | `8095` | HTTP 端口 |
| `Z_MASK_DB_HOST` | `127.0.0.1` | 数据源主机 |
| `Z_MASK_DB_PORT` | `3306` | 数据源端口 |
| `Z_MASK_DB_NAME` | `oc` | 库名（安全线共用单库，同 z-trade / z-biz / z-opcs） |
| `Z_MASK_DB_USERNAME` | **空** | 必须注入，无默认值 |
| `Z_MASK_DB_PASSWORD` | **空** | 必须注入，无默认值 |

连接池：`initial-size=2` / `min-idle=2` / `max-active=10`；`mybatis-plus.configuration.map-underscore-to-camel-case=true`。

显式给 `z.base.db.mask.url` 即切直连模式（绕开键名模式拼装），本地验收走这条路到 H2：

```
z.base.db.mask.url=jdbc:h2:mem:zmask_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1
z.base.db.mask.username=sa
z.base.db.mask.password=
```

> ⚠ 本仓验收**全部在本地 H2 内存库**：不连生产 RDS、不在 `oc` 库建表、不对任何真实库写入。

## 3. 密钥怎么给

`ENCRYPT` 策略需要密钥，但**密钥不进仓、不进库、不进策略对象**：
运行期由 `spi/SecretResolver` 的实现按 `keyRef` 取。装配哪一份 resolver 是部署方的决定
（本仓只提供接口与默认装配位），`keyRef` 只是一个字符串句柄。
取不到密钥时的行为是**全遮蔽**，不是报错也不是原文放行（见架构文档的降级口径）。

## 4. 建表

**本仓无 DDL**：10 条默认策略内置在 `support/DefaultPolicies`，M1 不读库，因此没有本仓要建的表。
安全线需要建表的是 z-audit（哈希链台账）与 z-security（策略表），执行清单属**人工动作**，
见 [`_doc/005_testing/001_验收复跑.md`](_doc/005_testing/001_验收复跑.md) §4。

## 5. 构建与验证

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home

mvn -f z-mask-core/pom.xml test    # 31 例（15 引擎行为 + 16 「按命中脱敏」）
mvn -f z-mask-web/pom.xml  test    # 4 例（含真起上下文的 /z-team/api/mask/apply 端点验收）
```

> ⚠ **必须 JDK 17**。本机默认 JDK 23 会让 Groovy 3.0.9 读不懂 class major 67 ⇒ **假红**。
> ⚠ 复跑命令**别接 `| tail; echo $?`** —— 取到的是 `tail` 的退出码 ⇒ **假绿**；先重定向到日志再取 `$?`。

`MaskApiE2ETest` 以 `WebEnvironment.RANDOM_PORT` **真起 Spring 上下文**并用 `TestRestTemplate` 打真 HTTP，
所以验收入库不需要先启服务。

## 6. 起进程这件事的当前状态

`z-mask-web/pom.xml` **没有配 `spring-boot-maven-plugin`** —— 打出来的是普通 jar（无 repackage、无 `Main-Class`），
`mvn spring-boot:run` 与 `java -jar` 两条路**目前都走不通**。
要独立部署成进程，需要补 repackage 插件与镜像资产，届时按 lead 002 规范落 `002_deploy/` 与 `003_script/`。
