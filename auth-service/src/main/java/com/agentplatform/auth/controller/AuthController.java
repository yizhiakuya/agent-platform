package com.agentplatform.auth.controller;

import com.agentplatform.auth.dto.LoginRequest;
import com.agentplatform.auth.dto.LoginResponse;
import com.agentplatform.auth.dto.RedeemRequest;
import com.agentplatform.auth.dto.RedeemResponse;
import com.agentplatform.auth.dto.RegisterRequest;
import com.agentplatform.auth.dto.RegisterResponse;
import com.agentplatform.auth.service.AuthService;
import com.agentplatform.auth.service.EnrollmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public auth endpoints — no JWT required.
 * Mounted at {@code /api/auth/**}, which is excluded from {@code PathBasedJwtFilter}'s
 * protected prefixes (so anonymous requests pass through).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final EnrollmentService enrollmentService;

    public AuthController(AuthService authService, EnrollmentService enrollmentService) {
        this.authService = authService;
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /**
     * Device redeems a one-time enrollment token for a long-lived device JWT.
     * Public — Android calls this without prior auth, holding only the token from the QR.
     */
    @PostMapping("/redeem/{token}")
    public RedeemResponse redeem(@PathVariable String token, @Valid @RequestBody RedeemRequest req) {
        return enrollmentService.redeem(token, req);
    }
}
