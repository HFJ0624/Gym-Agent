# Gym-Agent 体育场馆预约平台

## 1. 项目介绍

Gym-Agent 是一个基于 Spring Boot 3 + Vue3 + 自建 Agent Harness + RAG 的体育场馆预约平台，包含用户前台、后台管理系统和智能 Agent 服务。

项目核心业务包括：场馆浏览、场地预约、预约时段可视化、商品下单、订单管理、评论收藏、公告管理、后台运营管理、邮箱通知、智能问答、RAG 知识库问答与 Agent 工具调用。

Agent 部分没有直接套用 Agent 框架，而是自建了一套单 Agent 运行时：`GymAgentHarness` 负责会话、预算、锁与状态提交，`ReAct` 运行内核负责模型与工具循环，意图路由/直达路由/确认 Workflow 负责把“咨询、办理、追问”分开。用户可以通过自然语言让 Agent 查询场馆与规则、生成预约/取消/商品下单草稿，点击确认后走真实业务事务并返回结构化回执。

## 2. 项目亮点

### 平台业务

- 前后端分离架构，包含用户前台和后台管理端
- 场馆、场地、预约、订单、公告、评论、收藏等核心业务
- 按日期和时间段预约场地，同一时段预约冲突校验
- 预约时段可视化展示
- Redis（缓存/验证码/登录态/会话状态）、MinIO（文件存储）、邮箱通知
- 后台运营数据统计与可视化图表

### 智能 Agent（自建 Harness）

- **自建 Agent Harness**：会话加载与提交、意图/改写/工作流编排、预算与锁管理、Trace 观测，全部在一个入口内完成
- **ReAct 运行内核**：模型调用链（预算拦截器 + 观测拦截器 → 自建 OpenAI 兼容 LlmClient）与工具调用链（授权 → 参数校验 → 预算 → 观测 → Executor）双向拦截
- **Function Calling 工具注册表**：预约草稿、取消草稿、商品下单草稿、知识检索、场馆/公告/预约/订单查询等工具统一注册，按意图 + SOP 授权，高风险确认类工具不向模型开放
- **Workflow 控制办理边界**：草稿生成 → 用户确认 → 真实办理三段式；确认码 + Redis 草稿 + 业务最终校验；真实执行类工具永远不在模型工具列表中
- **客服 SOP**：服务端目录（catalog.json + 4 个场景文档）按意图、办理型措辞与待办草稿类型选择，候选工具取受控并集再求交集
- **结构化回复（v2）**：正文、引用来源、卡片、动作全部由后端组装；v1 字符串接口保留兼容，两接口共用同一 Harness
- **模型调用预算**：AGENT / KNOWLEDGE_ANSWER / CONTEXT_SUMMARY / ANSWER_REPAIR 用途化计数，主循环、辅助与总调用三层上限
- **上下文自动摘要**：达到阈值先摘要后裁剪，摘要失败降级完整轮次裁剪，硬超限直接终止（模型调用为 0）
- **全链路 Trace**：RUN_START / INTENT_ROUTE / SOP_SELECT / TOOL_CALL / KNOWLEDGE_RETRIEVE / EVIDENCE_VALIDATE / CONTEXT_COMPACT / SESSION_COMMIT / RUN_FINISH 等步骤可在后台查看

### RAG 知识库

- PostgreSQL + pgvector 向量检索，Embedding 与检索耗时/失败分开记录
- **检索与生成分离**：知识工具只返回结构化证据（docId:chunkIndex:内容指纹 稳定 ID + 引用标签 K1..Kn），回答由外层 Agent 统一生成
- **范围与有效性校验**：平台/场馆/场地/公告/FAQ 分类归属校验，启用/删除/内容指纹过滤，无范围查询只放行平台级公共资料，跨场馆比较走受控模式
- **引用校验**：回答中的 [K标签] 由后端校验，非法引用一次有界修正，仍失败返回受控资料展示，不编造来源
- 命中来源展示（标题、范围、相似度、片段）、单条知识增量索引、检索评测接口

### 会话连续性与受控恢复

- 会话并发锁（Redis Lua，单调时钟租约，续租失败/过期自然失效）
- **原子状态提交**：历史、草稿、业务槽位、运行记录全部走“Lua 校验锁令牌 → 写入”，旧请求失锁后无法覆盖新状态
- **持久运行记录**：RUNNING / 终态 / INTERRUPTED（按锁存在性条件写识别中断），latestRun 指针防旧覆盖
- **会话恢复快照**：`GET /front/agent/sessions/{sessionId}` 只读返回最近展示对话、运行状态与当前有效草稿，零模型调用
- 前端刷新恢复、账号切换清空、断连先查询不重发、SESSION_BUSY 提示、有界轮询

### 商品确认交易可靠性

