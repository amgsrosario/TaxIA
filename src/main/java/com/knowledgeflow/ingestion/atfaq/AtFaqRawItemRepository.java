package com.knowledgeflow.ingestion.atfaq;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AtFaqRawItemRepository extends JpaRepository<AtFaqRawItem, UUID> {

    Optional<AtFaqRawItem> findByOrganizationIdAndSourceAuthorityAndOfficialFaqIdAndSupersededFalse(
            UUID organizationId, String sourceAuthority, String officialFaqId);

    List<AtFaqRawItem> findByOrganizationIdAndSupersededFalse(UUID organizationId);

    long countByOrganizationIdAndSupersededFalse(UUID organizationId);

    List<AtFaqRawItem> findByOrganizationIdAndSourceUrlAndSupersededFalse(
            UUID organizationId, String sourceUrl);
}
