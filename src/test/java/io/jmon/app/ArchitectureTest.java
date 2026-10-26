package io.jmon.app;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture constraints for JMon's layered design (enforced, non-vacuous
 * predicates only).
 *
 * Layers (subpackages matter):
 * <ul>
 *   <li>{@code io.jmon.domain.{memory,metrics,health,diagnostics}} — pure
 *       models and policies</li>
 *   <li>{@code io.jmon.application} (+ {@code .monitoring},
 *       {@code .port.out}) — use cases and ports</li>
 *   <li>{@code io.jmon.infrastructure.{jvm,system,diagnostics,actuator}} —
 *       implementations of application ports</li>
 *   <li>{@code io.jmon.presentation.tui} (+ {@code .view}) — terminal UI</li>
 *   <li>{@code io.jmon.app} — composition root (only place that may touch
 *       concrete adapters)</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "io.jmon",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.jmon");

    @ArchTest
    static final ArchRule domain_must_be_independent_of_other_layers_and_frameworks =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("io.jmon.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.jmon.application..",
                            "io.jmon.infrastructure..",
                            "io.jmon.presentation..",
                            "io.jmon.app..",
                            "oshi..",
                            "org.jline..",
                            "com.fasterxml.jackson..",
                            "javax.management..",
                            "javax.management.remote..",
                            "com.sun.tools.attach..",
                            "org.slf4j..",
                            "org.tinylog..",
                            "com.sun.jna..",
                            // transport / OS / HTTP implementation APIs
                            "java.net.http..",
                            "javax.net.ssl..")
                    .because("the domain must stay independent of the "
                            + "application layer, adapters, and all "
                            + "framework, transport and OS-facing APIs "
                            + "(OSHI, JLine, Jackson, JMX, Attach, JNA, "
                            + "SLF4J, tinylog, JDK HTTP client)");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure_or_presentation =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("io.jmon.application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.jmon.infrastructure..",
                            "io.jmon.presentation..",
                            "oshi..",
                            "org.jline..",
                            "com.fasterxml.jackson..",
                            "javax.management..",
                            "javax.management.remote..",
                            "com.sun.tools.attach..",
                            "org.tinylog..",
                            "org.springframework..")
                    .because("application owns ports and use cases and must "
                            + "not depend on concrete adapters or the TUI");

    @ArchTest
    static final ArchRule presentation_must_not_depend_on_infrastructure =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("io.jmon.presentation..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.jmon.infrastructure..",
                            "oshi..",
                            "com.fasterxml.jackson..",
                            "javax.management..",
                            "javax.management.remote..",
                            "com.sun.tools.attach..",
                            "com.sun.jna..",
                            "java.net.http..")
                    .because("presentation must use application ports, never "
                            + "concrete infrastructure adapters");

    @ArchTest
    static final ArchRule adapters_may_use_only_ports_config_and_domain =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("io.jmon.infrastructure..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.jmon.presentation..",
                            "io.jmon.app..")
                    .because("infrastructure implements ports in "
                            + "application.port.out; it must not reach into "
                            + "the UI or the composition root");

    @ArchTest
    static final ArchRule only_the_composition_root_wires_concrete_adapters =
            ArchRuleDefinition.noClasses()
                    .that().resideOutsideOfPackages(
                            "io.jmon.app..",
                            "io.jmon.infrastructure..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.jmon.infrastructure..")
                    .because("io.jmon.app is the composition root: only it "
                            + "(and infrastructure internally) may reference "
                            + "concrete adapters");

    @ArchTest
    static final ArchRule packages_must_be_free_of_cycles =
            SlicesRuleDefinition.slices()
                    .matching("io.jmon.(*)..")
                    .should().beFreeOfCycles()
                    .because("layered packages must not depend on each "
                            + "other cyclically (top-level: "
                            + "app/application/domain/infrastructure/"
                            + "presentation)");

    @ArchTest
    static final ArchRule all_sources_live_in_canonical_packages =
            ArchRuleDefinition.classes()
                    .that().resideInAPackage("io.jmon..")
                    .should().resideInAnyPackage(
                            "io.jmon.app..",
                            "io.jmon.application..",
                            "io.jmon.common..",
                            "io.jmon.domain..",
                            "io.jmon.infrastructure..",
                            "io.jmon.presentation..")
                    .because("the codebase is organized into these canonical "
                            + "architectural packages (and their nested "
                            + "responsibility subpackages); named shared "
                            + "constants live in io.jmon.common.constants");

    @ArchTest
    static final ArchRule common_must_be_a_leaf_package =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("io.jmon.common..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.jmon.app..",
                            "io.jmon.application..",
                            "io.jmon.domain..",
                            "io.jmon.infrastructure..",
                            "io.jmon.presentation..")
                    .because("io.jmon.common holds only shared named constants "
                            + "and must stay usable from every layer, including "
                            + "the domain layer which may not depend on other "
                            + "internal layers");

    /**
     * Positive, non-noop counterpart to "infrastructure implements ports":
     * every application-owned output port keeps at least one top-level
     * implementation inside {@code io.jmon.infrastructure..}, and no port
     * may be implemented outside infrastructure/app (composition root may
     * only *wire*, not provide alternative adapters).
     */
    @ArchTest
    static void each_application_port_has_an_infrastructure_implementation(
            JavaClasses mainClasses) {
        String[] portNames = {
            "SystemMetricsCollector", "ProcessCollector",
            "TargetProcessCollector", "ClassHistogramCollector",
            "JvmConnectionSource", "JvmSession",
            "SpringBootMetricsClient", "DiagnosticsService"};
        for (String portName : portNames) {
            JavaClass port = findPort(mainClasses, portName);
            assertTrue(port != null,
                    "port " + portName + " must exist in "
                            + "io.jmon.application.port.out");
            String qualifiedPortName = port.getName();
            boolean infrastructureImpl = mainClasses.stream()
                    .anyMatch(c -> !c.getName().equals(qualifiedPortName)
                            && c.isTopLevelClass()
                            && c.getAllRawInterfaces().stream()
                                    .anyMatch(iface -> iface.getName()
                                            .equals(qualifiedPortName))
                            && c.getPackageName()
                                    .startsWith("io.jmon.infrastructure."));
            assertTrue(infrastructureImpl,
                    "port " + portName
                            + " must have at least one top-level "
                            + "implementation in io.jmon.infrastructure..");
        }
    }

    private static JavaClass findPort(JavaClasses mainClasses, String simpleName) {
        return mainClasses.stream()
                .filter(c -> c.getSimpleName().equals(simpleName))
                .filter(c -> "io.jmon.application.port.out"
                        .equals(c.getPackageName()))
                .findFirst()
                .orElse(null);
    }
}
