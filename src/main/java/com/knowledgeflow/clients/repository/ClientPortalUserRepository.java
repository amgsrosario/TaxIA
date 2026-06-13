package com.knowledgeflow.clients.repository;

import com.knowledgeflow.clients.entity.ClientPortalUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientPortalUserRepository extends JpaRepository<ClientPortalUser, UUID> {

    List<ClientPortalUser> findByClientIdAndOrganizationIdAndDeletedAtIsNull(UUID clientId, UUID organizationId);

    Optional<ClientPortalUser> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    boolean existsByClientIdAndEmailAndDeletedAtIsNull(UUID clientId, String email);

    Optional<ClientPortalUser> findByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(UUID organizationId, String email);
}
