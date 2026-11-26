/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import java.util.Map;

/**
 * Typed view of a {@code url} permission prompt.
 * <p>
 * Java represents the derived prompt as a {@link PermissionRequest}, which
 * keeps URL-specific fields in {@link PermissionRequest#getExtensionData()}.
 * This view reads them with their types so a handler can offer the scopes the
 * runtime supports:
 *
 * <pre>{@code
 * PermissionHandler handler = (request, invocation) -> {
 * 	var prompt = UrlPermissionPrompt.from(invocation.getPromptRequest());
 * 	if (prompt != null && prompt.getParentDomainPattern() != null && userApprovedWholeDomain(prompt)) {
 * 		return CompletableFuture
 * 				.completedFuture(PermissionRequestResult.approveUrlForSession(prompt.getParentDomainPattern()));
 * 	}
 * 	// Leave everything else for another client or a person to answer.
 * 	return CompletableFuture.completedFuture(PermissionRequestResult.noResult());
 * };
 * }</pre>
 *
 * A field holding a value of the wrong JSON type reads as {@code null}.
 *
 * @see PermissionInvocation#getPromptRequest()
 * @see PermissionRequestResult#approveUrlForSession(String)
 */
public final class UrlPermissionPrompt {

    private static final String KIND = "url";

    private final PermissionRequest request;

    private UrlPermissionPrompt(PermissionRequest request) {
        this.request = request;
    }

    /**
     * Returns the typed URL view of a permission prompt.
     *
     * @param request
     *            the prompt, usually
     *            {@link PermissionInvocation#getPromptRequest()}
     * @return the URL view, or {@code null} when {@code request} is {@code null} or
     *         is not a {@code url} prompt
     */
    public static UrlPermissionPrompt from(PermissionRequest request) {
        if (request == null || !KIND.equals(request.getKind())) {
            return null;
        }
        return new UrlPermissionPrompt(request);
    }

    /**
     * Gets the URL being accessed.
     *
     * @return the URL, or {@code null} when absent
     */
    public String getUrl() {
        return string("url");
    }

    /**
     * Gets the human-readable description of why the URL is being accessed.
     *
     * @return the intention, or {@code null} when absent
     */
    public String getIntention() {
        return string("intention");
    }

    /**
     * Gets the immediately preceding URL when this prompt is for a redirect target.
     *
     * @return the URL that redirected here, or {@code null} when this prompt is not
     *         for a redirect
     */
    public String getRedirectedFrom() {
        return string("redirectedFrom");
    }

    /**
     * Gets the wildcard pattern covering the URL's registrable domain and its
     * subdomains, such as {@code https://*.example.com} for
     * {@code https://api.example.com/x}.
     * <p>
     * The runtime matches it by registrable domain, so it does not cover subdomains
     * the Public Suffix List lists as separate owners, such as buckets under
     * {@code s3.amazonaws.com}; those still prompt.
     * <p>
     * A host may offer it as a broader alternative to approving the exact origin
     * and, when the user chooses it, answer with
     * {@link PermissionRequestResult#approveUrlForSession(String)}. The scope is
     * session-only; do not offer it as a permanent approval.
     *
     * @return the parent-domain pattern, or {@code null} when the runtime cannot
     *         offer one safely (IP addresses, private-network names, public
     *         suffixes and sandbox-bypass requests)
     */
    public String getParentDomainPattern() {
        return string("parentDomainPattern");
    }

    /**
     * Gets whether the tool asks to run this fetch outside the sandbox. Hosts
     * should highlight the elevated risk when this is {@code true}.
     *
     * @return {@code true} for a sandbox-bypass request, otherwise {@code false} or
     *         {@code null}
     */
    public Boolean getRequestSandboxBypass() {
        Object value = value("requestSandboxBypass");
        return value instanceof Boolean flag ? flag : null;
    }

    /**
     * Gets what the tool tells the user about the sandbox bypass on offer.
     *
     * @return the bypass reason, or {@code null} when absent
     */
    public String getRequestSandboxBypassReason() {
        return string("requestSandboxBypassReason");
    }

    private String string(String key) {
        Object value = value(key);
        return value instanceof String text ? text : null;
    }

    private Object value(String key) {
        Map<String, Object> extensionData = request.getExtensionData();
        return extensionData == null ? null : extensionData.get(key);
    }
}
