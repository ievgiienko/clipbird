package com.secureclipboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards frontend SPA routes to index.html so that
 * client-side routing (History API) can handle them.
 */
@Controller
public class SpaController {

    @GetMapping({"/create", "/open", "/c/{code}", "/login", "/register", "/dashboard"})
    public String forward() {
        return "forward:/index.html";
    }
}
