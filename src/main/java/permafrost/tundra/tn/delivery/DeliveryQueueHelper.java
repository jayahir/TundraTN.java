/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Lachlan Dowding
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package permafrost.tundra.tn.delivery;

import com.wm.app.b2b.server.InvokeState;
import com.wm.app.b2b.server.Service;
import com.wm.app.b2b.server.ServiceException;
import com.wm.app.b2b.server.Session;
import com.wm.app.tn.db.Datastore;
import com.wm.app.tn.db.QueueOperations;
import com.wm.app.tn.db.SQLWrappers;
import com.wm.app.tn.delivery.DeliveryQueue;
import com.wm.app.tn.delivery.GuaranteedJob;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataFactory;
import com.wm.data.IDataUtil;
import com.wm.lang.ns.NSName;
import permafrost.tundra.lang.ExceptionHelper;
import permafrost.tundra.server.ServerThreadFactory;
import permafrost.tundra.tn.document.BizDocEnvelopeHelper;
import permafrost.tundra.util.concurrent.BlockingRejectedExecutionHandler;
import permafrost.tundra.util.concurrent.DirectExecutorService;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A collection of convenience methods for working with Trading Networks delivery queues.
 */
public class DeliveryQueueHelper {
    /**
     * SQL statement to select head of a delivery queue in job creation datetime order.
     */
    private static final String SELECT_NEXT_DELIVERY_JOB_ORDERED_SQL = "SELECT JobID FROM DeliveryJob WHERE QueueName = ? AND JobStatus = 'QUEUED' AND TimeCreated = (SELECT MIN(TimeCreated) FROM DeliveryJob WHERE QueueName = ? AND JobStatus = 'QUEUED') AND TimeUpdated <= ?";

    /**
     * SQL statement to select head of a delivery queue in indeterminate order.
     */
    private static final String SELECT_NEXT_DELIVERY_JOB_UNORDERED_SQL = "SELECT JobID FROM DeliveryJob WHERE QueueName = ? AND JobStatus = 'QUEUED' AND TimeCreated = (SELECT MIN(TimeCreated) FROM DeliveryJob WHERE QueueName = ? AND JobStatus = 'QUEUED' AND TimeUpdated <= ?)";

    /**
     * The name of the service that Trading Networks uses to invoke delivery queue processing services.
     */
    private static final String DELIVER_BATCH_SERVICE_NAME = "wm.tn.queuing:deliverBatch";

    /**
     * The name of the service used to update the completion status of a delivery queue job.
     */
    private static final NSName UPDATE_QUEUED_TASK_SERVICE_NAME = NSName.create("wm.tn.queuing:updateQueuedTask");

    /**
     * How long to wait between each poll of a delivery queue for more jobs.
     */
    private static final long WAIT_BETWEEN_DELIVERY_QUEUE_POLLS_MILLISECONDS = 100;

    /**
     * How long to wait for an executor to shut down or terminate.
     */
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 60;

    /**
     * Disallow instantiation of this class.
     */
    private DeliveryQueueHelper() {}

    /**
     * Returns the Trading Networks delivery queue associated with the given name.
     *
     * @param queueName The name of the queue to return.
     * @return The delivery queue with the given name.
     * @throws ServiceException If a database error occurs.
     */
    public static DeliveryQueue get(String queueName) throws ServiceException {
        if (queueName == null) return null;

        DeliveryQueue queue = null;

        try {
            queue = QueueOperations.selectByName(queueName);
        } catch(SQLException ex) {
            ExceptionHelper.raise(ex);
        } catch(IOException ex) {
            ExceptionHelper.raise(ex);
        }

        return queue;
    }

    /**
     * Refreshes the given Trading Networks delivery queue from the database.
     *
     * @param queue The queue to be refreshed.
     * @return      The given queue, refreshed from the database.
     * @throws ServiceException If a database error occurs.
     */
    public static DeliveryQueue refresh(DeliveryQueue queue) throws ServiceException {
        return get(queue.getQueueName());
    }

