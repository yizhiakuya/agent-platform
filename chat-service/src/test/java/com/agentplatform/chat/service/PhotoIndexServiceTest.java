package com.agentplatform.chat.service;

import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class PhotoIndexServiceTest {

    @Test
    void semanticThenSortThresholdKeepsOnlyCandidatesNearBestSemanticHit() {
        double threshold = PhotoIndexService.qualifyingScoreThreshold(0.2696d, 0.1d);

        assertEquals(0.21568d, threshold, 0.00001d);
    }

    @Test
    void explicitMinScoreStillActsAsFloorForQualifiedCandidates() {
        double threshold = PhotoIndexService.qualifyingScoreThreshold(0.22d, 0.2d);

        assertEquals(0.2d, threshold, 0.00001d);
    }

    @Test
    void searchUsesStaticSqlWithBoundFiltersAndSortSwitches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PhotoIndexService service = new PhotoIndexService(jdbc);
        UUID userId = UUID.randomUUID();

        service.search(new PhotoAssetSearchRequest(
                userId,
                new float[] {0.1f, 0.2f},
                12,
                " camera ",
                " Cat ",
                10L,
                20L,
                0.15d,
                5,
                "semantic_then_sort",
                "name",
                "asc"));

        Object[] invocationArgs = mockingDetails(jdbc).getInvocations().iterator().next().getArguments();
        String sql = (String) invocationArgs[0];
        assertThat(sql)
                .contains("(?::text IS NULL OR pa.bucket_id = ?::text)")
                .contains("CASE WHEN ?::boolean THEN name END ASC")
                .doesNotContain("%s");
        assertThat(countPlaceholders(sql)).isEqualTo(invocationArgs.length - 2);
        assertThat(invocationArgs).contains("[0.1,0.2]", userId, "camera", "%cat%", 10L, 20L, 0.15d);
        assertThat(invocationArgs).doesNotContain(" Cat ");
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
