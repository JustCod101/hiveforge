package com.hiveforge.controller;

import com.hiveforge.repository.HiveTaskRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final HiveTaskRepository taskRepo;

    public DashboardController(HiveTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("tasks", taskRepo.findRecent(0, 20));
        return "dashboard";
    }
}
