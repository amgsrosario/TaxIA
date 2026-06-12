package com.knowledgeflow.cases.repository;

import com.knowledgeflow.cases.entity.KnowledgeCaseComment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeCaseCommentRepository extends JpaRepository<KnowledgeCaseComment, UUID> {

    List<KnowledgeCaseComment> findByKnowledgeCaseIdOrderByCreatedAtAsc(UUID knowledgeCaseId);
}
