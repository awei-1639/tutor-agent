package com.tutor.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 贯穿一轮处理的上下文对象 (实现设计 4.1, 等价 LangGraph State)。
 * 节点 = Function<TurnContext, TurnContext>; 每节点执行后快照入 turn_traces。
 */
public class TurnContext {
    private String traceId;
    private Long conversationId;
    private Long userId;
    private Map<String, Object> profile = new HashMap<>();
    private Intent intent;
    private List<Evidence> evidences = new ArrayList<>();
    private Map<String, ExpertOutput> expertOutputs = new HashMap<>();
    private String clarifyQuestion;    // 仲裁分歧时置位

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Map<String, Object> getProfile() { return profile; }
    public void setProfile(Map<String, Object> profile) { this.profile = profile; }
    public Intent getIntent() { return intent; }
    public void setIntent(Intent intent) { this.intent = intent; }
    public List<Evidence> getEvidences() { return evidences; }
    public void setEvidences(List<Evidence> evidences) { this.evidences = evidences; }
    public Map<String, ExpertOutput> getExpertOutputs() { return expertOutputs; }
    public void setExpertOutputs(Map<String, ExpertOutput> expertOutputs) { this.expertOutputs = expertOutputs; }
    public String getClarifyQuestion() { return clarifyQuestion; }
    public void setClarifyQuestion(String clarifyQuestion) { this.clarifyQuestion = clarifyQuestion; }
}
