package com.tutor.memory.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.Mem0Properties;
import com.tutor.memory.policy.MemoryAdmissionPolicy;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class Mem0ClientTest {
    @Test
    void dropsResultsWithoutVerifiableUserOwnership() {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"results":[
                  {"memory":"其他用户的记忆","user_id":"7"},
                  {"memory":"没有归属字段的记忆"},
                  {"id":"remote-uuid","memory":"当前用户的记忆","metadata":{"user_id":"42","memory_id":12}}
                ]}
                """);
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        Mem0Client client = new Mem0Client(
                new Mem0Properties(true, "https://mem0.example", "api-key", 1),
                new MemoryAdmissionPolicy(), http, new ObjectMapper());

        List<com.tutor.memory.local.EpisodeStore.Episode> result = client.search(42L, "Java", "trace");

        assertThat(result).extracting(com.tutor.memory.local.EpisodeStore.Episode::summary)
                .containsExactly("当前用户的记忆");
        assertThat(result.getFirst().id()).isEqualTo(12L);
        assertThat(result.getFirst().remoteMemoryId()).isEqualTo("remote-uuid");
    }

    @Test
    void acceptsEmptySuccessfulDeleteResponse() {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(response.body()).thenReturn("");
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        Mem0Client client = new Mem0Client(
                new Mem0Properties(true, "https://mem0.example", "api-key", 1),
                new MemoryAdmissionPolicy(), http, new ObjectMapper());

        client.deleteMemory("remote-uuid");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().method()).isEqualTo("DELETE");
        assertThat(request.getValue().uri().getPath()).isEqualTo("/v1/memories/remote-uuid/");
    }

    @Test
    void discoversAndDeletesRemoteCopyWhenUuidWasNotPreviouslyStored() {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> listResponse = mock(HttpResponse.class);
        when(listResponse.statusCode()).thenReturn(200);
        when(listResponse.body()).thenReturn("""
                {"count":2,"next":null,"results":[
                  {"id":"other-remote","metadata":{"user_id":"42","memory_id":99}},
                  {"id":"target-remote","metadata":{"user_id":"42","memory_id":12}}
                ]}
                """);
        HttpResponse<String> deleteResponse = mock(HttpResponse.class);
        when(deleteResponse.statusCode()).thenReturn(204);
        when(deleteResponse.body()).thenReturn("");
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(listResponse))
                .thenReturn(CompletableFuture.completedFuture(deleteResponse));

        Mem0Client client = new Mem0Client(
                new Mem0Properties(true, "https://mem0.example", "api-key", 1),
                new MemoryAdmissionPolicy(), http, new ObjectMapper());

        assertThat(client.deleteMemoryForLocalId(42L, 12L)).isTrue();

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).sendAsync(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().get(0).method()).isEqualTo("POST");
        assertThat(requests.getAllValues().get(0).uri().getPath()).isEqualTo("/v3/memories/");
        assertThat(requests.getAllValues().get(1).method()).isEqualTo("DELETE");
        assertThat(requests.getAllValues().get(1).uri().getPath()).isEqualTo("/v1/memories/target-remote/");
    }
}
