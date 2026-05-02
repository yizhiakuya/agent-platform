package com.agentplatform.auth.repository;

import com.agentplatform.auth.entity.RevokedJti;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedJtiRepository extends JpaRepository<RevokedJti, String> {
}
