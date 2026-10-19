package com.mentalcare.api;

import com.mentalcare.ai.ModelCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelController {

    private final ModelCatalogService catalog;

    public ModelController(ModelCatalogService catalog) {
        this.catalog = catalog;
    }

    /** Available models for the current credential plus the effective selections. Never fails hard. */
    @GetMapping("/api/models")
    public ModelCatalogService.Catalog models(@RequestParam(defaultValue = "false") boolean refresh) {
        return catalog.catalog(refresh);
    }
}
