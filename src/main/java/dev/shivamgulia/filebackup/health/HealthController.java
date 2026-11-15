package dev.shivamgulia.filebackup.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness + integrity report (HLD §3.4). Exempt from auth (clients probe it right after login).
 */
@RestController
public class HealthController {

    private final IntegrityChecker checker;

    public HealthController(IntegrityChecker checker) {
        this.checker = checker;
    }

    @GetMapping("/api/health")
    IntegrityChecker.HealthReport health() {
        return checker.check();
    }
}
