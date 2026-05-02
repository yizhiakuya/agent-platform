package com.agentplatform.auth.service;

import com.agentplatform.auth.dto.LoginRequest;
import com.agentplatform.auth.dto.LoginResponse;
import com.agentplatform.auth.dto.RegisterRequest;
import com.agentplatform.auth.dto.RegisterResponse;
import com.agentplatform.auth.entity.User;
import com.agentplatform.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder pwEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder pwEncoder, JwtService jwtService) {
        this.users = users;
        this.pwEncoder = pwEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        if (users.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(pwEncoder.encode(req.password()));
        users.save(u);
        return new RegisterResponse(u.getId(), u.getUsername());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        User u = users.findByUsername(req.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!pwEncoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtService.issueUserToken(u.getId());
        return new LoginResponse(u.getId(), u.getUsername(), token);
    }
}
