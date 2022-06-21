/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.allocator;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;

public class PendingListenersQueueTests extends ESTestCase {

    public void testShouldExecuteImmediatelyWhenNotPaused() throws InterruptedException {
        var threadPool = new TestThreadPool(getTestName());
        var queue = new PendingListenersQueue(threadPool);
        var executed = new CountDownLatch(1);

        queue.add(1, ActionListener.wrap(executed::countDown));
        queue.complete(1);

        try {
            assertThat(executed.await(1, TimeUnit.SECONDS), equalTo(true));
        } finally {
            terminate(threadPool);
        }
    }

    public void testShouldExecuteAfterUnPaused() throws InterruptedException {
        var threadPool = new TestThreadPool(getTestName());
        var queue = new PendingListenersQueue(threadPool);
        var executed = new CountDownLatch(1);

        queue.add(1, ActionListener.wrap(() -> {
            assertThat(queue.isPaused(), equalTo(false));
            executed.countDown();
        }));
        queue.pause();
        queue.complete(1);
        queue.resume();

        try {
            assertThat(executed.await(1, TimeUnit.SECONDS), equalTo(true));
        } finally {
            terminate(threadPool);
        }
    }

    public void testShouldExecuteOnlyCompleted() throws InterruptedException {
        var threadPool = new TestThreadPool(getTestName());
        var queue = new PendingListenersQueue(threadPool);
        var executed = new CountDownLatch(2);

        queue.add(1, ActionListener.wrap(executed::countDown));
        queue.add(2, ActionListener.wrap(executed::countDown));
        queue.add(3, ActionListener.wrap(() -> fail("Should not complete in test")));
        queue.complete(2);

        try {
            assertThat(executed.await(1, TimeUnit.SECONDS), equalTo(true));
        } finally {
            terminate(threadPool);
        }
    }

    /**
     * This test simulates following scenario:
     *
     * Master: starts allocation (index 2)
     * Master: pauses queue
     * Master: submits new balance calculation (index 2)
     * Master: allocates using already computed balance (index 1)
     *
     * Calculator: starts computing new balance (index 2)
     * Calculator: new balance is computed (isFresh=true, hasChanges=false), completes the queue for the new balance (index 2)
     *
     * Master: completes the queue for applied balance (index 1)
     * Master: resumes the queue
     *
     * Expects: Listener (index 2) should be completed
     */
    public void testShouldAdvanceOnly() throws InterruptedException {
        var threadPool = new TestThreadPool(getTestName());
        var queue = new PendingListenersQueue(threadPool);
        var executed = new CountDownLatch(1);

        // master
        queue.pause();
        queue.add(2, ActionListener.wrap(executed::countDown));

        // balance computation
        queue.complete(2);

        // master
        queue.complete(1);
        queue.resume();

        try {
            assertThat(executed.await(1, TimeUnit.SECONDS), equalTo(true));
        } finally {
            terminate(threadPool);
        }
    }

    public void testShouldExecuteAllAsNonMaster() throws InterruptedException {
        var threadPool = new TestThreadPool(getTestName());
        var queue = new PendingListenersQueue(threadPool);
        var executed = new CountDownLatch(2);

        queue.add(1, ActionListener.wrap(ignored -> fail("Should not complete in test"), exception -> executed.countDown()));
        queue.add(2, ActionListener.wrap(ignored -> fail("Should not complete in test"), exception -> executed.countDown()));
        queue.pause();
        queue.complete(1);
        queue.completeAllAsNotMaster();

        try {
            assertThat(executed.await(1, TimeUnit.SECONDS), equalTo(true));
        } finally {
            terminate(threadPool);
        }
    }
}
