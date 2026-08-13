package com.tutor.chat.api;

import com.tutor.auth.AuthContext;
import com.tutor.chat.api.ChatController;
import com.tutor.chat.application.ChatService;
import com.tutor.chat.support.ChatRateLimiter;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void streamsFrontendConsumableSseLifecycle() throws Exception {
        AuthContext.set(7L);
        ChatService service = mock(ChatService.class);
        doAnswer(invocation -> {
            ChatService.TurnEvents events = invocation.getArgument(2);
            events.onMeta(7L, "trace-1");
            events.onStage("routing");
            events.onExpertDone("resume", "timeout", "专家执行超时");
            events.onCitations(List.of(new Evidence("skill:java", "skill", "skill|Java|基础", 0.9, null,
                    "https://docs.oracle.com/en/java/")));
            events.onToken("hello");
            events.onDone(9L, "hello", "pending", List.of());
            return null;
        }).when(service).turn(any(), anyString(), any(ChatService.TurnEvents.class), any(CancellationToken.class));
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
                .contains("\"phase\":\"expert_done\"", "\"expert\":\"resume\"", "\"status\":\"timeout\"")
                .contains("\"sid\":\"S1\"", "\"node_id\":\"skill:java\"", "\"source_url\":\"https://docs.oracle.com/en/java/\"")
                .contains("\"text\":\"hello\"", "\"seq\":0")
                .contains("\"citation_status\":\"pending\"")
                .contains("\"message_id\":9");
    }

    @Test
    void rejectsRequestWhenUserExceedsRateLimit() throws Exception {
        AuthContext.set(7L);
        ChatService service = mock(ChatService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, new ChatRateLimiter(1))).build();
        String body = "{\"message\":\"hello\"}";

        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted());
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
        verify(service).turn(any(), anyString(), any(ChatService.TurnEvents.class), any(CancellationToken.class));
        verifyNoMoreInteractions(service);
    }

    @Test
    void propagatesAuthenticatedUserIntoVirtualChatThread() throws Exception {
        AuthContext.set(42L);
        ChatService service = mock(ChatService.class);
        CompletableFuture<Long> seenUser = new CompletableFuture<>();
        doAnswer(invocation -> {
            seenUser.complete(AuthContext.currentUserId());
            invocation.<ChatService.TurnEvents>getArgument(2).onDone(9L, "done");
            return null;
        }).when(service).turn(any(), anyString(), any(ChatService.TurnEvents.class), any(CancellationToken.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, new ChatRateLimiter(20))).build();

        MvcResult initial = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(initial)).andExpect(status().isOk());

        assertThat(seenUser.get(2, TimeUnit.SECONDS)).isEqualTo(42L);
    }
}
