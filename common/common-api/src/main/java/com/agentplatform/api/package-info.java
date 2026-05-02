/**
 * Cross-service DTOs and Feign interfaces. Modules are populated incrementally
 * by service PRs (auth-service, agent-service, chat-service, device-hub-service).
 *
 * <p>Stage-1 PRs that will add code here:
 * <ul>
 *   <li>PR 3 (auth-service): user/device DTOs, {@code AuthInternalClient} Feign interface.</li>
 *   <li>PR 7 (agent-service): SSE event DTOs, {@code DeviceHubInternalClient}, {@code ChatInternalClient}.</li>
 *   <li>PR 9 (chat-service): session/message DTOs.</li>
 * </ul>
 */
package com.agentplatform.api;
