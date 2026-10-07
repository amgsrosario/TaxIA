package com.knowledgeflow.users.repository;

import com.knowledgeflow.users.entity.User;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    /**
     * Ids (not entities) of the non-deleted users whose email matches ignoring case — break-glass
     * refuses more than one. Ids only, so no User is managed before it is locked.
     */
    @Query("select u.id from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    List<UUID> findIdsByEmailIgnoreCase(@Param("email") String email);

    /** Current token_version of a user, without loading the entity. */
    @Query("select u.tokenVersion from User u where u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") UUID id);
}
