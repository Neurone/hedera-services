/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.merkle.synchronization;

import static com.swirlds.logging.LogMarker.RECONNECT;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.internal.Lesson;
import com.swirlds.common.merkle.synchronization.internal.QueryResponse;
import com.swirlds.common.merkle.synchronization.internal.TeacherReceivingThread;
import com.swirlds.common.merkle.synchronization.internal.TeacherSendingThread;
import com.swirlds.common.merkle.synchronization.internal.TeacherSubtree;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Performs synchronization in the role of the teacher.
 */
public class TeachingSynchronizer {

    private static final String WORK_GROUP_NAME = "teaching-synchronizer";

    private static final Logger logger = LogManager.getLogger(TeachingSynchronizer.class);

    /**
     * Used to get data from the listener.
     */
    private final MerkleDataInputStream inputStream;

    /**
     * Used to transmit data to the listener.
     */
    private final MerkleDataOutputStream outputStream;

    /**
     * <p>
     * Subtrees that require reconnect using a custom view.
     * </p>
     *
     * <p>
     * Although multiple threads may modify this queue, it is still thread safe. This is because only one thread
     * will attempt to read/write this data structure at any time, and when the thread touching the queue changes
     * there is a synchronization point that establishes a happens before relationship.
     * </p>
     */
    private final Queue<TeacherSubtree> subtrees;

    private final Runnable breakConnection;

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    protected final ReconnectConfig reconnectConfig;

    /**
     * A mechanism to check if teaching should be stopped, e.g. when the teacher itself has
     * fallen behind network.
     */
    @Nullable
    private final BooleanSupplier requestToStopTeaching;

    /**
     * Create a new teaching synchronizer.
     *
     * @param threadManager
     * 		responsible for managing thread lifecycles
     * @param in
     * 		the input stream
     * @param out
     * 		the output stream
     * @param root
     * 		the root of the tree
     * @param breakConnection
     * 		a method that breaks the connection. Used iff
     * 		an exception is encountered. Prevents deadlock
     * 		if there is a thread stuck on a blocking IO
     * 		operation that will never finish due to a
     * 		failure.
     * @param requestToStopTeaching
     *      a function to check periodically if teaching should be stopped
     * @param reconnectConfig
     *      reconnect configuration from platform
     */
    public TeachingSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final MerkleDataInputStream in,
            @NonNull final MerkleDataOutputStream out,
            @NonNull final MerkleNode root,
            @Nullable final Runnable breakConnection,
            @Nullable final BooleanSupplier requestToStopTeaching,
            @NonNull final ReconnectConfig reconnectConfig) {

        this.threadManager = Objects.requireNonNull(threadManager, "threadManager must not be null");
        inputStream = Objects.requireNonNull(in, "in must not be null");
        outputStream = Objects.requireNonNull(out, "out must not be null");

        subtrees = new LinkedList<>();
        subtrees.add(new TeacherSubtree(root));

        this.breakConnection = breakConnection;
        this.requestToStopTeaching = requestToStopTeaching;
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig must not be null");
    }

    /**
     * Perform synchronization in the role of the teacher.
     */
    public void synchronize() throws InterruptedException {
        try {
            while (!subtrees.isEmpty()) {
                try (final TeacherSubtree subtree = subtrees.remove()) {
                    subtree.getView().waitUntilReady();
                    sendTree(subtree.getRoot(), subtree.getView());
                }
            }
        } finally {
            // If we crash, make sure to clean up any remaining subtrees.
            for (final TeacherSubtree subtree : subtrees) {
                subtree.close();
            }
        }
    }

    /**
     * Send a tree (or subtree).
     */
    private <T> void sendTree(final MerkleNode root, final TeacherTreeView<T> view) throws InterruptedException {
        logger.info(
                RECONNECT.getMarker(),
                "sending tree rooted at {} with route {}",
                root == null ? null : root.getClass().getName(),
                root == null ? "[]" : root.getRoute());

        // A future improvement might be to reuse threads between subtrees.
        final StandardWorkGroup workGroup =
                new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection, ex -> {
                    Throwable cause = ex;
                    while (cause != null) {
                        if (cause instanceof SocketException socketEx) {
                            if (socketEx.getMessage().equalsIgnoreCase("Connection reset by peer")) {
                                // Connection issues during reconnects are expected and recoverable, just
                                // log them as info. All other exceptions should be treated as real errors
                                logger.info(
                                        RECONNECT.getMarker(),
                                        "Connection reset while sending tree at {} with route {}. Aborting",
                                        root == null ? null : root.getClass().getName(),
                                        root == null ? "[]" : root.getRoute());
                                return true;
                            }
                        }
                        cause = cause.getCause();
                    }
                    // Let StandardWorkGroup log it as an error using the EXCEPTION marker
                    return false;
                });

        final AsyncInputStream<QueryResponse> in =
                new AsyncInputStream<>(inputStream, workGroup, QueryResponse::new, reconnectConfig);
        final AsyncOutputStream<Lesson<T>> out = buildOutputStream(workGroup, outputStream);

        in.start();
        out.start();

        final AtomicBoolean senderIsFinished = new AtomicBoolean(false);

        new TeacherSendingThread<T>(workGroup, in, out, subtrees, view, requestToStopTeaching, senderIsFinished)
                .start();
        new TeacherReceivingThread<>(workGroup, in, view, senderIsFinished).start();

        workGroup.waitForTermination();

        if (workGroup.hasExceptions()) {
            throw new MerkleSynchronizationException("Synchronization failed with exceptions");
        }

        logger.info(RECONNECT.getMarker(), "finished sending tree");
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected <T> AsyncOutputStream<Lesson<T>> buildOutputStream(
            final StandardWorkGroup workGroup, final SerializableDataOutputStream out) {
        return new AsyncOutputStream<>(out, workGroup, reconnectConfig);
    }
}
