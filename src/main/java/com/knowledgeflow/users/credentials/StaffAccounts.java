package com.knowledgeflow.users.credentials;

import com.knowledgeflow.users.entity.User;
import java.util.regex.Pattern;

/** Classification helpers for staff accounts (ADR-006). */
public final class StaffAccounts {

    /** A real BCrypt hash; non-login service accounts carry a sentinel that is not one. */
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private StaffAccounts() {
    }

    /**
     * Non-login service accounts (e.g. the governed pilot publisher/rollback actors) are never
     * managed by the credential flows: their hash is a sentinel, not BCrypt.
     */
    public static boolean isServiceAccount(User user) {
        String hash = user.getPasswordHash();
        return hash == null || !BCRYPT_HASH.matcher(hash).matches();
    }
}
