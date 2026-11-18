/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;

/** Source-file launcher for the one-off relocation's disposable Maven consumer. */
public final class VerifyRelocation {
    private static final List<String> CLASSIFIERS = List.of(
            "linux-x64", "linux-arm64", "linuxmusl-x64", "linuxmusl-arm64",
            "darwin-x64", "darwin-arm64", "win32-x64", "win32-arm64");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java VerifyRelocation.java consumer <V2-version> <directory>");
            System.out.println("       java --class-path <consumer-classpath> VerifyRelocation.java verify <V2-version> <local-repository> <Maven-log>");
            return;
        }
        require(args.length >= 3, "Missing arguments; run without arguments for usage");
        String version = args[1];
        require(version.matches("2\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?")
                && !version.endsWith("-SNAPSHOT"), "An explicit non-SNAPSHOT V2 version is required");
        switch (args[0]) {
            case "consumer" -> {
                require(args.length == 3, "consumer requires version and directory");
                consumer(version, Path.of(args[2]));
            }
            case "verify" -> {
                require(args.length == 4, "verify requires version, local repository and Maven log");
                verify(version, Path.of(args[2]), Path.of(args[3]));
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void consumer(String version, Path directory) throws Exception {
        StringBuilder dependencies = new StringBuilder(dependency("com.github", "copilot-sdk-java", version, ""));
        for (String artifact : List.of("copilot-sdk", "copilot-sdk-runtime")) {
            if (artifact.equals("copilot-sdk-runtime")) {
                dependencies.append(dependency("com.microsoft", artifact, version, ""));
            }
            for (String classifier : List.of("sources", "javadoc")) {
                dependencies.append(dependency("com.microsoft", artifact, version, classifier));
            }
        }
        for (String classifier : CLASSIFIERS) {
            dependencies.append(dependency("com.microsoft", "copilot-sdk-runtime", version, classifier));
        }
        // JNA is optional in the SDK; this consumer explicitly exercises native loading.
        dependencies.append(dependency("net.java.dev.jna", "jna", "5.19.1", ""));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>relocation.validation</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0</version>
                    <dependencies>
                %s
                    </dependencies>
                </project>
                """.formatted(dependencies));
    }

    private static String dependency(String group, String artifact, String version, String classifier) {
        return """
                <dependency>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    %s
                </dependency>
                """.formatted(group, artifact, version,
                classifier.isEmpty() ? "" : "<classifier>" + classifier + "</classifier>");
    }

    private static void verify(String version, Path repository, Path log) throws Exception {
        String output = Files.readString(log);
        require(output.contains("com.github:copilot-sdk-java:jar:" + version
                + " has been relocated to com.microsoft:copilot-sdk:jar:" + version),
                "Maven did not issue the expected relocation warning");
        require(output.contains("com.microsoft:copilot-sdk:jar:" + version + ":compile"),
                "The dependency tree did not resolve the replacement SDK");

        Path legacy = coordinate(repository, "com.github", "copilot-sdk-java", version);
        Document relocation = pom(legacy.resolve("copilot-sdk-java-" + version + ".pom"),
                "com.github", "copilot-sdk-java", version);
        require(value(relocation, "/project/packaging").equals("pom"), "Relocation must be POM-only");
        require(value(relocation, "/project/distributionManagement/relocation/groupId").equals("com.microsoft")
                && value(relocation, "/project/distributionManagement/relocation/artifactId").equals("copilot-sdk")
                && value(relocation, "/project/distributionManagement/relocation/version").equals(version),
                "Published relocation must name the exact replacement version");
        require(value(relocation, "/project/distributionManagement/relocation/message").contains("https://"),
                "Relocation warning must include migration guidance");
        require(relocation.getElementsByTagName("build").getLength() == 0
                && relocation.getElementsByTagName("dependencies").getLength() == 0,
                "A relocation POM must not add consumer plugins or dependencies");
        try (var files = Files.list(legacy)) {
            require(files.noneMatch(path -> path.toString().endsWith(".jar")), "Legacy coordinate must not contain JARs");
        }

        for (String artifact : List.of("copilot-sdk", "copilot-sdk-runtime")) {
            Path directory = coordinate(repository, "com.microsoft", artifact, version);
            pom(directory.resolve(artifact + "-" + version + ".pom"), "com.microsoft", artifact, version);
            for (String suffix : List.of("", "-sources", "-javadoc")) {
                try (var jar = new ZipFile(directory.resolve(artifact + "-" + version + suffix + ".jar").toFile())) {
                    require(jar.size() > 0, "Empty replacement archive: " + jar.getName());
                    if (artifact.equals("copilot-sdk") && suffix.isEmpty()) {
                        require(jar.getEntry("com/microsoft/copilot/CopilotClient.class") != null,
                                "Replacement SDK does not contain the V2 client");
                    }
                }
            }
        }

        String runtimeVersion = null;
        Path runtime = coordinate(repository, "com.microsoft", "copilot-sdk-runtime", version);
        for (String classifier : CLASSIFIERS) {
            require(output.contains("com.microsoft:copilot-sdk-runtime:jar:" + classifier + ":" + version + ":compile"),
                    "Missing resolved classifier: " + classifier);
            try (var jar = new ZipFile(runtime.resolve("copilot-sdk-runtime-" + version + "-" + classifier + ".jar").toFile())) {
                String prefix = "native/" + classifier + "/";
                for (String file : List.of("runtime.node", "platform.properties",
                        classifier.startsWith("win32") ? "copilot-runtime.exe" : "copilot-runtime")) {
                    var entry = jar.getEntry(prefix + file);
                    require(entry != null && entry.getSize() > 0, classifier + " is missing " + file);
                }
                Properties properties = new Properties();
                try (var stream = jar.getInputStream(jar.getEntry(prefix + "platform.properties"))) {
                    properties.load(stream);
                }
                require(classifier.equals(properties.getProperty("classifier")), "Wrong classifier metadata: " + classifier);
                String nativeVersion = properties.getProperty("version");
                require(nativeVersion != null && !nativeVersion.isBlank(), "Missing runtime version: " + classifier);
                if (runtimeVersion == null) {
                    runtimeVersion = nativeVersion;
                }
                require(runtimeVersion.equals(nativeVersion), "Mixed native runtime versions: " + classifier);
            }
        }

        require(System.getenv("COPILOT_CLI_PATH") == null, "Unset COPILOT_CLI_PATH to test the published runtime");
        Path nativePath = (Path) Class.forName("com.microsoft.copilot.ffi.NativeRuntimeLoader")
                .getMethod("resolve").invoke(null);
        require(nativePath.startsWith(Path.of(System.getProperty("user.home"))) && Files.size(nativePath) > 0,
                "Native loading must use the disposable consumer's extracted runtime");
        Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
        Object library = nativeLibrary.getMethod("getInstance", String.class).invoke(null, nativePath.toString());
        try {
            nativeLibrary.getMethod("getFunction", String.class).invoke(library, "copilot_runtime_host_start");
        } finally {
            nativeLibrary.getMethod("close").invoke(library);
        }
        System.out.println("Verified Maven relocation, parent-free SDK/runtime POMs, all eight classifiers, and host native loading.");
        System.out.println("Replacement SDK: " + version + "; native runtime: " + runtimeVersion);
    }

    private static Path coordinate(Path repository, String group, String artifact, String version) {
        return repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
    }

    private static Document pom(Path path, String group, String artifact, String version) throws Exception {
        require(!Files.readString(path).contains("${"), "Unresolved property in " + path);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(path.toFile());
        require(value(document, "/project/groupId").equals(group)
                && value(document, "/project/artifactId").equals(artifact)
                && value(document, "/project/version").equals(version), "Wrong package identity: " + path);
        require(document.getElementsByTagName("parent").getLength() == 0,
                "Published POM must not depend on the unpublished Java parent: " + path);
        return document;
    }

    private static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
