package com.ruanzixuan.deepseek_rag_qa.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 课程答疑接口。
 * /course/qa       走检索增强（RAG），用于正式答疑
 * /course/qa/plain 不带检索直接问模型，专门用来做 20 题准确率对照
 */
@RestController
@RequestMapping("/course")
public class CourseQaController {

    private final ChatClient courseQaChatClient;  // 带检索
    private final ChatClient plainChatClient;     // 不带检索，对照组

    public CourseQaController(ChatClient.Builder builder,
                              @Qualifier("courseQaChatClient") ChatClient courseQaChatClient) {
        this.courseQaChatClient = courseQaChatClient;
        this.plainChatClient = builder.build();
    }

    /** 检索增强问答 */
    @PostMapping("/qa")
    public Map<String, String> qa(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        String answer = courseQaChatClient.prompt()
                .user(question)
                .call()
                .content();
        return Map.of("answer", answer);
    }

    /** 对照组：不带检索，直接问模型 */
    @PostMapping("/qa/plain")
    public Map<String, String> plain(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        String answer = plainChatClient.prompt()
                .user(question)
                .call()
                .content();
        return Map.of("answer", answer);
    }
}
