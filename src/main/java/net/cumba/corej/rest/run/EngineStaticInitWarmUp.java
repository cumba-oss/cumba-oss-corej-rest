package net.cumba.corej.rest.run;

import jakarta.annotation.PostConstruct;
import net.cumba.corej.core.metadata.DomainClassMap;
import org.springframework.stereotype.Component;

/**
 * Forces the engine's static-initializer log sites to fire <em>once at REST startup</em>, outside
 * any run, so their one-time infrastructure warnings go to the server log only and are never
 * attributed to (or raced across) a check run.
 *
 * <p>
 * One engine class logs from a lazily-triggered static block / lazy singleton and is touched here
 * so that logging happens on the startup thread rather than mid-run:
 * </p>
 * <ul>
 * <li>{@link DomainClassMap} — its {@code load()} (run on first
 * {@link DomainClassMap#getInstance()}) warns when the bundled map is missing or an external
 * override is unreadable, and logs an INFO when an override is merged.</li>
 * </ul>
 *
 * <p>
 * That warning would otherwise fire on whatever thread first triggers class-load — which, under
 * parallel load, could be an engine worker thread mid-run (with a capture sink bound),
 * mis-attributing the one-time warning to that run. Touching the class here at startup makes
 * capture deterministic: the warm-up runs on the Spring startup thread with no sink bound, so the
 * warning reaches only the server log.
 * </p>
 *
 * <p>
 * ⚑ A second entry — {@code net.cumba.corej.core.gen.RuleGenerator}, loaded by name through
 * {@link Class#forName} — was removed by {@code plans/PLAN-remove-rule-generator.md}. It had been
 * vestigial since Fix #366 deleted its one log site, and it was warming a class that no longer
 * exists under that name. ⚠ Because it was referenced as a <b>string</b>, nothing in the build
 * would have reported the breakage: it compiles green and throws {@link ClassNotFoundException} at
 * runtime.
 * </p>
 */
@Component
public class EngineStaticInitWarmUp
{

    @PostConstruct
    void warmUp()
    {
        // Force DomainClassMap's lazy singleton (and its load-time warnings) now.
        DomainClassMap.getInstance();
    }
}
