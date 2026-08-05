package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    List<RecoveryCode> findByUserId(Long userId);

    /** How many ways back in remain. Shown so nobody discovers the answer is none. */
    long countByUserIdAndUsedAtIsNull(Long userId);

    void deleteByUserId(Long userId);
}