    /**
     * Returns a list of all registered Trading Networks delivery queues.
     *
     * @return A list of all registered Trading Networks delivery queues.
     * @throws ServiceException If a database error occurs.
     */
    public static DeliveryQueue[] list() throws ServiceException {
        DeliveryQueue[] output = null;

        try {
            output = QueueOperations.select(null);
        } catch(SQLException ex) {
            ExceptionHelper.raise(ex);
        } catch(IOException ex) {
            ExceptionHelper.raise(ex);
        }

        return output;
    }

    /**
     * Enables the delivery of the given Trading Networks delivery queue.
     *
     * @param queue The queue to enable delivery on.
     * @throws ServiceException If a database error occurs.
     */
    public static void enable(DeliveryQueue queue) throws ServiceException {
        if (queue == null) return;
        queue.setState(DeliveryQueue.STATE_ENABLED);
        save(queue);
    }

    /**
     * Disables the delivery of the given Trading Networks delivery queue.
     *
     * @param queue The queue to enable delivery on.
     * @throws ServiceException If a database error occurs.
     */
    public static void disable(DeliveryQueue queue) throws ServiceException {
        if (queue == null) return;
        queue.setState(DeliveryQueue.STATE_DISABLED);
        save(queue);
    }

    /**
     * Drains the delivery of the given Trading Networks delivery queue.
     *
     * @param queue The queue to enable delivery on.
     * @throws ServiceException If a database error occurs.
     */
    public static void drain(DeliveryQueue queue) throws ServiceException {
        if (queue == null) return;
        queue.setState(DeliveryQueue.STATE_DRAINING);
        save(queue);
    }

    /**
     * Suspends the delivery of the given Trading Networks delivery queue.
     *
     * @param queue The queue to enable delivery on.
     * @throws ServiceException If a database error occurs.
     */
    public static void suspend(DeliveryQueue queue) throws ServiceException {
        if (queue == null) return;
        queue.setState(DeliveryQueue.STATE_SUSPENDED);
        save(queue);
    }

    /**
     * Returns the number of jobs currently queued in the given Trading Networks delivery queue.
     *
     * @param queue The queue to return the length of.
     * @return      The length of the given queue, which is the number of delivery jobs with a status
     *              of QUEUED or DELIVERING.
     * @throws ServiceException If a database error occurs.
     */
    public static int length(DeliveryQueue queue) throws ServiceException {
        int length = 0;

        if (queue != null) {
            try {
                String[] jobs = QueueOperations.getQueuedJobs(queue.getQueueName());
                if (jobs != null) length = jobs.length;
            } catch(SQLException ex) {
                ExceptionHelper.raise(ex);
            }
        }

        return length;
    }

    /**
     * Updates the given Trading Networks delivery queue with any changes that may have occurred.
     *
     * @param queue The queue whose changes are to be saved.
     * @throws ServiceException If a database error occurs.
     */
    public static void save(DeliveryQueue queue) throws ServiceException {
        if (queue == null) return;

        IData pipeline = IDataFactory.create();
        IDataCursor cursor = pipeline.getCursor();
        IDataUtil.put(cursor, "queue", queue);
        cursor.destroy();

        try {
            Service.doInvoke("wm.tn.queuing", "updateQueue", pipeline);
        } catch(Exception ex) {
            ExceptionHelper.raise(ex);
        }
    }

    /**
     * Returns the head of the given delivery queue without dequeuing it.
     *
     * @param queue   The delivery queue whose head job is to be returned.
     * @param ordered Whether jobs should be dequeued in strict creation datetime or first in first out (FIFO) order.
     * @return        The job at the head of the given queue, or null if the queue is empty.
     * @throws ServiceException
     */
    public static GuaranteedJob peek(DeliveryQueue queue, boolean ordered) throws ServiceException {
        if (queue == null) return null;

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet results = null;
        GuaranteedJob task = null;

        try {
            connection = Datastore.getConnection();
            statement = connection.prepareStatement(ordered ? SELECT_NEXT_DELIVERY_JOB_ORDERED_SQL : SELECT_NEXT_DELIVERY_JOB_UNORDERED_SQL);
            statement.clearParameters();

            String queueName = queue.getQueueName();
            SQLWrappers.setChoppedString(statement, 1, queueName, "DeliveryQueue.QueueName");
            SQLWrappers.setChoppedString(statement, 2, queueName, "DeliveryQueue.QueueName");
            SQLWrappers.setTimestamp(statement, 3, new java.sql.Timestamp(new java.util.Date().getTime()));

            results = statement.executeQuery();
            if (results.next()) {
                String id = results.getString(1);
                task = GuaranteedJobHelper.get(id);
            }
            connection.commit();
        } catch (SQLException ex) {
            connection = Datastore.handleSQLException(connection, ex);
            ExceptionHelper.raise(ex);
        } finally {
            SQLWrappers.close(results);
            SQLWrappers.close(statement);
            Datastore.releaseConnection(connection);
        }

        return task;
    }

