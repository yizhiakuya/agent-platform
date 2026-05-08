package com.agentplatform.hub.client;

import com.agentplatform.api.auth.VerifyRequest;
import com.agentplatform.api.auth.VerifyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "auth-service", contextId = "hubInternalAuth", path = "/internal/auth")
public interface AuthInternalClient {

    @PostMapping("/verify")
    VerifyResponse verify(@RequestBody VerifyRequest req);
}
