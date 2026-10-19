package com.mentalcare.api;

import com.mentalcare.importing.ArchiveImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Imports a Markdown archive exported from another assistant. The file comes from the browser's
 * file picker (same-origin upload); the server never reads arbitrary paths.
 */
@RestController
public class ImportController {

    private final ArchiveImportService imports;

    public ImportController(ArchiveImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/imports/preview")
    public ArchiveImportService.Preview preview(@RequestParam("file") MultipartFile file) throws IOException {
        validate(file);
        return imports.preview(file.getBytes());
    }

    @PostMapping("/api/imports")
    public ArchiveImportService.Result importArchive(@RequestParam("file") MultipartFile file) throws IOException {
        validate(file);
        String name = file.getOriginalFilename() == null ? "archive.md" : file.getOriginalFilename();
        return imports.importArchive(name, file.getBytes());
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("파일이 비어 있습니다.");
        if (file.getSize() > ArchiveImportService.MAX_BYTES) throw new IllegalArgumentException("파일이 너무 큽니다 (최대 2MB).");
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".md") && !name.toLowerCase().endsWith(".txt")) {
            throw new IllegalArgumentException("Markdown(.md) 또는 텍스트(.txt) 파일만 가져올 수 있습니다.");
        }
    }
}
