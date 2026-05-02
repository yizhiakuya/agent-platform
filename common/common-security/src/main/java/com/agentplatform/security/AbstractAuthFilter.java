package com.agentplatform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Servlet {@link Filter} skeleton that narrows the request to {@code HttpServletRequest}.
 * Concrete filters override {@link #doFilterInternal} and don't have to do the cast themselves.
 */
public abstract class AbstractAuthFilter implements Filter {

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        doFilterInternal(req, res, chain);
    }

    protected abstract void doFilterInternal(HttpServletRequest req,
                                             HttpServletResponse res,
                                             FilterChain chain)
            throws ServletException, IOException;
}
