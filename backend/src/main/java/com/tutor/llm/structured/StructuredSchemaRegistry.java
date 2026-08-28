package com.tutor.llm.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.Map;

public final class StructuredSchemaRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<StructuredTask, Definition<?>> DEFINITIONS = Map.ofEntries(
            Map.entry(StructuredTask.COREFERENCE, definition(
                    "coreference-v1",
                    CoreferenceOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/coreference-v1",
                      "type":"object",
                      "additionalProperties":false,
                      "required":["resolved_query","resolved_to","confidence","needs_clarification"],
                      "properties":{
                        "resolved_query":{"type":"string","minLength":1,"maxLength":4000},
                        "resolved_to":{"type":["string","null"],"maxLength":200},
                        "confidence":{"type":"number","minimum":0,"maximum":1},
                        "needs_clarification":{"type":"boolean"}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.RETRIEVAL_JUDGE, definition(
                    "retrieval-judge-v1",
                    RetrievalJudgeOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/retrieval-judge-v1",
                      "type":"object",
                      "additionalProperties":false,
                      "required":["sufficient","followup_query","missing"],
                      "properties":{
                        "sufficient":{"type":"boolean"},
                        "followup_query":{"type":["string","null"],"maxLength":240},
                        "missing":{"type":["string","null"],"maxLength":500}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.ROUTER, definition(
                    "router-v1",
                    RouterOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/router-v1",
                      "type":"object",
                      "additionalProperties":false,
                      "required":["scope","intent","intents","alternative_intent","alternative_confidence","ambiguity_flags","retrieval_facets","retrieval_hint","confidence","reason_codes"],
                      "properties":{
                        "scope":{"type":"string","enum":["in_scope","out_of_scope","uncertain"]},
                        "intent":{"type":"string","enum":["resume","interview","planning","mixed","chat","out_of_scope"]},
                        "intents":{"type":"array","maxItems":5,"items":{"type":"string","enum":["resume","interview","planning","mixed","chat","out_of_scope"]}},
                        "alternative_intent":{"type":["string","null"],"maxLength":40},
                        "alternative_confidence":{"type":"number","minimum":0,"maximum":1},
                        "ambiguity_flags":{"type":"array","maxItems":10,"items":{"type":"string","maxLength":200}},
                        "retrieval_facets":{"type":"array","maxItems":3,"items":{"type":"string","enum":["career","learning","resource"]}},
                        "retrieval_hint":{"type":"string","enum":["none","single","multi_candidate"]},
                        "confidence":{"type":"number","minimum":0,"maximum":1},
                        "reason_codes":{"type":"array","maxItems":20,"items":{"type":"string","maxLength":100}}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.TOOL_CALL, definition(
                    "tool-loop-v1",
                    ToolLoopOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/tool-loop-v1",
                      "oneOf":[
                        {
                          "type":"object",
                          "additionalProperties":false,
                          "required":["type","tool","arguments"],
                          "properties":{
                            "type":{"const":"tool_call"},
                            "tool":{"type":"string","minLength":1,"maxLength":80},
                            "arguments":{"type":"object","maxProperties":50}
                          }
                        },
                        {
                          "type":"object",
                          "additionalProperties":false,
                          "required":["type","answer"],
                          "properties":{
                            "type":{"const":"final"},
                            "answer":{"type":"string","minLength":1,"maxLength":12000}
                          }
                        }
                      ]
                    }
                    """
            )),
            Map.entry(StructuredTask.INTERVIEW_QUESTION, definition(
                    "interview-question-v1",
                    InterviewQuestionOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/interview-question-v1",
                      "type":"object","additionalProperties":false,
                      "required":["question","required_points","bonus_points","critical_errors"],
                      "properties":{
                        "question":{"type":"string","minLength":1,"maxLength":160},
                        "required_points":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"string","minLength":1,"maxLength":500}},
                        "bonus_points":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":500}},
                        "critical_errors":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":500}}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.INTERVIEW_FOLLOW_UP, definition(
                    "interview-follow-up-v1",
                    InterviewFollowUpOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/interview-follow-up-v1",
                      "type":"object","additionalProperties":false,
                      "required":["follow_up"],
                      "properties":{"follow_up":{"type":"string","minLength":1,"maxLength":300}}
                    }
                    """
            )),
            Map.entry(StructuredTask.INTERVIEW_SCORECARD, definition(
                    "interview-scorecard-v1",
                    InterviewScorecardOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/interview-scorecard-v1",
                      "type":"object","additionalProperties":false,
                      "required":["score","strengths","missing_points","confidence","evidence_quotes"],
                      "properties":{
                        "score":{"type":"integer","minimum":0,"maximum":10},
                        "strengths":{"type":"array","maxItems":20,"items":{"type":"string","maxLength":500}},
                        "missing_points":{"type":"array","maxItems":20,"items":{"type":"string","maxLength":500}},
                        "confidence":{"type":"number","minimum":0,"maximum":1},
                        "evidence_quotes":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"string","minLength":1,"maxLength":500}}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.RESUME_EXTRACT, definition(
                    "resume-extract-v1",
                    ResumeExtractOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/resume-extract-v1",
                      "type":"object","additionalProperties":false,
                      "required":["education","experiences","projects","skills","summary"],
                      "properties":{
                        "education":{"type":"array","maxItems":20,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["school","degree","major","period"],
                          "properties":{"school":{"type":"string","maxLength":300},"degree":{"type":"string","maxLength":100},"major":{"type":"string","maxLength":200},"period":{"type":"string","maxLength":100}}
                        }},
                        "experiences":{"type":"array","maxItems":30,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["company","title","period","highlights"],
                          "properties":{"company":{"type":"string","maxLength":300},"title":{"type":"string","maxLength":200},"period":{"type":"string","maxLength":100},"highlights":{"type":"array","maxItems":30,"items":{"type":"string","maxLength":500}}}
                        }},
                        "projects":{"type":"array","maxItems":30,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["name","role","description","tech"],
                          "properties":{"name":{"type":"string","maxLength":300},"role":{"type":"string","maxLength":200},"description":{"type":"string","maxLength":1000},"tech":{"type":"array","maxItems":30,"items":{"type":"string","maxLength":100}}}
                        }},
                        "skills":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":100}},
                        "summary":{"type":"string","maxLength":200}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.PROFILE_EXTRACT, definition(
                    "profile-extract-v1",
                    ProfileExtractOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/profile-extract-v1",
                      "type":"object","additionalProperties":false,
                      "required":["skills","scalars","preferred_format"],
                      "properties":{
                        "skills":{"type":"array","maxItems":50,"items":{
                          "type":"object","additionalProperties":false,"required":["name","explicit"],
                          "properties":{"name":{"type":"string","minLength":1,"maxLength":120},"explicit":{"type":"boolean"}}
                        }},
                        "scalars":{"type":"object","additionalProperties":false,"required":["target_position","location","experience_years","education","daily_hours"],
                          "properties":{
                            "target_position":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/scalar"}]},
                            "location":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/scalar"}]},
                            "experience_years":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/scalar"}]},
                            "education":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/scalar"}]},
                            "daily_hours":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/scalar"}]}
                          }
                        },
                        "preferred_format":{"type":"array","maxItems":10,"items":{"type":"string","maxLength":80}}
                      },
                      "$defs":{"scalar":{"type":"object","additionalProperties":false,"required":["value","explicit"],"properties":{"value":{"type":"string","maxLength":300},"explicit":{"type":"boolean"}}}}
                    }
                    """
            )),
            Map.entry(StructuredTask.EXPERT, definition(
                    "expert-v1",
                    ExpertPayload.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/expert-v1",
                      "type":"object","additionalProperties":false,
                      "required":["confidence","citations"],
                      "properties":{
                        "advice":{"type":"array","maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["point","reason","priority"],"properties":{"point":{"type":"string","minLength":1,"maxLength":2000},"reason":{"type":"string","minLength":1,"maxLength":2000},"priority":{"type":"integer","minimum":1,"maximum":5}}}},
                        "questions":{"type":"array","maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["q","type","answer_points"],"properties":{"q":{"type":"string","minLength":1,"maxLength":2000},"type":{"type":"string","minLength":1,"maxLength":100},"answer_points":{"type":"string","minLength":1,"maxLength":2000}}}},
                        "weeks":{"type":"array","maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["week","goal","tasks","resources"],"properties":{"week":{"type":"integer","minimum":1,"maximum":8},"goal":{"type":"string","minLength":1,"maxLength":2000},"tasks":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":2000}},"resources":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":2000}}}}},
                        "match_score":{"type":["number","null"],"minimum":0,"maximum":1},
                        "confidence":{"type":"number","minimum":0,"maximum":1},
                        "citations":{"type":"array","maxItems":10,"items":{"type":"string","pattern":"^S[1-9][0-9]*$"}}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.PLAN, definition(
                    "plan-v1",
                    PlanOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/plan-v1",
                      "type":"object","additionalProperties":false,
                      "required":["goal_summary","days"],
                      "properties":{
                        "goal_summary":{"type":"string","minLength":1,"maxLength":300},
                        "days":{"type":"array","minItems":7,"maxItems":7,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["day","content","kind","related_skills","estimated_minutes"],
                          "properties":{
                            "day":{"type":"string","minLength":1,"maxLength":20},
                            "content":{"type":"string","minLength":1,"maxLength":500},
                            "kind":{"type":"string","enum":["learn","practice","review"]},
                            "related_skills":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":120}},
                            "estimated_minutes":{"type":"integer","minimum":5,"maximum":480}
                          }
                        }}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.CITATION_GUARD, definition(
                    "citation-guard-v1",
                    CitationGuardOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/citation-guard-v1",
                      "type":"object","additionalProperties":false,
                      "required":["claims","summary"],
                      "properties":{
                        "claims":{"type":"array","minItems":1,"maxItems":20,"items":{
                          "type":"object","additionalProperties":false,"required":["text","sid","verdict"],
                          "properties":{"text":{"type":"string","minLength":1,"maxLength":200},"sid":{"type":"string","pattern":"^S[1-9][0-9]*$"},"verdict":{"type":"string","enum":["supported","unsupported"]}}
                        }},
                        "summary":{"type":"string","maxLength":100}
                      }
                    }
                    """
            )),
            Map.entry(StructuredTask.SUMMARY_FOLDER, definition(
                    "summary-folder-v1",
                    SummaryOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/summary-folder-v1",
                      "type":"object","additionalProperties":false,
                      "required":["summary"],
                      "properties":{"summary":{"type":"string","minLength":1,"maxLength":300}}
                    }
                    """
            )),
            Map.entry(StructuredTask.EPISODE_SUMMARY, definition(
                    "episode-summary-v1",
                    EpisodeSummaryOutput.class,
                    """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "$id":"https://tutor.local/schema/episode-summary-v1",
                      "type":"object","additionalProperties":false,
                      "required":["summary","topics","open_items"],
                      "properties":{
                        "summary":{"type":"string","minLength":1,"maxLength":200},
                        "topics":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"string","minLength":1,"maxLength":100}},
                        "open_items":{"type":"array","maxItems":10,"items":{"type":"string","maxLength":300}}
                      }
                    }
                    """
            ))
    );

    private StructuredSchemaRegistry() {
    }

    public static Definition<?> get(StructuredTask task) {
        Definition<?> definition = DEFINITIONS.get(task);
        if (definition == null) throw new IllegalArgumentException("unknown structured task: " + task);
        return definition;
    }

    private static <T> Definition<T> definition(
            String id,
            Class<T> type,
            String schemaJson
    ) {
        try {
            JsonNode node = MAPPER.readTree(schemaJson);
            return new Definition<>(id, type, schemaJson, REGISTRY.getSchema(node));
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public record Definition<T>(
            String schemaId,
            Class<T> outputType,
            String schemaJson,
            Schema schema
    ) {
    }
}
