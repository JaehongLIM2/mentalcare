package com.mentalcare.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** When the built frontend is bundled into the jar, client-side routes must serve index.html. */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/new", "/session/**", "/history/**", "/memory", "/trends", "/settings"})
    public String forward() {
        return "forward:/index.html";
    }
}
