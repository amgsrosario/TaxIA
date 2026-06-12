package com.knowledgeflow.cases.mapper;

import com.knowledgeflow.cases.dto.KnowledgeCaseCommentResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseVersionResponse;
import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.entity.KnowledgeCaseComment;
import com.knowledgeflow.cases.entity.KnowledgeCaseVersion;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface KnowledgeCaseMapper {

    @Mapping(target = "organizationId", source = "organization.id")
    @Mapping(target = "clientId", source = "client.id")
    KnowledgeCaseResponse toResponse(KnowledgeCase knowledgeCase);

    @Mapping(target = "organizationId", source = "organization.id")
    @Mapping(target = "clientId", source = "client.id")
    KnowledgeCaseDetailResponse toDetailResponse(KnowledgeCase knowledgeCase);

    @Mapping(target = "knowledgeCaseId", source = "knowledgeCase.id")
    @Mapping(target = "createdByUserId", source = "createdByUser.id")
    KnowledgeCaseVersionResponse toVersionResponse(KnowledgeCaseVersion knowledgeCaseVersion);

    @Mapping(target = "knowledgeCaseId", source = "knowledgeCase.id")
    @Mapping(target = "createdByUserId", source = "createdByUser.id")
    KnowledgeCaseCommentResponse toCommentResponse(KnowledgeCaseComment comment);
}
