package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /** Accounts belonging to one organization — the only list an admin may see. */
    List<User> findByOrganizationIdOrderByCreatedAtAsc(Long organizationId);

    /** Scoped fetch, so an id from another organization matches nothing. */
    Optional<User> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Guards against removing or demoting the last administrator. */
    long countByOrganizationIdAndRoleIgnoreCaseAndActiveTrue(Long organizationId, String role);
}
