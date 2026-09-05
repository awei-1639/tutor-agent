package com.tutor.knowledge.document;

import com.tutor.identity.admin.AdminService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/documents")
public class KnowledgeDocumentController {
    private final AdminService admin;
    private final KnowledgeDocumentService documents;

    public KnowledgeDocumentController(AdminService admin, KnowledgeDocumentService documents) {
        this.admin = admin;
        this.documents = documents;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocumentService.UploadResult upload(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(required = false) String title,
                                                        @RequestParam(required = false) String resourceKind) {
        return documents.upload(admin.requireAdmin(), file, title, resourceKind);
    }

    public record UploadSessionRequest(String filename, long sizeBytes, String contentType, String title,
                                       String resourceKind) {}

    @PostMapping(path = "/upload-session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeDocumentService.UploadSession prepareUpload(@RequestBody UploadSessionRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传参数不能为空");
        return documents.prepareUpload(admin.requireAdmin(), request.filename(), request.sizeBytes(),
                request.contentType(), request.title(), request.resourceKind());
    }

    @GetMapping("/{id}/upload-session")
    public KnowledgeDocumentService.UploadSession resumeUpload(@PathVariable UUID id) {
        return documents.resumeUpload(admin.requireAdmin(), id);
    }

    public record CompletedPartRequest(int partNumber, String etag) {}
    public record CompleteUploadRequest(List<CompletedPartRequest> parts) {}

    @PostMapping("/{id}/complete")
    public KnowledgeDocumentService.UploadResult completeUpload(@PathVariable UUID id,
                                                                 @RequestBody(required = false) CompleteUploadRequest request) {
        List<KnowledgeDocumentService.CompletedPart> parts = request == null || request.parts() == null
                ? List.of()
                : request.parts().stream().map(part -> new KnowledgeDocumentService.CompletedPart(part.partNumber(), part.etag())).toList();
        return documents.completeUpload(admin.requireAdmin(), id, parts);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
        admin.requireAdmin();
        return documents.list(limit);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID id) {
        documents.retry(admin.requireAdmin(), id);
    }

    @PostMapping("/{id}/soft-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@PathVariable UUID id) {
        documents.softDelete(admin.requireAdmin(), id);
    }
}
