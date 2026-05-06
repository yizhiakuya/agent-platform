package com.agentplatform.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
