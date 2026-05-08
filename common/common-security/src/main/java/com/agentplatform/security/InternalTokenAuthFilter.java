package com.agentplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class InternalTokenAuthFilter extends AbstractAuthFilter {

    private final String expectedToken;

    public InternalTokenAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        if (!InternalToken.isValid(expectedToken, req.getHeader(InternalToken.HEADER))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal token required");
            return;
        }
        chain.doFilter(req, res);
    }
}
