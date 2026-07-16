package com.pockettune.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperationIdSequenceTest {
    @Test
    void replacementOperationsNeverReuseAnEarlierIdentifier() {
        OperationIdSequence sequence = new OperationIdSequence();

        long removedInstanceOperation = sequence.next();
        long replacementInstanceOperation = sequence.next();

        assertNotEquals(0L, removedInstanceOperation);
        assertNotEquals(removedInstanceOperation, replacementInstanceOperation);
    }

    @Test
    void concurrentCallersReceiveUniqueIdentifiers() throws Exception {
        OperationIdSequence sequence = new OperationIdSequence();
        int operationCount = 1_000;
        Set<Long> identifiers = ConcurrentHashMap.newKeySet();
        CountDownLatch finished = new CountDownLatch(operationCount);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int index = 0; index < operationCount; index++) {
                executor.execute(() -> {
                    identifiers.add(sequence.next());
                    finished.countDown();
                });
            }
            assertTrue(finished.await(5L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(operationCount, identifiers.size());
        assertTrue(identifiers.stream().noneMatch(identifier -> identifier == 0L));
    }
}
