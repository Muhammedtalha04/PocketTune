package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ProcessTerminationBatchTest {
    @Test
    void oneTerminationFailureCannotPreventRemainingTargetsFromBeingCleanedUp() {
        List<Integer> attempted = new ArrayList<>();
        IllegalStateException expectedFailure = new IllegalStateException("broken process handle");

        List<ProcessTerminationBatch.Failure<Integer>> failures = ProcessTerminationBatch.run(
                List.of(1, 2, 3, 4),
                target -> {
                    attempted.add(target);
                    if (target == 2) {
                        throw expectedFailure;
                    }
                }
        );

        assertEquals(List.of(1, 2, 3, 4), attempted);
        assertEquals(1, failures.size());
        assertEquals(2, failures.getFirst().target());
        assertSame(expectedFailure, failures.getFirst().cause());
    }

    @Test
    void everyFailureIsCollectedAfterEveryTargetWasAttempted() {
        List<String> attempted = new ArrayList<>();

        List<ProcessTerminationBatch.Failure<String>> failures = ProcessTerminationBatch.run(
                List.of("first", "second", "third"),
                target -> {
                    attempted.add(target);
                    throw new IllegalArgumentException(target);
                }
        );

        assertEquals(List.of("first", "second", "third"), attempted);
        assertEquals(3, failures.size());
        assertEquals(
                List.of("first", "second", "third"),
                failures.stream().map(ProcessTerminationBatch.Failure::target).toList()
        );
    }
}
