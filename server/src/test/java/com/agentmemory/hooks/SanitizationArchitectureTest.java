package com.agentmemory.hooks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The enforced architecture half of issue #9's "type system (or an enforced architecture test)
 * prevents bypass" criterion (DD-010 / invariant #6). The type system already makes it hard — a
 * {@link Sanitized} has no public constructor and the store writer accepts only that type — but a
 * class <em>inside</em> the {@code com.agentmemory.hooks} package could still call the package-private
 * constructor and forge a sanitized value. This test forbids exactly that: only {@link Sanitizer}
 * may construct a {@link Sanitized}.
 *
 * <p>Scanned over production classes only ({@code main}, tests excluded) so test fixtures that build
 * {@code Sanitized} via the sanitizer are irrelevant and a forged construction in shipping code fails
 * the build.
 */
class SanitizationArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.agentmemory");

    @Test
    void onlyTheSanitizerMayConstructSanitized() {
        // Sanitized is generic, so its sole constructor erases to Sanitized(Object).
        ArchRule rule = noClasses()
                .that().areNotAssignableTo(Sanitizer.class)
                .should().callConstructor(Sanitized.class, Object.class)
                .because("Sanitized is the privacy boundary (DD-010 / invariant #6): only "
                        + "Sanitizer.sanitize() may construct it, so untrusted text cannot reach the "
                        + "store without being scrubbed.");
        rule.check(PRODUCTION_CLASSES);
    }
}
