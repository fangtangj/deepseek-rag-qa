package com.ruanzixuan.deepseek_rag_qa.loader;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CourseDocLoader implements ApplicationRunner {

    private static final Path DOCS_DIR = Paths.get("src/main/resources/docs");

    /**
     * 阿里云百炼 text-embedding-v4 单次请求最多接受 10 条文本，
     * 超过会返回 400 InternalError.Algo.InvalidParameter: batch size is invalid。
     * Spring AI 默认把整个 List 一次发过去，所以必须自己按条数切批。
     */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public CourseDocLoader(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!Files.isDirectory(DOCS_DIR)) {
            System.out.println("[RAG] 找不到资料目录：" + DOCS_DIR.toAbsolutePath());
            return;
        }

        // ① 读文件，一份文件 = 一个 Document，文件名塞进 metadata
        List<Document> docs = new ArrayList<>();
        try (var stream = Files.list(DOCS_DIR)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".md")).sorted().toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                docs.add(new Document(text, Map.of("source", p.getFileName().toString())));
            }
        }
        if (docs.isEmpty()) {
            System.out.println("[RAG] docs 目录下没有 .md 文件");
            return;
        }

        // ② 切分：按 token 切，遇到中文标点尽量在句末断开
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(400)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .withPunctuationMarks(List.of('。', '？', '！', '；', '\n'))
                .build();

        List<Document> chunks = splitter.apply(docs);

        // ③ 先清空旧数据：避免每次启动重复追加，导致检索结果出现重复片段
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");

        // ④ 分批写库：每批最多 EMBEDDING_BATCH_SIZE 条，写库时自动调 embedding 转成向量
        for (int i = 0; i < chunks.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, chunks.size());
            vectorStore.add(chunks.subList(i, end));
        }

        int batches = (chunks.size() + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE;
        System.out.println("[RAG] 载入 " + docs.size() + " 份资料，切分为 " + chunks.size()
                + " 个片段，分 " + batches + " 批入库");
    }
}
