package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByToken(String token);

    List<Invitation> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Invitation> findByIdAndOrganizationId(Long id, Long organizationId);

    /** An address already invited and still able to accept should not be invited twice. */
    Optional<Invitation> findByEmailIgnoreCaseAndAcceptedAtIsNull(String email);
}