- **幂等回执**：`shopping_confirmation` 表主键唯一约束收敛同一业务意图，回执占位前置到库存扣减之前，冲突时事务退出并由外层重放已提交订单
- **条件扣减**：库存（`stock >= quantity` 且商品有效）与余额（`balance >= amount`）数据库条件更新，影响行数为零即失败并整体回滚
- **结构化结果**：PAID / PENDING_PAYMENT / OUT_OF_STOCK / PRICE_CHANGED / INVALID_DRAFT / IN_PROGRESS / RESULT_UNKNOWN / BUSINESS_FAILED；已支付回执卡片只展示真实订单字段
- 普通商城下单与客服确认复用同一交易核心，库存不足显式失败，不再虚报成功
- 只读回执查询接口按可信身份归属校验

## 3. 技术栈

### 后端

- Java 17
- Spring Boot 3.0.5
- Spring MVC
- MyBatis
- MySQL 8
- Redis 7（会话锁 / 状态原子写入 / 运行记录）
- PostgreSQL + pgvector
- MinIO
- WebSocket
- Mail
- EasyExcel
- LangChain4j（仅 RAG 的 EmbeddingStore 部分沿用）
- Maven 多模块

### 前端

- Vue3
- Element Plus
- Vite
- Vue Router
- Axios
- Pinia / Store
- ECharts

### AI 与 Agent

- 自建单 Agent Harness（会话 / 预算 / 锁 / 状态提交）
- 自建 ReAct 运行内核（模型循环 + 工具循环）
- 自建 OpenAI Compatible LlmClient（Function Calling、用途化预算、超时随剩余预算收缩）
- 工具注册表 + 双向拦截器链
- RAG：Embedding 向量化 + pgvector 检索 + 证据结构 + 引用校验
- 会话摘要与上下文预算控制
- 意图路由 / 直达路由 / 确认 Workflow
- 客服 SOP 服务端目录

### 工程化

- Maven
- Docker
- Git / GitHub
- JUnit 5 + Mockito（104 个测试）
- 隔离 MySQL / Redis 集成用例（Testcontainers 或本地实例可执行）

## 4. 系统角色

### 普通用户

- 注册登录、浏览场馆、查看公告与场地详情
- 选择预约日期和时间段，查看预约时段状态，预约场地
- 商城购物、余额支付、查看我的订单与预约
- 评论和收藏
- 使用 Agent 咨询规则 / 查询业务 / 生成预约、取消、下单草稿
- 点击确认按钮完成真实预约 / 取消 / 下单，拿到真实订单回执
- 刷新页面恢复最近对话与当前有效草稿
- 使用 RAG 问答查询平台规则、预约流程、场馆公告

### 管理员

- 用户 / 场馆 / 场地 / 预约 / 订单 / 公告 / 评论管理
- RAG 知识库管理与单条知识增量索引
- Agent Trace 与工具调用日志查看
- 运营数据统计

### 智能 Agent

- 查询场馆、公告、可预约时段、我的预约、我的订单
- 生成预约草稿、取消草稿、商品下单草稿并返回卡片
- 用户确认后执行真实业务，返回结构化办理结果（已支付 / 待支付 / 库存不足 / 价格变化等）
- 调用知识库回答规则类问题并返回已校验的引用来源
- 会话多轮上下文（自动摘要）、运行状态可查询、断连后可恢复

## 5. Agent 架构

一次请求的真实调用链：

```text
POST /front/agent/chat 或 /chat/v2
        │
AgentServiceImpl（可信身份解析 AgentTrustedUserResolver + 会话锁 + Trace 上下文）
        │
GymAgentHarness（DefaultGymAgentHarness）
        ├── 会话锁 AgentSessionLockService（Lua 加锁 / 续租 / 释放，单调时钟租约）
        ├── 会话快照加载 AgentSessionMessageStore（消息 + 摘要 + 展示轮次，读取失败拒绝覆盖）
        ├── 运行记录 AgentRunStore（RUNNING → 终态 / INTERRUPTED，latestRun 指针）
        ├── 业务上下文 AgentContextEnhanceService（场馆/场地槽位，受保护写入）
        │
        ├── 意图路由 GymIntentRouter → 问句改写 → AgentWorkflowRouter
        │       ├── CONFIRMATION        → AgentConfirmationWorkflow（确认码校验 → 真实业务 → 受保护清草稿）
        │       ├── CANCEL_BOOKING_DRAFT → 取消草稿 Workflow
        │       ├── CLARIFY             → 追问
        │       ├── DIRECT_ROUTE        → AgentDirectRouteService（常用查询直达）
        │       └── REACT               → DefaultReActRuntime
        │
        ├── DefaultReActRuntime（ReAct 循环）
        │       ├── 模型链 ModelInvocationChain（ModelBudget → ModelTrace → LlmClient）
        │       ├── 上下文预算 AgentContextBudgeter（摘要 → 裁剪 → 硬上限终止）
        │       ├── 工具链 ToolInvocationChain（ToolBudget → Authorization → Argument → Trace → Executor）
        │       └── 证据登记 EvidenceRegistry（K1..Kn 引用标签 → 回答引用校验 → ANSWER_REPAIR）
        │
        ├── finish()
        │       ├── 组装卡片/动作 AgentResponseAssembler（来自真实 PendingDraft）
        │       ├── 展示轮次写入 SessionHistory（turnId=runId，按轮去重）
        │       ├── 会话提交 saveHistoryIfHolder（Lua 校验锁令牌，原子写）
        │       └── 运行终态 AgentRunStore.finish
        │
        └── AgentChatResponse（v2：正文 / 来源 / 卡片 / 动作 / errorCode / sessionCommitted）
```

