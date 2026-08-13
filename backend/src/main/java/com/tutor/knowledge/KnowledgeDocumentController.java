package com.tutor.knowledge;

import com.tutor.admin.AdminService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

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
                                                        @RequestParam(required = false) String title) {
        return documents.upload(admin.requireAdmin(), file, title);
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
