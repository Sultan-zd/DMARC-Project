package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.PasswordResetToken;
import com.teknologiia.dmarc.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Every token still outstanding for an account.
     *
     * <p>Used to spend the others the moment one is issued or redeemed. Without it,
     * a request made from a machine that was later lost leaves a working link behind
     * for the rest of its lifetime — and asking for a new link would not take the
     * old one away, which is exactly what a person doing so is trying to achieve.
     */
    List<PasswordResetToken> findByUserAndUsedAtIsNull(User user);
}
