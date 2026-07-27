package com.tutor.contract;

import java.util.List;

/** 专家结构化输出的公共骨架 (实现设计 3.2: 附自评置信度与依据引用) */
public record ExpertOutput(
        String expert,              // resume / interview / planner
        String content,             // 结构化建议正文 (各专家 schema 序列化)
        double confidence,          // 0~1 自评置信度, 仲裁阈值 0.6
        List<String> citations      // 引用的知识节点 id
) {}
