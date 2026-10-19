package com.mentalcare.api;

import com.mentalcare.trend.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TrendController {

    private final TrendService trends;

    public TrendController(TrendService trends) {
        this.trends = trends;
    }

    @GetMapping("/api/trends")
    public List<TrendService.TrendPoint> trends(@RequestParam(defaultValue = "12") int limit) {
        return trends.recent(Math.min(Math.max(limit, 1), 104));
    }
}
