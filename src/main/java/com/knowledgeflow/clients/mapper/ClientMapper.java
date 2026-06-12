package com.knowledgeflow.clients.mapper;

import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientResponse;
import com.knowledgeflow.clients.entity.Client;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ClientMapper {

    @Mapping(target = "organizationId", source = "organization.id")
    ClientResponse toResponse(Client client);

    @Mapping(target = "organizationId", source = "organization.id")
    ClientDetailResponse toDetailResponse(Client client);
}
