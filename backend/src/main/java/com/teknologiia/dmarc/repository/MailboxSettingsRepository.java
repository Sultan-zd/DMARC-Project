package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.MailboxSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailboxSettingsRepository extends JpaRepository<MailboxSettings, Long> {

    Optional<MailboxSettings> findByOrganizationId(Long organizationId);

    /** Every mailbox the scheduled collector should visit. */
    List<MailboxSettings> findByPollingEnabledTrue();

    void deleteByOrganizationId(Long organizationId);
}
