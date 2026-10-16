# DeepSeek RAG 课程问答

基于 **Spring AI + DeepSeek** 的课程知识库问答服务。

把课程资料切分成片段、向量化后写入向量库；提问时先检索最相关的若干片段，拼进 Prompt 再交给 DeepSeek 生成回答，**回答附带原文出处**。

项目自带一个网页版调试界面（`/index.html`），可以直观对比「带检索」和「不带检索」两种模式的回答差异。

---

## 功能

- **课程资料入库**：启动时自动读取 `src/main/resources/docs/` 下的 Markdown，按 token 切分（中文标点断句）、向量化、写入 PostgreSQL。
- **检索增强问答**：`POST /course/qa` —— 向量检索 Top-K 片段 → 拼装 Prompt → DeepSeek 生成 → 标注引用来源。
- **对照组接口**：`POST /course/qa/plain` —— 不带检索直接问模型，用于做准确率对比实验。
- **网页调试界面**：`GET /index.html` —— 内置两种模式切换，方便演示与截图。

---

## 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 17 | |
| 框架 | Spring Boot 4.1.1 | |
| AI 框架 | Spring AI 2.0.1 | 统一模型调用与 RAG Advisor 抽象 |
| 对话模型 | DeepSeek（`spring-ai-starter-model-deepseek`） | 负责生成回答 |
| 向量化模型 | 阿里云百炼 `text-embedding-v4`（1024 维） | DeepSeek **不提供** embeddings 接口，故向量化单独用一家供应商 |
| 向量库 | PostgreSQL 17 + pgvector 0.8.7 | HNSW 索引、余弦距离 |
| 构建 | Maven（含 Wrapper） | |

> **为什么用两家供应商**：DeepSeek 官方只有对话接口，没有 `/embeddings` 端点。所以链路拆成「百炼做向量化 + DeepSeek 做生成」。这一处是通过 `spring.ai.openai.embedding.base-url` 指向百炼的 OpenAI 兼容端点实现的（`spring-ai-starter-model-openai` 在这里只当通用客户端用）。

---

## 架构与数据流

**离线索引（启动时执行一次）**

```
docs/*.md → 读取为 Document → TokenTextSplitter 切分 → embedding 向量化 → 写入 pgvector
```

**在线问答（每次提问）**

```
用户提问 → 问题向量化 → pgvector 相似度检索 Top-K → 拼装 Prompt → DeepSeek 生成 → 返回答案 + 引用
```

在线这段由 `QuestionAnswerAdvisor` 完成——它作为 Advisor 挂在 `ChatClient` 上，业务代码里看不到检索逻辑。

---

## 快速开始

### 1. 前置条件

- JDK 17
- Docker Desktop（用于跑 PostgreSQL + pgvector）
- 两个 API Key：DeepSeek、阿里云百炼

### 2. 启动向量库

```bash
docker compose up -d
docker exec pgvector psql -U postgres -d ragdb -c "\dx"
```

期望输出里能看到 `vector` 扩展。

### 3. 配置 API Key（不要写进代码）

Windows PowerShell：

```powershell
setx DEEPSEEK_API_KEY "sk-你的deepseek密钥"
setx DASHSCOPE_API_KEY "sk-你的百炼密钥"
```

> 设完必须**重开终端和 IDE** 才会生效。

### 4. 准备课程语料

把 Markdown 文件放到 `src/main/resources/docs/` 目录下，一份文件一个主题。

### 5. 启动

```bash
./mvnw spring-boot:run
```

启动日志中会打印入库结果，例如：

```
[RAG] 载入 8 份资料，切分为 61 个片段，已入库
```

然后打开 <http://localhost:8080/index.html>。

---

## 接口说明

### `POST /course/qa` —— 检索增强问答

```bash
curl -X POST http://localhost:8080/course/qa \
  -H "Content-Type: application/json" \
  -d '{"question":"线程池的核心参数有哪些？"}'
```

```json
{ "answer": "……（回答正文，末尾列出引用的资料文件名）" }
```

### `POST /course/qa/plain` —— 对照组

请求体相同，但不做检索，直接问模型。用于对比「有 / 无检索」的回答质量差异。

---

## 关键参数

| 参数 | 取值 | 位置 |
|---|---|---|
| 切分块大小 `chunkSize` | 400 token | `CourseDocLoader` |
| 最小块字符数 `minChunkSizeChars` | 200 | 同上 |
| 断句标点 | `。？！；` + 换行 | 同上（中文语料必须显式配置，否则长句会被从中间切断） |
| 检索条数 `topK` | 4 | `AiConfig` |
| 相似度阈值 | 0.3 | 同上 |
| 生成温度 `temperature` | 0.3 | `application.yaml` |
| 向量维度 | 1024 | 需与 embedding 模型输出一致 |

---

## 目录结构

```
src/main/java/com/ruanzixuan/deepseek_rag_qa/
├── DeepseekRagQaApplication.java   启动类
├── config/AiConfig.java            装配 ChatClient + 检索增强器
├── loader/CourseDocLoader.java     启动时读取语料、切分、入库
└── controller/CourseQaController.java  对外 HTTP 接口

src/main/resources/
├── application.yaml                模型与数据源配置
├── docs/                           课程语料（Markdown）
└── static/index.html               网页调试界面
```

---

## 开发说明

- **每次启动都会重新入库**。`CourseDocLoader` 在每次启动时执行 `vectorStore.add()`，而 pgvector 默认使用 UUID 主键，重复启动会累积重复片段。重复实验前建议先清表：

  ```bash
  docker exec pgvector psql -U postgres -d ragdb -c "truncate vector_store;"
  ```

- **`initialize-schema: true`** 让应用启动时自动执行 `CREATE EXTENSION vector` 并建表，所以不需要手工建表。

- 这套代码在 `JDK 17 + Spring Boot 4.1.1 + Spring AI 2.0.1` 下编译通过。注意 Spring AI 的依赖坐标在 1.x → 2.0 之间改过名（检索增强器从 `spring-ai-advisors-vector-store` 改为 `spring-ai-vector-store-advisor`），Spring Boot 4 的 Web starter 也从 `spring-boot-starter-web` 改成了 `spring-boot-starter-webmvc`，照抄旧教程会编译失败。

---

## 待补充

- **20 题测试集与两组准确率对比**：用于量化「检索增强」相对「直接提问」的提升幅度，测试集正在整理中。
