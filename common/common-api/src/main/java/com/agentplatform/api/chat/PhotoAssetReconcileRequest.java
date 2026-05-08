package com.agentplatform.api.chat;

import java.util.List;

/**
 * Current Android MediaStore image ids for a device-side photo index
 * reconciliation pass.
 */
public record PhotoAssetReconcileRequest(
        List<String> mediaStoreIds
) {}
