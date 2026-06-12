package com.knowledgeflow.interactions.repository;

import com.knowledgeflow.interactions.entity.AssistedInteractionMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistedInteractionMessageRepository extends JpaRepository<AssistedInteractionMessage, UUID> {

    List<AssistedInteractionMessage> findByInteractionIdOrderByCreatedAtAsc(UUID interactionId);
}