    /**
     * Dequeues the job at the head of the given delivery queue.
     *
     * @param queue   The delivery queue to dequeue the head job from.
     * @param ordered Whether jobs should be dequeued in strict creation datetime or first in first out (FIFO) order.
     * @return        The dequeued job that was at the head of the given queue, or null if queue is empty.
     * @throws ServiceException If a database error is encountered.
     */
    public static GuaranteedJob pop(DeliveryQueue queue, boolean ordered) throws ServiceException {
        GuaranteedJob task = peek(queue, ordered);
        GuaranteedJobHelper.setDelivering(task);
        return task;
    }

    /**
     * Callable for invoking a given service against a given job.
     */
    private static class CallableGuaranteedJob implements Callable<IData> {
        /**
         * The job against which the service will be invoked.
         */
        private GuaranteedJob job;

        /**
         * The delivery queue from which the job was dequeued.
         */
        private DeliveryQueue queue;

        /**
         * The service to be invoked.
         */
        private NSName service;

        /**
         * The pipeline the service is invoked with.
         */
        private IData pipeline;

        /**
         * The session the service is invoked under.
         */
        private Session session;

        /**
         * The retry settings to be used when retrying the job.
         */
        private int retryLimit, retryFactor, timeToWait;

        /**
         * Whether the deliver queue should be suspended on retry exhaustion.
         */
        private boolean suspend;

        /**
         * The time the job was dequeued.
         */
        private long timeDequeued;

        /**
         * Creates a new CallableGuaranteedJob which when called invokes the given service against the given job.
         *
         * @param job         The job to be processed.
         * @param service     The service to be invoked to process the given job.
         * @param session     The session used when invoking the given service.
         * @param pipeline    The input pipeline used when invoking the given service.
         * @param retryLimit  The number of retries this job should attempt.
         * @param retryFactor The factor used to extend the time to wait on each retry.
         * @param timeToWait  The time in seconds to wait between each retry.
         * @param suspend     Whether to suspend the delivery queue on job retry exhaustion.
         */
        public CallableGuaranteedJob(DeliveryQueue queue, GuaranteedJob job, String service, Session session, IData pipeline, int retryLimit, int retryFactor, int timeToWait, boolean suspend) {
            this(queue, job, service == null ? null : NSName.create(service), session, pipeline, retryLimit, retryFactor, timeToWait, suspend);
        }

        /**
         * Creates a new CallableGuaranteedJob which when called invokes the given service against the given job.
         *
         * @param job         The job to be processed.
         * @param service     The service to be invoked to process the given job.
         * @param session     The session used when invoking the given service.
         * @param pipeline    The input pipeline used when invoking the given service.
         * @param retryLimit  The number of retries this job should attempt.
         * @param retryFactor The factor used to extend the time to wait on each retry.
         * @param timeToWait  The time in seconds to wait between each retry.
         * @param suspend     Whether to suspend the delivery queue on job retry exhaustion.
         */
        public CallableGuaranteedJob(DeliveryQueue queue, GuaranteedJob job, NSName service, Session session, IData pipeline, int retryLimit, int retryFactor, int timeToWait, boolean suspend) {
            if (queue == null) throw new NullPointerException("queue must not be null");
            if (job == null) throw new NullPointerException("job must not be null");
            if (service == null) throw new NullPointerException("service must not be null");

            this.queue = queue;
            this.job = job;
            this.service = service;
            this.session = session;
            this.pipeline = pipeline == null ? IDataFactory.create() : pipeline;
            this.retryLimit = retryLimit;
            this.retryFactor = retryFactor;
            this.timeToWait = timeToWait;
            this.suspend = suspend;
            this.timeDequeued = System.currentTimeMillis();
        }

