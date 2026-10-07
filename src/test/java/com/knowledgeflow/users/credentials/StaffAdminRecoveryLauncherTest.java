package com.knowledgeflow.users.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-006: the break-glass switch is BREAK_GLASS_ENABLED=true for the execution, nothing else. */
class StaffAdminRecoveryLauncherTest {

    @Test
    void enabledOnlyWithExactTrue() {
        assertThat(StaffAdminRecoveryLauncher.breakGlassEnabled(Map.of("BREAK_GLASS_ENABLED", "true")::get)).isTrue();
        assertThat(StaffAdminRecoveryLauncher.breakGlassEnabled(Map.<String, String>of()::get)).isFalse();
        for (String value : new String[] {"TRUE", "1", "yes", " true", "false", ""}) {
            assertThat(StaffAdminRecoveryLauncher.breakGlassEnabled(Map.of("BREAK_GLASS_ENABLED", value)::get))
                    .as(value).isFalse();
        }
    }
}