关键约束：

- 单 Agent 单入口：不另起第二套 Harness / Runtime / 工具注册表 / 记忆系统
- 模型不能自己确认、扣款、退款；真实执行类工具不进入模型工具列表
- 所有会话状态写入（历史 / 草稿 / 槽位 / 运行记录）在 Redis 侧原子校验锁持有令牌
- Token 数为本地估算（明确标注），真实扣减以数据库条件更新为准
- 业务 exactly-once 不由 Agent 保证，由数据库唯一约束 + 事务 + 业务最终校验保证

## 6. 接口说明

| 接口 | 说明 |
|---|---|
| `POST /front/agent/chat` | v1 兼容接口，Result\<String\>（草稿场景为文本），与 v2 共用同一 Harness |
| `POST /front/agent/chat/v2` | 结构化回复：message / sources / cards / actions / errorCode / sessionCommitted |
| `GET /front/agent/sessions/{sessionId}` | 会话恢复快照（登录、只读、零模型调用）：最近展示对话 + 运行状态 + 当前有效草稿 |
| `GET /front/agent/confirmations/{confirmationId}` | 商品确认回执查询（只读、归属校验）：真实订单号与支付状态 |
| `POST /front/agent/...` 其余 | 意图/知识评测等管理接口见 controller |

## 7. 功能模块

| 模块 | 说明 |
|---|---|
| 用户模块 | 登录、注册、个人信息 |
| 场馆模块 | 场馆列表、详情、状态管理、图片展示 |
| 场地模块 | 场地管理、场地类型、价格、容量、状态 |
| 预约模块 | 按日期和时段预约、预约冲突校验、我的预约查询 |
| 订单模块 | 预约订单、商城订单、订单状态流转 |
| 支付模块 | 余额支付、支付流水、退款流水（条件扣减 + 权威流水） |
| 公告模块 | 公告发布、公告展示、公告分类 |
| 评论收藏 | 用户评论、收藏场馆 |
| 通知模块 | 预约通知、下单通知、取消通知、邮箱通知（AFTER_COMMIT） |
| Agent 模块 | 自建 Harness、ReAct、Workflow、工具注册、SOP、预算、Trace |
| RAG 模块 | 知识库问答、证据检索、范围/版本校验、引用校验、来源展示、增量索引 |
| 会话模块 | 并发锁、原子状态提交、运行记录、恢复快照 |
| 交易模块 | 商品确认幂等回执、库存/余额条件扣减、事务一致性 |
| 工具日志模块 | Agent Trace 步骤、工具调用参数与结果查看 |
| 可视化模块 | 预约时段可视化、后台统计图表 |

## 8. 项目结构

```text
Gym
├── Gym-parent                       # 后端父工程
│   ├── Gym-admin                    # 后端启动模块与业务接口
│   │   ├── src/main/java/com/sau/gym/admin
│   │   │   ├── agent                # Agent 模块
│   │   │   │   ├── harness          # GymAgentHarness + 模型/工具拦截器
│   │   │   │   ├── runtime          # DefaultReActRuntime / 运行上下文 / 结果与状态
│   │   │   │   ├── workflow         # 意图后工作流（确认 / 取消草稿）
│   │   │   │   ├── intent           # 意图路由
│   │   │   │   ├── tool             # 工具注册表 / 选择器 / Executor / 工具类
│   │   │   │   ├── context          # 上下文预算 + 自动摘要 + 系统提示
│   │   │   │   ├── memory           # 会话快照 / 业务上下文 / 展示轮次
│   │   │   │   ├── store            # 锁 / 草稿 / 受保护写入（Lua）/ 运行记录依赖
│   │   │   │   ├── run              # 运行记录（RUNNING/终态/INTERRUPTED）
│   │   │   │   ├── snapshot         # 会话恢复快照 + 卡片组装器
│   │   │   │   ├── sop              # 客服 SOP 目录与场景文档
│   │   │   │   ├── rag              # （工具侧）知识证据检索工具
│   │   │   │   └── llm              # 自建 OpenAI 兼容 LlmClient
│   │   │   ├── rag                  # RAG 模块（检索 / 过滤 / 指纹 / 证据 / 生成）
│   │   │   ├── controller           # 接口层（front / admin / agent）
│   │   │   ├── mapper               # MyBatis Mapper
│   │   │   ├── service              # 业务服务（含商品确认交易核心）
│   │   │   └── AdminApplication.java
│   │   ├── src/main/resources
│   │   │   ├── agent/sop            # SOP 目录与场景文档
│   │   │   └── mapper               # MyBatis XML
│   ├── Gym-Model                    # 实体类、DTO、VO
│   └── Gym-common                   # 公共模块
│       ├── common-log / common-service / common-util
├── front
│   └── Gym-admin
│       ├── Gym-admin                # 后台管理端
│       └── Gym-front                # 用户前台端（含 AiAssistant 会话组件）
├── db
│   ├── gym.sql                      # 数据库初始化脚本
│   └── phase4_shopping_confirmation.sql  # 商品确认回执表迁移
├── README.md
└── .gitignore
```

