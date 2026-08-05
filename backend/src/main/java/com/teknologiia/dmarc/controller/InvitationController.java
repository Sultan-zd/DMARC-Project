package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.invite.AcceptInvitationRequest;
import com.teknologiia.dmarc.dto.invite.InvitationPreview;
import com.teknologiia.dmarc.service.InvitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The invitee's side of joining a team — reachable without an account, since the
 * whole point is that they do not have one yet. The token is the credential.
 */
@RestController
@RequestMapping("/api/public/invitation")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @GetMapping("/{token}")
    public InvitationPreview preview(@PathVariable String token) {
        return invitationService.preview(token);
    }

    @PostMapping("/{token}/accept")
    public Map<String, String> accept(@PathVariable String token,
                                      @Valid @RequestBody AcceptInvitationRequest request) {
        String organization = invitationService.accept(token, request);
        return Map.of("detail", "You have joined " + organization + ". You can sign in now.",
                "organization", organization);
    }
}
