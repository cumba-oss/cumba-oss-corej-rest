package net.cumba.corej.rest.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

/**
 * PLAN-dictionary-backed-formats owner ruling OQ3 (a), 2026-10-10: a check run (and the request
 * validation before it) loads data through a local manager with dictionary formats switched off —
 * the engine reads the dictionaries itself, so the manager parses none of them at load.
 */
class StudyValidationCheckRunnerManagerTest
{

    @Test
    void theRunsManagerResolvesNoDictionaryFormats()
    {
        assertFalse(StudyValidationCheckRunner.validationManager().isResolvingDictionaryFormats());
        assertNotSame(StudyValidationCheckRunner.validationManager(),
                StudyValidationCheckRunner.validationManager(), "a new manager per run");
    }
}
