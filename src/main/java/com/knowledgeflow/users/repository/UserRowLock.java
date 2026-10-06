package com.knowledgeflow.users.repository;

import com.knowledgeflow.users.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write lock on a users row with a guaranteed fresh state (ADR-006).
 *
 * <p>A locking query does not refresh an entity that is already managed in the persistence
 * context, so a stale {@code token_version} read earlier in the same transaction would be written
 * back and a concurrent increment lost. {@code refresh(..., PESSIMISTIC_WRITE)} always issues
 * {@code SELECT ... FOR UPDATE} and overwrites the managed state. Every credential, status or
 * privilege change of a user goes through here.
 */
@Component
public class UserRowLock {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<User> lock(UUID userId) {
        User user = entityManager.find(User.class, userId);
        if (user == null) {
            return Optional.empty();
        }
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(user);
    }
}
