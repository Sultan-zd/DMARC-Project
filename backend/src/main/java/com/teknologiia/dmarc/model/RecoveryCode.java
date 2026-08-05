package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single-use way back in when the authenticator app is gone.
 *
 * <p>Stored hashed, exactly like a password: a leaked database must not hand over a
 * working second factor. The plaintext is shown once, at enrolment, and never
 * again — which is also why {@code usedAt} matters more than deletion, so somebody
 * can see that a code was spent rather than wonder whether it ever existed.
 */
@Entity
@Table(name = "recovery_codes")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RecoveryCode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public boolean isSpent() {
        return usedAt != null;
    }
}
