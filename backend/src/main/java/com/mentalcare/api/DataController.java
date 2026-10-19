package com.mentalcare.api;

import com.mentalcare.counseling.DataDeletionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Irreversible deletions. The UI asks for explicit confirmation before calling these. */
@RestController
public class DataController {

    public record DeleteAllRequest(String confirmation) {}

    private final DataDeletionService deletion;

    public DataController(DataDeletionService deletion) {
        this.deletion = deletion;
    }

    @DeleteMapping("/api/sessions/{id}")
    public DataDeletionService.Result deleteSession(@PathVariable long id) {
        return deletion.deleteSession(id);
    }

    /** Body must be {"confirmation":"DELETE"}. Settings and the ChatGPT connection are kept. */
    @DeleteMapping("/api/data")
    public DataDeletionService.Result deleteAll(@RequestBody(required = false) DeleteAllRequest request) {
        return deletion.deleteAll(request == null ? null : request.confirmation());
    }
}
