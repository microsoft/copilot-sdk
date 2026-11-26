/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.copilot.CopilotExperimental;
import java.util.Objects;
import javax.annotation.processing.Generated;

/**
 * Input for a semantic session import.
 * <p>
 * Required inputs are constructor arguments. Optional inputs have fluent setters.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SessionImportRequest {
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();

    /** Complete semantic JSONL input. */
    @JsonProperty("jsonl")
    private final String jsonl;

    /** Input file path used to resolve relative attachments. */
    @JsonProperty("inputPath")
    private final String inputPath;

    /** Absolute fallback directory supplied by the caller. */
    @JsonProperty("defaultWorkingDirectory")
    private final String defaultWorkingDirectory;

    /** Explicit directory override. */
    @JsonProperty("workingDirectory")
    private String workingDirectory;

    /** Explicit session name override. */
    @JsonProperty("name")
    private String name;

    /** Validate without creating a session. */
    @JsonProperty("dryRun")
    private Boolean dryRun;

    /** Identifier of the application that owns the imported session. */
    @JsonProperty("clientName")
    private String clientName;

    /** Structured client kind used by runtime behavior gates. */
    @JsonProperty("clientKind")
    private String clientKind;

    /**
     * Creates a request with its required inputs.
     *
     * @param jsonl Complete semantic JSONL input.
     * @param inputPath Input file path used to resolve relative attachments.
     * @param defaultWorkingDirectory Absolute fallback directory supplied by the caller.
     */
    public SessionImportRequest(String jsonl, String inputPath, String defaultWorkingDirectory) {
        this.jsonl = Objects.requireNonNull(jsonl, "jsonl");
        this.inputPath = Objects.requireNonNull(inputPath, "inputPath");
        this.defaultWorkingDirectory = Objects.requireNonNull(defaultWorkingDirectory, "defaultWorkingDirectory");
    }

    /**
     * Returns the {@code jsonl} property.
     *
     * @return Complete semantic JSONL input.
     */
    public String getJsonl() {
        return jsonl;
    }

    /**
     * Returns the {@code inputPath} property.
     *
     * @return Input file path used to resolve relative attachments.
     */
    public String getInputPath() {
        return inputPath;
    }

    /**
     * Returns the {@code defaultWorkingDirectory} property.
     *
     * @return Absolute fallback directory supplied by the caller.
     */
    public String getDefaultWorkingDirectory() {
        return defaultWorkingDirectory;
    }

    /**
     * Returns the {@code workingDirectory} property.
     *
     * @return Explicit directory override.
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Returns the {@code name} property.
     *
     * @return Explicit session name override.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the {@code dryRun} property.
     *
     * @return Validate without creating a session.
     */
    public Boolean getDryRun() {
        return dryRun;
    }

    /**
     * Returns the {@code clientName} property.
     *
     * @return Identifier of the application that owns the imported session.
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * Returns the {@code clientKind} property.
     *
     * @return Structured client kind used by runtime behavior gates.
     */
    public String getClientKind() {
        return clientKind;
    }

    /**
     * Sets the {@code workingDirectory} property.
     *
     * @param value Explicit directory override.
     * @return this request
     */
    public SessionImportRequest setWorkingDirectory(String value) {
        this.workingDirectory = value;
        if (value == null) $explicitNulls.add("workingDirectory");
        else $explicitNulls.remove("workingDirectory");
        return this;
    }

    /**
     * Sets the {@code name} property.
     *
     * @param value Explicit session name override.
     * @return this request
     */
    public SessionImportRequest setName(String value) {
        this.name = value;
        if (value == null) $explicitNulls.add("name");
        else $explicitNulls.remove("name");
        return this;
    }

    /**
     * Sets the {@code dryRun} property.
     *
     * @param value Validate without creating a session.
     * @return this request
     */
    public SessionImportRequest setDryRun(Boolean value) {
        this.dryRun = value;
        if (value == null) $explicitNulls.add("dryRun");
        else $explicitNulls.remove("dryRun");
        return this;
    }

    /**
     * Sets the {@code clientName} property.
     *
     * @param value Identifier of the application that owns the imported session.
     * @return this request
     */
    public SessionImportRequest setClientName(String value) {
        this.clientName = value;
        if (value == null) $explicitNulls.add("clientName");
        else $explicitNulls.remove("clientName");
        return this;
    }

    /**
     * Sets the {@code clientKind} property.
     *
     * @param value Structured client kind used by runtime behavior gates.
     * @return this request
     */
    public SessionImportRequest setClientKind(String value) {
        this.clientKind = value;
        if (value == null) $explicitNulls.add("clientKind");
        else $explicitNulls.remove("clientKind");
        return this;
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    public java.util.Map<String, Object> $nullableProperties() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        if ($explicitNulls.contains("workingDirectory") && workingDirectory == null) values.put("workingDirectory", null);
        if ($explicitNulls.contains("name") && name == null) values.put("name", null);
        if ($explicitNulls.contains("dryRun") && dryRun == null) values.put("dryRun", null);
        if ($explicitNulls.contains("clientName") && clientName == null) values.put("clientName", null);
        if ($explicitNulls.contains("clientKind") && clientKind == null) values.put("clientKind", null);
        return values;
    }
}
