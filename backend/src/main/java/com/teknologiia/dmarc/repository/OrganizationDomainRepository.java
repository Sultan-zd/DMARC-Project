package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.OrganizationDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationDomainRepository extends JpaRepository<OrganizationDomain, Long> {

    /**
     * The organization that has proven ownership of this email domain, if any.
     * Only verified claims are considered — an unverified one grants nothing.
     */
    Optional<OrganizationDomain> findByDomainIgnoreCaseAndVerifiedAtIsNotNull(String domain);

    Optional<OrganizationDomain> findByDomainIgnoreCase(String domain);

    List<OrganizationDomain> findByOrganizationIdOrderByCreatedAtAsc(Long organizationId);

    Optional<OrganizationDomain> findByIdAndOrganizationId(Long id, Long organizationId);
}