        /**
         * Invokes the provided service with the provided pipeline and session against the job.
         *
         * @return The output pipeline returned by the invocation.
         * @throws Exception if the service encounters an error.
         */
        public IData call() throws Exception {
            IData output = null;

            try {
                BizDocEnvelopeHelper.setStatus(job.getBizDocEnvelope(), null, "DEQUEUED");
                GuaranteedJobHelper.log(job, "MESSAGE", "Processing", MessageFormat.format("Dequeued from {0} queue '{1}'", queue.getQueueType(), queue.getQueueName()), MessageFormat.format("Service '{0}' attempting to process document", service.getFullName()));
                GuaranteedJobHelper.setRetryStrategy(job, retryLimit, retryFactor, timeToWait);

                IDataCursor cursor = pipeline.getCursor();
                IDataUtil.put(cursor, "$task", job);
                cursor.destroy();

                output = Service.doInvoke(service, session, pipeline);

                setJobCompleted(output);
            } catch(Throwable ex) {
                setJobCompleted(output, ex);
            }

            return output;
        }

        /**
         * Sets the job as successfully completed.
         *
         * @param serviceOutput The output of the service used to process the job.
         * @throws Exception If a database error occurs.
         */
        private void setJobCompleted(IData serviceOutput) throws Exception {
            setJobCompleted(serviceOutput, null);
        }

        /**
         * Sets the job as either successfully or unsuccessfully completed, depending on whether
         * and exception is provided.
         *
         * @param serviceOutput The output of the service used to process the job.
         * @param exception Optional exception encountered while processing the job.
         * @throws Exception If a database error occurs.
         */
        private void setJobCompleted(IData serviceOutput, Throwable exception) throws Exception {
            IData input = IDataFactory.create();

            IDataCursor cursor = input.getCursor();
            IDataUtil.put(cursor, "taskid", job.getJobId());
            IDataUtil.put(cursor, "queue", queue.getQueueName());

            if (exception == null) {
                IDataUtil.put(cursor, "status", "success");
            } else {
                IDataUtil.put(cursor, "status", "fail");
                IDataUtil.put(cursor, "statusMsg", ExceptionHelper.getMessage(exception));
            }

            IDataUtil.put(cursor, "timeDequeued", timeDequeued);
            if (serviceOutput != null) IDataUtil.put(cursor, "serviceOutput", serviceOutput);
            cursor.destroy();

            Service.doInvoke(UPDATE_QUEUED_TASK_SERVICE_NAME, session, input);

            GuaranteedJobHelper.retry(job, suspend);
        }
    }

    /**
     * Dequeues each task on the given Trading Networks delivery queue, and processes the task using the given service
     * and input pipeline; if concurrency > 1, tasks will be processed by a thread pool whose size is equal to the
     * desired concurrency, otherwise they will be processed on the current thread.
     *
     * @param queueName   The name of the delivery queue whose queued jobs are to be processed.
     * @param service     The service to be invoked to process jobs on the given delivery queue.
     * @param pipeline    The input pipeline used when invoking the given service.
     * @param concurrency If > 1, this is the number of threads used to process jobs simultaneously.
     * @param retryLimit  The number of retries this job should attempt.
     * @param retryFactor The factor used to extend the time to wait on each retry.
     * @param timeToWait  The time in seconds to wait between each retry.
     * @param ordered     Whether delivery queue jobs should be processed in job creation datetime order.
     * @param suspend     Whether to suspend the delivery queue on job retry exhaustion.
     * @throws ServiceException If an error is enountered while processing jobs.
     */
    public static void each(String queueName, String service, IData pipeline, int concurrency, int retryLimit, int retryFactor, int timeToWait, boolean ordered, boolean suspend) throws ServiceException {
        if (queueName == null) throw new NullPointerException("queueName must not be null");
        if (service == null) throw new NullPointerException("service must not be null");

        DeliveryQueue queue = DeliveryQueueHelper.get(queueName);
        if (queue == null) throw new ServiceException("Queue '" + queueName + "' does not exist");

        each(queue, NSName.create(service), pipeline, concurrency, retryLimit, retryFactor, timeToWait, ordered, suspend);
    }

