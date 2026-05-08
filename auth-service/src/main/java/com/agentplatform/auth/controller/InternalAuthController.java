package com.agentplatform.auth.controller;

import com.agentplatform.auth.service.JwtService;
import com.agentplatform.api.auth.VerifyRequest;
import com.agentplatform.api.auth.VerifyResponse;
import com.agentplatform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal endpoints — only the gateway is supposed to reach these. The gateway
 * never proxies {@code /internal/**} paths from public clients (its route table
 * does not include this prefix). Direct exposure would let any caller validate
 * arbitrary tokens, which is not catastrophic but is avoidable.
 */
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private final JwtService jwtService;

    public InternalAuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Verify a token (signature + issuer + expiration + jti revocation) and
     * return the decoded principal. Used by the gateway during the WS handshake
     * filter for device connections.
     */
    @PostMapping("/verify")
    public VerifyResponse verify(@RequestBody VerifyRequest req) {
        if (req == null || req.token() == null || req.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token required");
        }
        try {
            Principal p = jwtService.verifyAndCheckRevocation(req.token());
            return new VerifyResponse(true, p.type(), p.subject(), p.userId(), p.jti(), null);
        } catch (Exception e) {
            return new VerifyResponse(false, null, null, null, null, e.getMessage());
        }
    }
}
