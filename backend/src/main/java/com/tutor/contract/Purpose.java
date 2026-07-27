package com.tutor.contract;

/** LLM 网关调用用途标签, 记账与分级超时/模型路由的键 (实现设计 6.1/6.4) */
public enum Purpose {
    CHAT, ROUTER, EXPERT, SUMMARY, EXTRACT, JUDGE, EMBED, RERANK, PLAN
}
