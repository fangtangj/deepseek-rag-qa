# DeepSeek RAG 课程问答

基于 **Spring AI + DeepSeek** 的课程知识库问答服务。

把课程资料切分成片段、向量化后写入向量库；提问时先检索最相关的若干片段，拼进 Prompt 再交给 DeepSeek 生成回答，**回答附带原文出处**。

项目自带一个网页版调试界面（`/index.html`），可以直观对比「带检索」和「不带检索」两种模式的回答差异。

---

## 功能

- **课程资料入库**：启动时读取 `src/main/resources/docs/` 下的 Markdown → 按 token 切分（中文标点断句）→ 向量化 → 写入 PostgreSQL。入库前先 `TRUNCATE`，保证每次启动都是干净的快照。
- **分批向量化**：按 10 条一批调用 embedding 接口（供应商单次请求的硬上限，详见下文「踩过的坑」）。
- **检索增强问答**：`POST /course/qa` —— 向量检索 Top-K 片段 → 拼装 Prompt → DeepSeek 生成 → 标注引用来源。
- **对照组接口**：`POST /course/qa/plain` —— 不带检索直接问模型，用于做准确率对比实验。
- **网页调试界面**：`GET /index.html` —— 两种模式切换、手写极简 Markdown 渲染、耗时显示、历史记录。

---

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 4.1.1 |
| AI 框架 | Spring AI 2.0.1 |
| 对话模型 | DeepSeek（`spring-ai-starter-model-deepseek`） |
| 向量化模型 | 阿里云百炼 `text-embedding-v4`（1024 维） |
| 向量库 | PostgreSQL 17 + pgvector 0.8.7（HNSW / 余弦距离） |
| 构建 | Maven（含 Wrapper） |

> **为什么用两家供应商**：DeepSeek 官方只有对话接口，没有 `/embeddings` 端点。所以链路拆成「百炼做向量化 + DeepSeek 做生成」。这里通过 `spring.ai.openai.embedding.base-url` 指向百炼的 OpenAI 兼容端点实现——`spring-ai-starter-model-openai` 只当通用客户端用，并不真的连 OpenAI。

---

## 架构与数据流

**离线索引（启动时执行一次）**

```
docs/*.md → 读取为 Document → TokenTextSplitter 切分 → 分批 embedding → 写入 pgvector
```

**在线问答（每次提问）**

```
用户提问 → 问题向量化 → pgvector 相似度检索 Top-K → 拼装 Prompt → DeepSeek 生成 → 答案 + 引用
```

在线这段由 `QuestionAnswerAdvisor` 完成——它作为 Advisor 挂在 `ChatClient` 上，业务代码里看不到检索逻辑。

---

## 实测结果

**入库**：8 份课程资料（约 2.6 万字）→ 切分为 **60 个片段**（各文件分别为 6 / 8 / 6 / 8 / 8 / 8 / 7 / 9），分 6 批写入。

**检索增强 vs 直接提问**（同一个课程私有问题：「这门课的作业逾期了怎么扣分？」）

| 模式 | 回答 |
|---|---|
| `/course/qa`（带检索） | 答出具体规则「每逾期 1 天扣 10%，超过 3 天按 0 分」，并附「参考资料：常见问题 FAQ」 |
| `/course/qa/plain`（不带检索） | 只能给通用建议（去看大纲、联系老师），答不出具体规则 |

这就是「为什么需要 RAG」最直接的演示：**模型本身不知道这门课的规则，规则只存在于私有语料里。**

---

## 快速开始

### 1. 前置条件

- JDK 17
- Docker Desktop（跑 PostgreSQL + pgvector）
- 两个 API Key：DeepSeek、阿里云百炼

### 2. 启动向量库

```bash
docker compose up -d
docker exec pgvector psql -U postgres -d ragdb -c "\dx"
```

期望输出里能看到 `vector` 扩展。如果拉不到镜像（国内访问 Docker Hub 超时），在 Docker Desktop 的 `Settings → Docker Engine` 里配 `registry-mirrors`。

### 3. 配置 API Key（不要写进代码）

Windows PowerShell：

```powershell
setx DEEPSEEK_API_KEY "sk-你的deepseek密钥"
setx DASHSCOPE_API_KEY "sk-你的百炼密钥"
```

> 设完必须**重开终端和 IDE** 才会生效。

### 4. 准备课程语料

把 Markdown 文件放到 `src/main/resources/docs/`，一份文件一个主题。仓库里已带 8 份示例语料。

### 5. 启动

```bash
./mvnw spring-boot:run
```

启动日志：

