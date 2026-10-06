package com.knowledgeflow.users.credentials;

import org.springframework.context.ConfigurableApplicationContext;

/** Exposes the launcher's real boot (same arguments, same profile) to tests in other packages. */
public final class StaffAdminRecoveryLauncherTestBridge {

    private StaffAdminRecoveryLauncherTestBridge() {
    }

    public static ConfigurableApplicationContext boot(String... args) {
        return StaffAdminRecoveryLauncher.boot(args);
    }
}
