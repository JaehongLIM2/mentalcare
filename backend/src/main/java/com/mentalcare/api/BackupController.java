package com.mentalcare.api;

import com.mentalcare.backup.BackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Backup download and restore. Both are same-origin browser requests; the server never reads or writes
 * paths the user types. Restore is destructive and requires {@code confirmation=RESTORE} on top of the
 * confirmation dialog in the UI.
 */
@RestController
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    /** Streams a fresh backup zip. {@code credentials=true} also includes the encrypted login files. */
    @GetMapping("/api/backup")
    public ResponseEntity<StreamingResponseBody> download(@RequestParam(defaultValue = "false") boolean credentials)
            throws IOException {
        Path zip = backups.createForDownload(credentials);
        long size = Files.size(zip);
        StreamingResponseBody body = out -> {
            try {
                Files.copy(zip, out);
            } finally {
                Files.deleteIfExists(zip);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + backups.downloadFileName(credentials) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(body);
    }

    @PostMapping("/api/backup/inspect")
    public BackupService.Inspection inspect(@RequestParam("file") MultipartFile file) throws IOException {
        Path tmp = stash(file);
        try {
            return backups.inspect(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @PostMapping("/api/backup/restore")
    public BackupService.RestoreResult restore(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "confirmation", required = false) String confirmation,
                                               @RequestParam(value = "credentials", defaultValue = "false") boolean credentials)
            throws IOException {
        if (!"RESTORE".equals(confirmation)) {
            throw new IllegalArgumentException("confirmation must be \"RESTORE\"");
        }
        Path tmp = stash(file);
        try {
            return backups.restore(tmp, credentials);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Path stash(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("파일이 비어 있습니다.");
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("백업 파일(.zip)만 올릴 수 있습니다.");
        }
        Files.createDirectories(backups.backupsDir());
        Path tmp = Files.createTempFile(backups.backupsDir(), ".upload-", ".zip");
        file.transferTo(tmp);
        return tmp;
    }
}