```
[RAG] 载入 8 份资料，切分为 60 个片段，分 6 批入库
```

---

## 接口说明

### `POST /course/qa` —— 检索增强问答

```bash
curl -X POST http://localhost:8080/course/qa \
  -H "Content-Type: application/json" \
  -d '{"question":"Redis 缓存雪崩怎么解决？"}'
```

```json
{ "answer": "……（回答正文，末尾列出引用的资料文件名）" }
```

### `POST /course/qa/plain` —— 对照组

请求体相同，但不做检索，直接问模型，用于对比「有 / 无检索」的回答质量差异。

> 两个接口都要求 `Content-Type: application/json`，用 `text/plain` 调用会返回 415。

---

## 关键参数

| 参数 | 取值 | 位置 |
|---|---|---|
| 切分块大小 `chunkSize` | 400 token | `CourseDocLoader` |
| 最小块字符数 `minChunkSizeChars` | 200 | 同上 |
| 断句标点 | `。？！；` + 换行 | 同上 |
| embedding 单批条数 | 10 | 同上 |
| 检索条数 `topK` | 4 | `AiConfig` |
| 相似度阈值 | 0.3 | 同上 |
| 生成温度 `temperature` | 0.3 | `application.yaml` |
| 向量维度 | 1024 | 需与 embedding 模型输出一致 |

---

## 踩过的坑

这几个问题都是实际调试中撞到并定位的，记录下来：

**1. 百炼的 base-url 必须带 `/v1`**

写成 `https://dashscope.aliyuncs.com/compatible-mode` 会返回 404。因为 OpenAI 兼容协议会在 base-url 后面拼 `/embeddings`，少了 `/v1` 实际请求就变成 `.../compatible-mode/embeddings`。正确写法是 `https://dashscope.aliyuncs.com/compatible-mode/v1`。

**2. 百炼 embedding 单次最多 10 条文本**

60 个片段一次性发过去会返回 `400 InternalError.Algo.InvalidParameter: batch size is invalid, it should not be larger than 10`。

Spring AI **不会**按条数分批——`TokenCountBatchingStrategy` 是按 token 分（默认 8191），60 块约 24000 token，分下来每批仍有 20 条左右，照样超限。所以只能自己在代码里按条数切批，见 `CourseDocLoader.EMBEDDING_BATCH_SIZE`。

**3. pgvector：镜像自带扩展 ≠ 数据库已启用扩展**

`\dx` 里只有 `plpgsql` 是正常的，需要执行一次 `CREATE EXTENSION IF NOT EXISTS vector`。应用里用 `initialize-schema: true` 自动完成这件事。

**4. 中文语料必须显式配置断句标点**

`TokenTextSplitter` 默认标点是 `. ? ! \n`，没有中文标点。中文长句找不到句末位置会被从中间硬切断，所以要用 `withPunctuationMarks(List.of('。','？','！','；','\n'))`。

**5. Spring AI 2.0 的依赖坐标和 1.x 不同**

检索增强器从 `spring-ai-advisors-vector-store` 改名为 `spring-ai-vector-store-advisor`；Spring Boot 4 的 Web starter 也从 `spring-boot-starter-web` 改成 `spring-boot-starter-webmvc`。照抄旧教程会编译失败。

---

## 目录结构

```
src/main/java/com/ruanzixuan/deepseek_rag_qa/
├── DeepseekRagQaApplication.java       启动类
├── config/AiConfig.java                装配 ChatClient + 检索增强器
├── loader/CourseDocLoader.java         启动时读取语料、切分、分批入库
└── controller/CourseQaController.java  对外 HTTP 接口

src/main/resources/
├── application.yaml                    模型与数据源配置
├── docs/                               课程语料（8 份 Markdown）
└── static/index.html                   网页调试界面
```

---

## 开发说明

- **每次启动都会重新入库**：`CourseDocLoader` 会先 `TRUNCATE TABLE vector_store` 再重新灌，好处是数据始终与 `docs/` 一致，不会有重复片段累积；代价是启动时要等 embedding 调用完成（60 个片段约 6 批）。
- **`initialize-schema: true`** 让应用启动时自动执行 `CREATE EXTENSION vector` 并建表，不需要手工建表。
- 语料目录用的是**相对路径** `src/main/resources/docs`，依赖运行时工作目录为项目根。在 IDE 里直接运行没问题；打成 jar 后需要改成 classpath 读取——这是已知待改进点。

---

## 待补充

- **20 题测试集与两组准确率对比**：用于量化「检索增强」相对「直接提问」的提升幅度，测试集正在整理中。
