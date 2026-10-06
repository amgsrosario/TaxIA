package com.knowledgeflow.users.entity;

import com.knowledgeflow.users.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 160)
    private String fullName;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    /**
     * Monotonic session counter carried in the staff JWT claim {@code tv} (ADR-006). Every token
     * whose {@code tv} differs from this value is rejected on the next request; the governed
     * operations below only ever increment it, so an old token can never become valid again.
     */
    @Column(nullable = false)
    private int tokenVersion;

    /** Set by an ADMIN reset or break-glass recovery: the session is restricted to the password change. */
    @Column(nullable = false)
    private boolean mustChangePassword;

    protected User() {
    }

    public User(String email, String fullName, String passwordHash) {
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    // -------------------------------------------------------------------------
    // Governed credential and session operations (ADR-006). There is no raw password
    // setter: every path that replaces the hash also invalidates all existing sessions.
    // Callers must hold a fresh write lock on the row (UserRowLock#lock).
    // -------------------------------------------------------------------------

    /** Own password change: final password, clears the forced-change flag, ends every session. */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = requireHash(newPasswordHash);
        this.mustChangePassword = false;
        incrementTokenVersion();
    }

    /** ADMIN reset or break-glass: temporary password that must be changed at the next login. */
    public void resetPassword(String temporaryPasswordHash) {
        this.passwordHash = requireHash(temporaryPasswordHash);
        this.mustChangePassword = true;
        incrementTokenVersion();
    }

    /** Ends every session of this user (logout-all, ADMIN revoke, privilege change). */
    public void revokeSessions() {
        incrementTokenVersion();
    }

    public void disable() {
        if (status == UserStatus.DISABLED) {
            throw new IllegalStateException("User is already disabled");
        }
        this.status = UserStatus.DISABLED;
        incrementTokenVersion();
    }

    /**
     * Reactivation never restores an earlier token version: it increments it, so tokens issued
     * before the disable (or before any out-of-band status change) stay invalid and a new login
     * is required.
     */
    public void reactivate() {
        if (status == UserStatus.ACTIVE) {
            throw new IllegalStateException("User is already active");
        }
        this.status = UserStatus.ACTIVE;
        incrementTokenVersion();
    }

    public void softDelete() {
        if (deletedAt == null) {
            this.deletedAt = OffsetDateTime.now();
        }
        incrementTokenVersion();
    }

    public void incrementTokenVersion() {
        if (tokenVersion == Integer.MAX_VALUE) {
            // Never wrap around: a wrapped counter could re-validate an old token.
            throw new IllegalStateException("token_version exhausted");
        }
        tokenVersion++;
    }

    private static String requireHash(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("password hash is required");
        }
        return hash;
    }
}
