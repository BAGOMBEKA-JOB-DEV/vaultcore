package com.vaultcore.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @GetMapping
    public StatusResponse status() {
        return new StatusResponse("vaultcore", "practice-api");
    }

    public record StatusResponse(String service, String stage) {
    }
}