    /**
     * Dequeues each task on the given Trading Networks delivery queue, and processes the task using the given service
     * and input pipeline; if concurrency > 1, tasks will be processed by a thread pool whose size is equal to the
     * desired concurrency, otherwise they will be processed on the current thread.
     *
     * @param queue       The delivery queue whose queued jobs are to be processed.
     * @param service     The service to be invoked to process jobs on the given delivery queue.
     * @param pipeline    The input pipeline used when invoking the given service.
     * @param concurrency If > 1, this is the number of threads used to process jobs simultaneously.
     * @param retryLimit  The number of retries this job should attempt.
     * @param retryFactor The factor used to extend the time to wait on each retry.
     * @param timeToWait  The time in seconds to wait between each retry.
     * @param ordered     Whether delivery queue jobs should be processed in job creation datetime order.
     * @param suspend     Whether to suspend the delivery queue on job retry exhaustion.
     * @throws ServiceException If an error is enountered while processing jobs.
     */
    public static void each(DeliveryQueue queue, NSName service, IData pipeline, int concurrency, int retryLimit, int retryFactor, int timeToWait, boolean ordered, boolean suspend) throws ServiceException {
        boolean invokedByTradingNetworks = invokedByTradingNetworks();
        Session session = Service.getSession();
        ExecutorService executor = getExecutor(queue, InvokeState.getCurrentState(), concurrency);
        Queue<Future<IData>> results = new LinkedList<Future<IData>>();

        try {
            while(true) {
                if (!invokedByTradingNetworks || queue.isEnabled() || queue.isDraining()) {
                    GuaranteedJob job = DeliveryQueueHelper.pop(queue, ordered);
                    if (job == null) {
                        if (results.size() > 0) {
                            // wait for first job to finish or polling timeout; then loop again and see if there are now tasks on the queue
                            awaitFirst(results, WAIT_BETWEEN_DELIVERY_QUEUE_POLLS_MILLISECONDS, TimeUnit.MILLISECONDS);
                        } else {
                            // if all threads have finished and there are no more tasks, then exit
                            break;
                        }
                    } else {
                        results.add(executor.submit(new CallableGuaranteedJob(queue, job, service, session, pipeline, retryLimit, retryFactor, timeToWait, suspend)));
                    }

                    if (invokedByTradingNetworks) queue = DeliveryQueueHelper.refresh(queue);
                } else {
                    break; // if invoked by TN and queue is disabled or suspended, then exit
                }
            }
        } catch (Throwable ex) {
            ExceptionHelper.raise(ex);
        } finally {
            try {
                executor.shutdown();

                // wait a while for existing tasks to terminate
                if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // cancel currently executing tasks
                    // wait a while for tasks to respond to being cancelled
                    executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                // cancel if current thread also interrupted
                executor.shutdownNow();
                // preserve interrupt status
                Thread.currentThread().interrupt();
            } finally {
                awaitAll(results);
            }
        }
    }

    /**
     * Returns an executor appropriate for the level of desired concurrency.
     *
     * @param queue       The delivery queue to be processed.
     * @param state       The invoke state to be used by the thread pool.
     * @param concurrency The level of desired concurrency.
     * @return            An executor appropriate for the level of desired concurrency.
     */
    private static ExecutorService getExecutor(DeliveryQueue queue, InvokeState state, int concurrency) {
        ExecutorService executor;

        if (concurrency <= 1) {
            executor = new DirectExecutorService();
        } else {
            ThreadFactory threadFactory = new ServerThreadFactory("TundraTN/Queue '" + queue.getQueueName() + "'", state);
            BlockingQueue<Runnable> workQueue = new SynchronousQueue<Runnable>(true);
            RejectedExecutionHandler handler = new BlockingRejectedExecutionHandler();

            executor = new java.util.concurrent.ThreadPoolExecutor(concurrency, concurrency, 1, TimeUnit.MINUTES, workQueue, threadFactory, handler);
        }

        return executor;
    }

