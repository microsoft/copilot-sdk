/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SchemaGenerator} using the compilation-testing approach. A
 * test annotation processor exercises SchemaGenerator during compilation of
 * small source snippets.
 */
public class SchemaGeneratorTest {

    /**
     * In-memory Java source file for compilation testing.
     */
    private static class InMemorySource extends SimpleJavaFileObject {

        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return code;
        }
    }

    /**
     * Test processor that captures schema generation results.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static class SchemaCapturingProcessor extends AbstractProcessor {

        static final List<String> capturedSchemas = new ArrayList<>();
        static final List<String> capturedParameterSchemas = new ArrayList<>();

        private Types typeUtils;
        private Elements elementUtils;

        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(processingEnv);
            this.typeUtils = processingEnv.getTypeUtils();
            this.elementUtils = processingEnv.getElementUtils();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }

            SchemaGenerator generator = new SchemaGenerator();

            for (Element rootElement : roundEnv.getRootElements()) {
                if (rootElement.getKind() == ElementKind.CLASS || rootElement.getKind() == ElementKind.RECORD
                        || rootElement.getKind() == ElementKind.INTERFACE
                        || rootElement.getKind() == ElementKind.ENUM) {
                    // Find methods named "schemaTarget" to capture schemas for their return type
                    for (Element enclosed : rootElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.METHOD) {
                            ExecutableElement method = (ExecutableElement) enclosed;
                            String methodName = method.getSimpleName().toString();
                            if (methodName.startsWith("schemaTarget")) {
                                TypeMirror returnType = method.getReturnType();
                                String schema = generator.generateSchemaSource(returnType, typeUtils, elementUtils);
                                capturedSchemas.add(methodName + "=" + schema);
                            }
                            if ("parametersTarget".equals(methodName)) {
                                List<? extends VariableElement> params = method.getParameters();
                                String schema = generator.generateParametersSchemaSource(params, typeUtils,
                                        elementUtils);
                                capturedParameterSchemas.add(schema);
                            }
                        }
                    }

                    // For record/enum types, generate schema for the type itself
                    TypeElement typeElement = (TypeElement) rootElement;
                    String typeName = typeElement.getSimpleName().toString();
                    if (typeName.startsWith("TestRecord") || typeName.startsWith("TestEnum")
                            || typeName.startsWith("TestSealed")) {
                        String schema = generator.generateSchemaSource(typeElement.asType(), typeUtils, elementUtils);
                        capturedSchemas.add(typeName + "=" + schema);
                    }
                }
            }

            return false;
        }
    }

    private static final Path CLASS_OUTPUT_DIR = Path.of("target", "test-schema-classes");

    /**
     * Creates a StandardJavaFileManager that writes compiled .class files to
     * target/test-schema-classes/ instead of the working directory.
     */
    private StandardJavaFileManager createFileManager(JavaCompiler compiler,
            DiagnosticCollector<JavaFileObject> diagnostics) throws IOException {
        Files.createDirectories(CLASS_OUTPUT_DIR);
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(CLASS_OUTPUT_DIR.toFile()));
        return fm;
    }

    private List<String> compileAndCapture(String... sources) {
        return compileAndCapture(Arrays.asList(sources));
    }

    private List<String> compileAndCapture(List<String> sourceTexts) {
        SchemaCapturingProcessor.capturedSchemas.clear();
        SchemaCapturingProcessor.capturedParameterSchemas.clear();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler not available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (String sourceText : sourceTexts) {
            // Extract class name from source
            String className = extractClassName(sourceText);
            compilationUnits.add(new InMemorySource(className, sourceText));
        }

        try (StandardJavaFileManager fm = createFileManager(compiler, diagnostics)) {
            // Compile with the processor on classpath
            JavaCompiler.CompilationTask task = compiler.getTask(null, // writer
                    fm, // file manager
                    diagnostics, // diagnostics
                    List.of("--add-modules", "ALL-MODULE-PATH"), // options
                    null, // annotation classes
                    compilationUnits);

            task.setProcessors(List.of(new SchemaCapturingProcessor()));
            boolean success = task.call();

            if (!success) {
                // Try without module options for simpler environments
                diagnostics = new DiagnosticCollector<>();
                try (StandardJavaFileManager fm2 = createFileManager(compiler, diagnostics)) {
                    task = compiler.getTask(null, fm2, diagnostics, null, null, compilationUnits);
                    task.setProcessors(List.of(new SchemaCapturingProcessor()));
                    success = task.call();
                }
            }

            assertTrue(success, "Compilation failed: " + diagnostics.getDiagnostics());
        } catch (IOException e) {
            fail("Failed to create file manager: " + e.getMessage());
        }
        return new ArrayList<>(SchemaCapturingProcessor.capturedSchemas);
    }

    private List<String> compileAndCaptureParams(String source) {
        SchemaCapturingProcessor.capturedSchemas.clear();
        SchemaCapturingProcessor.capturedParameterSchemas.clear();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler not available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        String className = extractClassName(source);
        List<JavaFileObject> compilationUnits = List.of(new InMemorySource(className, source));

        try (StandardJavaFileManager fm = createFileManager(compiler, diagnostics)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, null, null, compilationUnits);
            task.setProcessors(List.of(new SchemaCapturingProcessor()));
            boolean success = task.call();

            assertTrue(success, "Compilation failed: " + diagnostics.getDiagnostics());
        } catch (IOException e) {
            fail("Failed to create file manager: " + e.getMessage());
        }
        return new ArrayList<>(SchemaCapturingProcessor.capturedParameterSchemas);
    }

    private String extractClassName(String source) {
        // Simple extraction: find "class X", "record X", "enum X", or "interface X"
        for (String keyword : new String[]{"class ", "record ", "enum ", "interface "}) {
            int idx = source.indexOf(keyword);
            if (idx >= 0) {
                int start = idx + keyword.length();
                int end = start;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                return source.substring(start, end);
            }
        }
        return "Unknown";
    }

    // --- Type mapping tests ---

    @Test
    void memberTypeMappings() {
        // One compilation covers every member type mapping:
        // {method, return type, expected schema source}
        String[][] rows = {{"schemaTargetString", "String", "Map.of(\"type\", \"string\")"},
                {"schemaTargetInt", "int", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetInteger", "Integer", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetLong", "long", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetDouble", "double", "Map.of(\"type\", \"number\")"},
                {"schemaTargetFloat", "float", "Map.of(\"type\", \"number\")"},
                {"schemaTargetBoolean", "boolean", "Map.of(\"type\", \"boolean\")"},
                {"schemaTargetBooleanBoxed", "Boolean", "Map.of(\"type\", \"boolean\")"},
                {"schemaTargetByte", "Byte", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetShort", "Short", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetChar", "Character", "Map.of(\"type\", \"string\")"},
                {"schemaTargetArray", "String[]",
                        "Map.of(\"type\", \"array\", \"items\", Map.of(\"type\", \"string\"))"},
                {"schemaTargetList", "List<String>",
                        "Map.of(\"type\", \"array\", \"items\", Map.of(\"type\", \"string\"))"},
                {"schemaTargetMap", "Map<String, String>",
                        "Map.of(\"type\", \"object\", \"additionalProperties\", Map.of(\"type\", \"string\"))"},
                {"schemaTargetMapObject", "Map<String, Object>", "Map.of(\"type\", \"object\")"},
                {"schemaTargetMapBool", "Map<String, Boolean>",
                        "Map.of(\"type\", \"object\", \"additionalProperties\", Map.of(\"type\", \"boolean\"))"},
                {"schemaTargetMapLong", "Map<String, Long>",
                        "Map.of(\"type\", \"object\", \"additionalProperties\", Map.of(\"type\", \"integer\"))"},
                {"schemaTargetNestedMap", "Map<String, List<String>>",
                        "Map.of(\"type\", \"object\", \"additionalProperties\", "
                                + "Map.of(\"type\", \"array\", \"items\", Map.of(\"type\", \"string\")))"},
                {"schemaTargetOptional", "Optional<String>", "Map.of(\"type\", \"string\")"},
                {"schemaTargetOptionalInt", "OptionalInt", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetOptionalLong", "OptionalLong", "Map.of(\"type\", \"integer\")"},
                {"schemaTargetOptionalDouble", "OptionalDouble", "Map.of(\"type\", \"number\")"},
                {"schemaTargetUuid", "UUID", "Map.of(\"type\", \"string\", \"format\", \"uuid\")"},
                {"schemaTargetDateTime", "OffsetDateTime", "Map.of(\"type\", \"string\", \"format\", \"date-time\")"},
                {"schemaTargetLocalDateTime", "LocalDateTime",
                        "Map.of(\"type\", \"string\", \"format\", \"date-time\")"},
                {"schemaTargetInstant", "Instant", "Map.of(\"type\", \"string\", \"format\", \"date-time\")"},
                {"schemaTargetZonedDateTime", "ZonedDateTime",
                        "Map.of(\"type\", \"string\", \"format\", \"date-time\")"},
                {"schemaTargetLocalDate", "LocalDate", "Map.of(\"type\", \"string\", \"format\", \"date\")"},
                {"schemaTargetLocalTime", "LocalTime", "Map.of(\"type\", \"string\", \"format\", \"time\")"},
                {"schemaTargetObject", "Object", "Map.of()"}};
        StringBuilder source = new StringBuilder("import java.util.*;\nimport java.time.*;\n")
                .append("public class TestMemberTypesHolder {\n");
        for (String[] row : rows) {
            String value = switch (row[1]) {
                case "boolean" -> "false";
                case "int", "long", "double", "float", "short", "byte", "char" -> "0";
                default -> "null";
            };
            source.append("    public ").append(row[1]).append(' ').append(row[0]).append("() { return ").append(value)
                    .append("; }\n");
        }
        source.append("}\n");
        List<String> schemas = compileAndCapture(source.toString());
        List<String> missing = new ArrayList<>();
        for (String[] row : rows) {
            String expected = row[0] + "=" + row[2];
            if (!schemas.contains(expected)) {
                missing.add(expected);
            }
        }
        assertEquals(List.of(), missing, "Captured: " + schemas);
    }

    @Test
    void enumAndRecordTypes() {
        List<String> schemas = compileAndCapture("""
                public enum TestEnumColor { RED, GREEN, BLUE }
                """, """
                public record TestRecordPerson(String name, int age, boolean active) {}
                """, """
                import java.util.Optional;
                public record TestRecordWithOptional(String name, Optional<String> nickname) {}
                """);
        assertContainsSchema(schemas, "TestEnumColor",
                "Map.of(\"type\", \"string\", \"enum\", List.of(\"RED\", \"GREEN\", \"BLUE\"))");
        assertContainsSchema(schemas, "TestRecordPerson",
                "Map.of(\"type\", \"object\", \"properties\", "
                        + "Map.ofEntries(Map.entry(\"name\", Map.of(\"type\", \"string\")), "
                        + "Map.entry(\"age\", Map.of(\"type\", \"integer\")), "
                        + "Map.entry(\"active\", Map.of(\"type\", \"boolean\"))), "
                        + "\"required\", List.of(\"name\", \"age\", \"active\"))");
        assertContainsSchema(schemas, "TestRecordWithOptional",
                "Map.of(\"type\", \"object\", \"properties\", "
                        + "Map.ofEntries(Map.entry(\"name\", Map.of(\"type\", \"string\")), "
                        + "Map.entry(\"nickname\", Map.of(\"type\", \"string\"))), \"required\", List.of(\"name\"))");
    }

    @Test
    void recordWithMoreThanTenFields() {
        String source = """
                public record TestRecordLarge(
                    String f1, String f2, String f3, String f4, String f5,
                    String f6, String f7, String f8, String f9, String f10,
                    String f11) {}
                """;
        List<String> schemas = compileAndCapture(source);
        // Verify the schema contains all 11 fields and uses Map.ofEntries
        String schema = schemas.stream().filter(s -> s.startsWith("TestRecordLarge=")).findFirst().orElse("");
        assertFalse(schema.isEmpty(), "Expected schema for TestRecordLarge");
        assertTrue(schema.contains("Map.ofEntries("), "Should use Map.ofEntries for >10 fields: " + schema);
        assertTrue(schema.contains("Map.entry(\"f1\""), "Should have f1: " + schema);
        assertTrue(schema.contains("Map.entry(\"f11\""), "Should have f11: " + schema);
        // Verify the generated source expression is compilable by re-compiling it
        String schemaExpr = schema.substring(schema.indexOf('=') + 1);
        String validationSource = "import java.util.Map;\nimport java.util.List;\n"
                + "public class LargeRecordValidation {\n" + "    @SuppressWarnings(\"unchecked\")\n"
                + "    public Object schema() { return " + schemaExpr + "; }\n}\n";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = List.of(new InMemorySource("LargeRecordValidation", validationSource));
        try (StandardJavaFileManager fm = createFileManager(compiler, diagnostics)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, null, null, units);
            boolean success = task.call();
            assertTrue(success, "Generated schema for >10-field record does not compile: "
                    + diagnostics.getDiagnostics() + "\nSource:\n" + validationSource);
        } catch (IOException e) {
            fail("Failed to create file manager: " + e.getMessage());
        }
    }

    @Test
    void parametersSchema() {
        String source = """
                public class TestParamsHolder {
                    public void parametersTarget(String query, int limit, boolean verbose) {}
                }
                """;
        List<String> paramSchemas = compileAndCaptureParams(source);
        assertFalse(paramSchemas.isEmpty(), "Expected parameter schemas");
        String schema = paramSchemas.get(0);
        assertTrue(schema.contains("\"type\", \"object\""), "Should be object type: " + schema);
        assertTrue(schema.contains("Map.entry(\"query\", Map.of(\"type\", \"string\"))"),
                "Should have query property: " + schema);
        assertTrue(schema.contains("Map.entry(\"limit\", Map.of(\"type\", \"integer\"))"),
                "Should have limit property: " + schema);
        assertTrue(schema.contains("Map.entry(\"verbose\", Map.of(\"type\", \"boolean\"))"),
                "Should have verbose property: " + schema);
        assertTrue(schema.contains("\"required\", List.of("), "Should have required list: " + schema);
    }

    @Test
    void sealedInterfaceType() {
        String sealedInterface = """
                public sealed interface TestSealedShape permits TestSealedCircle, TestSealedRect {}
                """;
        String circle = """
                public record TestSealedCircle(double radius) implements TestSealedShape {}
                """;
        String rect = """
                public record TestSealedRect(double width, double height) implements TestSealedShape {}
                """;
        List<String> schemas = compileAndCapture(sealedInterface, circle, rect);
        String expected = "Map.of(\"oneOf\", List.of(" + "Map.of(\"type\", \"object\", \"properties\", "
                + "Map.ofEntries(Map.entry(\"radius\", Map.of(\"type\", \"number\"))), "
                + "\"required\", List.of(\"radius\")), " + "Map.of(\"type\", \"object\", \"properties\", "
                + "Map.ofEntries(Map.entry(\"width\", Map.of(\"type\", \"number\")), "
                + "Map.entry(\"height\", Map.of(\"type\", \"number\"))), "
                + "\"required\", List.of(\"width\", \"height\"))))";
        assertContainsSchema(schemas, "TestSealedShape", expected);
    }

    private void assertContainsSchema(List<String> schemas, String methodName, String expectedSchema) {
        String expected = methodName + "=" + expectedSchema;
        assertTrue(schemas.stream().anyMatch(s -> s.equals(expected)),
                "Expected schema '" + expected + "' not found in: " + schemas);
    }
}
