package com.tutor.chat;

import com.tutor.contract.Evidence;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {
    @Test
    void streamsFrontendConsumableSseLifecycle() throws Exception {
        ChatService service = mock(ChatService.class);
        doAnswer(invocation -> {
            ChatService.TurnEvents events = invocation.getArgument(2);
            events.onMeta(7L, "trace-1");
            events.onStage("routing");
            events.onCitations(List.of(new Evidence("skill:java", "skill", "skill|Java|基础", 0.9, null,
                    "https://docs.oracle.com/en/java/")));
            events.onToken("hello");
            events.onDone(9L, "hello");
            return null;
        }).when(service).turn(any(), anyString(), any(ChatService.TurnEvents.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, new ChatRateLimiter(20))).build();

        MvcResult initial = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"conversation_id\":7", "\"trace_id\":\"trace-1\"")
                .contains("\"phase\":\"routing\"")
                .contains("\"sid\":\"S1\"", "\"node_id\":\"skill:java\"", "\"source_url\":\"https://docs.oracle.com/en/java/\"")
                .contains("\"text\":\"hello\"", "\"seq\":0")
                .contains("\"message_id\":9");
    }

    @Test
    void rejectsRequestWhenUserExceedsRateLimit() throws Exception {
        ChatService service = mock(ChatService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, new ChatRateLimiter(1))).build();
        String body = "{\"message\":\"hello\"}";

        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted());
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
        verify(service).turn(any(), anyString(), any(ChatService.TurnEvents.class));
        verifyNoMoreInteractions(service);
    }
}