    // waits for all futures in the given queue to complete
    protected static List<IData> awaitAll(Queue<Future<IData>> futures) {
        List<IData> results = new ArrayList<IData>(futures.size());

        while(futures.size() > 0) {
            try {
                results.add(awaitFirst(futures));
            } catch (Throwable ex) {
                // ignore all exceptions
            }
        }

        return results;
    }

    /**
     * Waits for the first/head future in the given queue to complete.
     *
     * @param futures A queue of futures.
     * @param timeout How long to wait for the future to complete.
     * @param unit    The time unit in which the timeout is specified.
     * @return        The result returned by the first/head future in the given queue.
     * @throws Exception If an exception was encountered by the future when it was executed.
     */
    private static IData awaitFirst(Queue<Future<IData>> futures, long timeout, TimeUnit unit) throws Exception {
        return await(futures.poll(), timeout, unit);
    }

    /**
     * Waits for the first/head future in the given queue to complete.
     *
     * @param futures A queue of futures.
     * @return        The result returned by the first/head future in the given queue.
     * @throws Exception If an exception was encountered by the future when it was executed.
     */
    private static IData awaitFirst(Queue<Future<IData>> futures) throws Exception {
        return await(futures.poll());
    }

    /**
     * Waits for the given future to complete, then returns the result.
     *
     * @param future  The future to wait to complete.
     * @param timeout How long to wait for the future to complete.
     * @param unit    The time unit in which the timeout is specified.
     * @return        The result returned by the future.
     * @throws Exception If an exception was encountered by the future when it was executed.
     */
    private static IData await(Future<IData> future, long timeout, TimeUnit unit) throws Exception {
        return future == null ? null : future.get(timeout, unit);
    }

    /**
     * Waits for the given future to complete, then returns the result.
     *
     * @param future  The future to wait to complete.
     * @return        The result returned by the future.
     * @throws Exception If an exception was encountered by the future when it was executed.
     */
    private static IData await(Future<IData> future) throws Exception {
        return future == null ? null : future.get();
    }

    /**
     * Returns true if the invocation call stack includes the WmTN/wm.tn.queuing:deliverBatch service.
     *
     * @return True if the invocation call stack includes the WmTN/wm.tn.queuing:deliverBatch service.
     */
    private static boolean invokedByTradingNetworks() {
        java.util.Iterator iterator = InvokeState.getCurrentState().getCallStack().iterator();
        boolean result = false;
        while(iterator.hasNext()) {
            result = iterator.next().toString().equals(DELIVER_BATCH_SERVICE_NAME);
            if (result) break;
        }
        return result;
    }

    /**
     * Converts the given Trading Networks delivery queue to an IData doc.
     *
     * @param input The queue to convert to an IData doc representation.
     * @return      An IData doc representation of the given queue.
     * @throws ServiceException If a database error occurs.
     */
    public static IData toIData(DeliveryQueue input) throws ServiceException {
        if (input == null) return null;

        IData output = IDataFactory.create();
        IDataCursor cursor = output.getCursor();

        IDataUtil.put(cursor, "name", input.getQueueName());
        IDataUtil.put(cursor, "type", input.getQueueType());
        IDataUtil.put(cursor, "status", input.getState());
        IDataUtil.put(cursor, "length", "" + length(input));

        cursor.destroy();

        return output;
    }

    /**
     * Converts the given list of Trading Networks delivery queues to an IData[] doc list.
     *
     * @param input The list of queues to convert to an IData[] doc list representation.
     * @return      An IData[] doc list representation of the given queues.
     * @throws ServiceException If a database error occurs.
     */
    public static IData[] toIDataArray(DeliveryQueue[] input) throws ServiceException {
        if (input == null) return null;

        IData[] output = new IData[input.length];

        for (int i = 0; i < input.length; i++) {
            output[i] = toIData(input[i]);
        }

        return output;
    }
}