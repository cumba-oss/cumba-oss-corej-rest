package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.cumba.corej.core.metadata.DomainClassMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the engine static-init warm-up: forcing {@link DomainClassMap}'s static initializer at
 * startup means its one-time infrastructure warnings fire <em>outside</em> any run, so a subsequent
 * run's captured log does not contain them.
 *
 * <p>
 * ⚑ A second assertion here loaded {@code net.cumba.corej.core.gen.RuleGenerator} by name and
 * pinned that the warm-up had initialised it. It was removed with the warm-up's own
 * {@code RuleGenerator} leg by {@code plans/PLAN-remove-rule-generator.md}. ⚠ It referenced the
 * class as a <b>string literal</b>, so it would have compiled green and failed only at run time.
 * </p>
 */
@SpringBootTest
class EngineStaticInitWarmUpTest
{

    @TempDir
    static Path base;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> base.resolve("sessions").toString());
        registry.add("corej.reports.dir", () -> base.resolve("reports").toString());
    }

    @Autowired
    private RunLogDebugCapture capture;

    @Test
    void warmUpInitializesStaticClasses() throws Exception
    {
        // The @PostConstruct warm-up already ran during context startup; re-running it is
        // idempotent and confirms the public trigger points are reachable and side-effect-free.
        EngineStaticInitWarmUp warmUp = new EngineStaticInitWarmUp();
        var m = EngineStaticInitWarmUp.class.getDeclaredMethod("warmUp");
        m.setAccessible(true);
        m.invoke(warmUp);

        // DomainClassMap's lazy singleton resolved without error.
        assertThat(DomainClassMap.getInstance()).isNotNull();
    }


    @Test
    void subsequentRunLogDoesNotContainOneTimeInfraWarnings()
    {
        // The static initializers fired at startup (warm-up), with no sink bound. A run begun now
        // and re-touching those classes must not re-emit their one-time infra lines into the sink,
        // because their static state is already established.
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            DomainClassMap.getInstance();
            DomainClassMap.getInstance();
        }
        finally
        {
            capture.end();
        }
        // No DomainClassMap load-time infra warning leaked into this run's sink.
        assertThat(lines)
                .noneMatch(l -> l.contains("domain-class-map") || l.contains("not readable"));
    }
}
