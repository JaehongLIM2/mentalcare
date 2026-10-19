package com.mentalcare.api;

import com.mentalcare.counseling.HomeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    private final HomeService home;

    public HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/home")
    public HomeService.HomeView home() {
        return home.view();
    }
}