## 9. 部署项目

环境需求：JDK 17、Maven 3.8+、MySQL 8、Redis 7、PostgreSQL + pgvector（RAG）、Node.js 16+

1. **初始化数据库**

   先执行 `db/gym.sql`；若部署第四轮及以后版本，再执行 `db/phase4_shopping_confirmation.sql`（新增商品确认回执表，含回滚语句）。

2. **修改配置文件**

   修改 `Gym-parent/Gym-admin/src/main/resources/application.yml` 为自己的环境；新建本地配置文件 `application-local.yml` 存放 AI Key、数据库、MinIO 等敏感配置（已被 .gitignore 忽略，不要提交）。

   Agent 相关可调配置（均有代码默认值）位于 `gym.agent.harness.*`：模型/辅助/总调用预算、上下文硬预算与摘要阈值、工具次数、锁 TTL 等；`gym.rag.retrieval.*`：topK、相似度阈值、候选扩大倍数、内容版本严格校验。

3. **启动后端**

   运行 `Gym-parent/Gym-admin/src/main/java/com/sau/gym/admin/AdminApplication.java`，后端端口 9601。

4. **启动前端**

```shell
后台管理端
cd front/Gym-admin/Gym-admin
npm install
npm run dev
# 后台管理端口 3001

用户前台端
cd front/Gym-admin/Gym-front
npm install
npm run dev
# 用户前台端口 3002
```

5. **运行测试**

```shell
cd Gym-parent
mvn -pl Gym-admin -am test        # 104 个单元测试（JDK 17）
cd front/Gym-admin/Gym-front
npm run build                     # 前端生产构建
```

商品确认的 MySQL 并发/回执收敛集成用例位于 `ShoppingConfirmationMySqlIT`：通过环境变量 `GYM_IT_DB_URL/GYM_IT_DB_USER/GYM_IT_DB_PASS` 指向隔离测试库后移除 `@Disabled` 执行。

## 10. 已知边界

- 引用校验保证证据的身份、范围与版本正确，不自动保证语义完全正确
- 会话恢复只还原已保存的展示状态；INTERRUPTED 且曾进入确认阶段的办理结果需要人工核实
- 业务 exactly-once 由数据库唯一约束、事务与业务最终校验保证，不由 Agent 运行时保证
- 真实模型、生产 Redis/MySQL/向量库的在线联调需在具备 Key 与网络的环境执行

## 11. 总结

本项目的起源是想着学习java后端方向以及前端，项目背景是本人除了编写代码的其他爱好就是健身及各种运动才有感而生想到的项目背景，我是在学完了各种技术以及做了四个项目前提下，自己想着动手搭建从0到1的一个项目，期间会搭配着各种AI以及大佬的博客解决项目中遇到的各种难题。我觉得该项目可以够一个小白跟着视频学完，想完全自学的一个项目，你看完我的或者别人的，感觉都会有点灵感，虽然这只是一个SpringBoot+Vue的一个前后端分离项目，放在现在的AI大背景下可能不是很够看的一个项目，但我觉得要学就要大胆去做，而不是畏手畏脚不敢去做，如果有更好的意见，恳请各位指点。回想起来，自己也是刚开始从java的输入System.out.println();都会打错，到现在也慢慢进步，也能慢慢给一些同学建议。当你没那么在意未来，当下最重要，也许新学的技术会更新迭代的很快，也许有一天AI会代替程序员，也许现在都充斥着入行Java就是1949年入国军，但是我觉得不要被这些给吓到了而去焦虑未来，既然选择了这条路，不清楚是不是最好的，那就坚信自己的选择，相信自己的选择是最正确的选择。最后，接受各个大佬的指点以及互相讨论学习也可，谢谢大家。
持续更新ing...
