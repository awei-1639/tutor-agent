package com.tutor.knowledge.document;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class KnowledgeIngestionWorkerTest {
    @Test
    void completesPersistedJobAfterIngestion() {
        KnowledgeIngestionJobStore jobs = mock(KnowledgeIngestionJobStore.class);
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        var job = new KnowledgeIngestionJobStore.Job(UUID.randomUUID(), UUID.randomUUID(), 0L, 1, UUID.randomUUID());
        when(jobs.claimNext()).thenReturn(Optional.of(job));
        when(jobs.complete(job)).thenReturn(true);

        new KnowledgeIngestionWorker(jobs, documents).processOne();

        verify(documents).ingest(job);
        verify(jobs).complete(job);
    }

    @Test
    void keepsDocumentRetryableBeforeMaxAttempts() {
        KnowledgeIngestionJobStore jobs = mock(KnowledgeIngestionJobStore.class);
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        var job = new KnowledgeIngestionJobStore.Job(UUID.randomUUID(), UUID.randomUUID(), 4L, 2, UUID.randomUUID());
        when(jobs.claimNext()).thenReturn(Optional.of(job));
        when(jobs.failFenced(job, "parse failed")).thenReturn(false);
        doThrow(new IllegalStateException("parse failed")).when(documents).ingest(job);

        new KnowledgeIngestionWorker(jobs, documents).processOne();

        verify(jobs).failFenced(job, "parse failed");
        verify(jobs, never()).markDocumentFailed(any(), anyLong(), any());
    }

    @Test
    void marksCurrentGenerationFailedAfterMaxAttempts() {
        KnowledgeIngestionJobStore jobs = mock(KnowledgeIngestionJobStore.class);
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        var job = new KnowledgeIngestionJobStore.Job(UUID.randomUUID(), UUID.randomUUID(), 4L, 5, UUID.randomUUID());
        when(jobs.claimNext()).thenReturn(Optional.of(job));
        when(jobs.failFenced(job, "parse failed")).thenReturn(true);
        doThrow(new IllegalStateException("parse failed")).when(documents).ingest(job);

        new KnowledgeIngestionWorker(jobs, documents).processOne();

        verify(jobs).failFenced(job, "parse failed");
        verify(jobs).markDocumentFailed(job.documentId(), 4L, "parse failed");
    }
}
