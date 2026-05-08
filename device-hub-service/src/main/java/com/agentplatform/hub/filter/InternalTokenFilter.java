package com.agentplatform.hub.filter;

import com.agentplatform.security.InternalTokenAuthFilter;

public class InternalTokenFilter extends InternalTokenAuthFilter {

    public InternalTokenFilter(String expectedToken) {
        super(expectedToken);
    }
}
