/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.copilot.generated.rpc.PermissionDecisionContext;

/**
 * Result of a permission request decision.
 * <p>
 * This object indicates whether a permission request was approved or denied,
 * and may include additional rules for future similar requests.
 *
 * <h2>Common Result Kinds</h2>
 * <ul>
 * <li>{@link PermissionRequestResultKind#APPROVED} — approved</li>
 * <li>{@link PermissionRequestResultKind#USER_NOT_AVAILABLE} — no handler and
 * couldn't ask user</li>
 * <li>{@link PermissionRequestResultKind#REJECTED} — denied by the user
 * interactively</li>
 * </ul>
 *
 * @see PermissionHandler
 * @see PermissionRequestResultKind
 * @since 1.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PermissionRequestResult {

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("rules")
    private List<Object> rules;

    @JsonProperty("feedback")
    private String feedback;

    @JsonProperty("directories")
    private List<String> directories;

    @JsonProperty("domain")
    private String domain;

    /**
     * Optional provenance describing how and where this decision was made. Never
     * serialized inside the result — the SDK forwards it as a sibling of
     * {@code result} so the runtime can attribute {@code auto_approval_decision}
     * telemetry.
     */
    @JsonIgnore
    private PermissionDecisionContext decisionContext;

    /**
     * Creates a result that approves this single request.
     *
     * @return a new approved result
     * @since 1.3.0
     */
    public static PermissionRequestResult approveOnce() {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVED);
    }

    /**
     * Creates a result that approves read access to the selected directories for
     * the rest of the session.
     *
     * @param directories
     *            the canonical directory candidates selected from the permission
     *            request
     * @return a new session-scoped read-only result
     */
    public static PermissionRequestResult approveReadOnlyForSession(List<String> directories) {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVE_READ_ONLY_FOR_SESSION)
                .setDirectories(directories);
    }

    /**
     * Creates a result that approves a URL prompt and remembers the approval for
     * the rest of the session.
     * <p>
     * Pass the prompt's exact origin, such as {@code https://api.example.com}, to
     * approve that origin only, or the prompt's
     * {@link UrlPermissionPrompt#getParentDomainPattern() parentDomainPattern} to
     * approve its registrable domain and subdomains. The parent-domain scope does
     * not cover subdomains the Public Suffix List lists as separate owners, such as
     * buckets under {@code s3.amazonaws.com}; those still prompt. The runtime
     * requires a domain for a URL session approval.
     *
     * @param domain
     *            the URL pattern to approve for the session
     * @return a new session-scoped URL approval
     * @throws NullPointerException
     *             if {@code domain} is {@code null}
     */
    public static PermissionRequestResult approveUrlForSession(String domain) {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVE_FOR_SESSION)
                .setDomain(Objects.requireNonNull(domain, "domain"));
    }

    /**
     * Creates a result that approves a URL domain permanently, across sessions.
     * <p>
     * Do not pass the prompt's {@link UrlPermissionPrompt#getParentDomainPattern()
     * parentDomainPattern}: the parent-domain scope is session-only, so the runtime
     * keeps it for the session and writes nothing to settings.
     *
     * @param domain
     *            the URL pattern to approve permanently
     * @return a new permanent URL approval
     * @throws NullPointerException
     *             if {@code domain} is {@code null}
     */
    public static PermissionRequestResult approveUrlPermanently(String domain) {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVE_PERMANENTLY)
                .setDomain(Objects.requireNonNull(domain, "domain"));
    }

    /**
     * Creates a result that rejects the request, optionally forwarding feedback to
     * the LLM.
     *
     * @param feedback
     *            optional feedback message, or {@code null}
     * @return a new rejected result
     * @since 1.3.0
     */
    public static PermissionRequestResult reject(String feedback) {
        var result = new PermissionRequestResult().setKind(PermissionRequestResultKind.REJECTED);
        result.setFeedback(feedback);
        return result;
    }

    /**
     * Creates a result denying the request because no user is available to confirm
     * it.
     *
     * @return a new user-not-available result
     * @since 1.3.0
     */
    public static PermissionRequestResult userNotAvailable() {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.USER_NOT_AVAILABLE);
    }

    /**
     * Creates a result that declines to respond to this permission request,
     * allowing another connected client to answer instead.
     *
     * @return a new no-result result
     * @since 1.3.0
     */
    public static PermissionRequestResult noResult() {
        return new PermissionRequestResult().setKind(PermissionRequestResultKind.NO_RESULT);
    }

    /**
     * Gets the result kind as a string.
     *
     * @return the result kind indicating approval or denial
     */
    public String getKind() {
        return kind;
    }

    /**
     * Sets the result kind using a {@link PermissionRequestResultKind} value.
     *
     * @param kind
     *            the result kind
     * @return this result for method chaining
     * @since 1.1.0
     */
    public PermissionRequestResult setKind(PermissionRequestResultKind kind) {
        this.kind = kind != null ? kind.getValue() : null;
        return this;
    }

    /**
     * Sets the result kind using a raw string value.
     *
     * @param kind
     *            the result kind string
     * @return this result for method chaining
     */
    public PermissionRequestResult setKind(String kind) {
        this.kind = kind;
        return this;
    }

    /**
     * Gets the approval rules.
     *
     * @return the list of rules for future similar requests
     */
    public List<Object> getRules() {
        return rules;
    }

    /**
     * Sets approval rules for future similar requests.
     *
     * @param rules
     *            the list of rules
     * @return this result for method chaining
     */
    public PermissionRequestResult setRules(List<Object> rules) {
        this.rules = rules;
        return this;
    }

    /**
     * Gets optional human-readable feedback to forward to the LLM along with the
     * decision.
     *
     * @return the feedback message, or {@code null}
     * @since 1.3.0
     */
    public String getFeedback() {
        return feedback;
    }

    /**
     * Sets optional human-readable feedback to forward to the LLM along with the
     * decision.
     *
     * @param feedback
     *            the feedback message
     * @return this result for method chaining
     * @since 1.3.0
     */
    public PermissionRequestResult setFeedback(String feedback) {
        this.feedback = feedback;
        return this;
    }

    /**
     * Gets the directories selected for a read-only approval.
     *
     * @return the selected canonical directories, or {@code null}
     */
    public List<String> getDirectories() {
        return directories;
    }

    /**
     * Sets the directories selected for a read-only approval.
     *
     * @param directories
     *            the canonical directory candidates selected from the permission
     *            request
     * @return this result for method chaining
     */
    public PermissionRequestResult setDirectories(List<String> directories) {
        this.directories = directories;
        return this;
    }

    /**
     * Gets the URL domain approved by an {@code approve-for-session} or
     * {@code approve-permanently} URL decision.
     *
     * @return the approved URL pattern, or {@code null}
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Sets the URL domain approved by an {@code approve-for-session} or
     * {@code approve-permanently} URL decision.
     *
     * @param domain
     *            the URL pattern to approve
     * @return this result for method chaining
     */
    public PermissionRequestResult setDomain(String domain) {
        this.domain = domain;
        return this;
    }

    /**
     * Gets the optional provenance describing how and where this decision was made.
     * <p>
     * This value is never serialized inside the result JSON; the SDK forwards it as
     * a sibling of {@code result} when responding to the runtime.
     *
     * @return the decision context, or {@code null} if none was attached
     * @since 1.3.0
     */
    public PermissionDecisionContext getDecisionContext() {
        return decisionContext;
    }

    /**
     * Sets provenance describing how and where this decision was made, so the
     * runtime can attribute {@code auto_approval_decision} telemetry. When it says
     * a person decided ({@code source} {@code human_response}), the runtime records
     * the decision as the person's own, but only from the session's authenticated
     * owning connection; from any other caller the decision applies without that
     * authority.
     * <p>
     * Calling this method more than once replaces any previously set context. The
     * context is never serialized inside the result; the SDK forwards it as a
     * sibling of {@code result}.
     *
     * @param decisionContext
     *            the decision context, or {@code null} to attach none
     * @return this result for method chaining
     * @since 1.3.0
     */
    public PermissionRequestResult setDecisionContext(PermissionDecisionContext decisionContext) {
        this.decisionContext = decisionContext;
        return this;
    }
}
