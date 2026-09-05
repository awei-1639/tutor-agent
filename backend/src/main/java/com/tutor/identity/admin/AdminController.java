package com.tutor.identity.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/users")
    public Map<String, Object> users(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listUsers(search, status, page, size);
    }

    @PostMapping("/users/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable long id) {
        service.disable(id);
    }

    @PostMapping("/users/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable long id) {
        service.restore(id);
    }

    @PostMapping("/users/{id}/soft-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@PathVariable long id) {
        service.softDelete(id);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return service.audit(limit);
    }
}
