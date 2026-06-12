package com.knowledgeflow.users.repository;

import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.enums.RoleName;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(RoleName name);
}
