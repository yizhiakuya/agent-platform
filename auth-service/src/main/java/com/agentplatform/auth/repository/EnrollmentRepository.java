package com.agentplatform.auth.repository;

import com.agentplatform.auth.entity.Enrollment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, String> {

    /**
     * Pessimistic SELECT ... FOR UPDATE on the enrollment row. Required for
     * the redeem path: two concurrent requests with the same token would
     * otherwise both observe {@code usedAt = null} under READ_COMMITTED and
     * each succeed in creating a Device + signing a 365-day JWT.
     *
     * <p>With FOR UPDATE the second request blocks until the first commits,
     * then sees the freshly-set {@code usedAt} and rejects.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Enrollment e where e.tokenHash = :hash")
    Optional<Enrollment> findByTokenHashForUpdate(@Param("hash") String hash);
}
