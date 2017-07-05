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

import com.wm.app.b2b.server.ServiceException;
import com.wm.app.tn.db.Datastore;
import com.wm.app.tn.db.DeliveryStore;
import com.wm.app.tn.db.SQLStatements;
import com.wm.app.tn.db.SQLWrappers;
import com.wm.app.tn.delivery.DeliveryQueue;
import com.wm.app.tn.delivery.GuaranteedJob;
import com.wm.app.tn.delivery.JobMgr;
import com.wm.app.tn.doc.BizDocEnvelope;
import com.wm.app.tn.manage.OmiUtils;
import com.wm.app.tn.profile.ProfileStore;
import com.wm.app.tn.profile.ProfileStoreException;
import com.wm.app.tn.profile.ProfileSummary;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import permafrost.tundra.math.BigDecimalHelper;
import permafrost.tundra.math.RoundingModeHelper;
import permafrost.tundra.time.DateTimeHelper;
import permafrost.tundra.tn.document.BizDocEnvelopeHelper;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import javax.xml.datatype.Duration;

/**
 * A collection of convenience methods for working with Trading Networks delivery jobs.
 */
public final class GuaranteedJobHelper {
    /**
     * SQL statement for updating a Trading Networks delivery job.
     */
    private static final String UPDATE_DELIVERY_JOB_SQL = "deliver.job.update";

    /**
     * SQL statement for updating the retry strategy of a Trading Networks delivery job.
     */
    private static final String UPDATE_DELIVERY_JOB_RETRY_STRATEGY_SQL = "UPDATE DeliveryJob SET RetryLimit = ?, RetryFactor = ?, TimeToWait = ? WHERE JobID = ?";

    /**
     * SQL statement for updating a Trading Networks delivery job status to "DELIVERING".
     */
    private static final String UPDATE_DELIVERY_JOB_STATUS_TO_DELIVERING_SQL = "deliver.job.update.delivering";

    /**
     * SQL statement for selecting all Trading Networks delivery jobs for a specific bizdoc.
     */
    private static final String SELECT_DELIVERY_JOBS_FOR_BIZDOC_SQL = "delivery.jobid.select.docid";

    /**
     * The system status to use when queuing a BizDocEnvelope.
     */
    private static final String BIZDOC_ENVELOPE_QUEUED_SYSTEM_STATUS = "QUEUED";

    /**
     * The user status to use when retrying a BizDocEnvelope delivery queue job.
     */
    private static final String BIZDOC_ENVELOPE_REQUEUED_USER_STATUS = "REQUEUED";

    /**
     * The user status to use when suspending a delivery queue due to BizDocEnvelope delivery queue job exhaustion.
     */
    private static final String BIZDOC_ENVELOPE_SUSPENDED_USER_STATUS = "SUSPENDED";

    /**
     * The user status to use when a BizDocEnvelope's queued job is exhausted.
     */
    private static final String BIZDOC_ENVELOPE_EXHAUSTED_USER_STATUS = "EXHAUSTED";

    /**
     * The default timeout for database queries.
     */
    private static final int DEFAULT_SQL_STATEMENT_QUERY_TIMEOUT_SECONDS = 30;

    /**
     * The number of decimal places expected in fixed decimal point retry factor.
     */
    private static final int RETRY_FACTOR_DECIMAL_PRECISION = 3;

    /**
     * The multiplier to use to pack a decimal retry factor into an int.
     */
    private static final float RETRY_FACTOR_DECIMAL_MULTIPLIER = (float)Math.pow(10, RETRY_FACTOR_DECIMAL_PRECISION);

    /**
     * Disallow instantiation of this class.
     */
    private GuaranteedJobHelper() {}

    /**
     * Returns the job with the given ID.
     *
     * @param id The ID of the job to be returned.
     * @return   The job associated with the given ID.
     */
    public static GuaranteedJob get(String id) {
        if (id == null) return null;
        return DeliveryStore.getAnyJob(id, OmiUtils.isOmiEnabled());
    }

    /**
     * Returns the given job, refreshed from the Trading Networks database.
     *
     * @param job The job to be refreshed.
     * @return    The given job, refreshed from the Trading Networks database.
     */
    public static GuaranteedJob refresh(GuaranteedJob job) {
        if (job == null) return null;
        return get(job.getJobId());
    }

    /**
     * Returns a GuaranteedJob, if given either a subset or full GuaranteedJob as an IData document.
     *
     * @param input             An IData document which could be a GuaranteedJob, or could be a subset of a
     *                          GuaranteedJob that includes an TaskId key.
     * @return                  The GuaranteedJob associated with the given IData document.
     */
    public static GuaranteedJob normalize(IData input) {
        if (input == null) return null;

        GuaranteedJob job = null;

        if (input instanceof GuaranteedJob) {
            job = (GuaranteedJob)input;
        } else {
            IDataCursor cursor = input.getCursor();
            String id = IDataUtil.getString(cursor, "TaskId");
            cursor.destroy();

            if (id == null) throw new IllegalArgumentException("TaskId is required");

            job = get(id);
        }

        return job;
    }

    /**
     * Returns all delivery queue jobs associated with the given BizDocEnvelope.
     *
     * @param bizdoc        The BizDocEnvelope to return all associated jobs for.
     * @return              An array of all delivery queue jobs associated with the given BizDocEnvelope.
     * @throws SQLException If a database error occurs.
     */
    public static GuaranteedJob[] list(BizDocEnvelope bizdoc) throws SQLException {
        if (bizdoc == null) return null;

        String[] taskIDs = list(bizdoc.getInternalId());

        List<GuaranteedJob> output = new ArrayList<GuaranteedJob>();

        for (String taskID : taskIDs) {
            output.add(get(taskID));
        }

        return output.toArray(new GuaranteedJob[output.size()]);
    }

    /**
     * Returns all delivery queue jobs associated with the given BizDocEnvelope.
     *
     * @param internalID    The internal ID of the BizDocEnvelope to return all associated jobs for.
     * @return              An array of all delivery queue job IDs associated with the given BizDocEnvelope.
     * @throws SQLException If a database error occurs.
     */
    public static String[] list(String internalID) throws SQLException {
        if (internalID == null) return null;

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        List<String> output = new ArrayList<String>();

        try {
            connection = Datastore.getConnection();
            statement = SQLStatements.prepareStatement(connection, SELECT_DELIVERY_JOBS_FOR_BIZDOC_SQL);
            statement.setQueryTimeout(DEFAULT_SQL_STATEMENT_QUERY_TIMEOUT_SECONDS);
            statement.clearParameters();

            statement.setString(1, internalID);

            resultSet = statement.executeQuery();
            while(resultSet.next()) {
                output.add(resultSet.getString(1));
            }

            connection.commit();
        } catch (SQLException ex) {
            connection = Datastore.handleSQLException(connection, ex);
            throw ex;
        } finally {
            SQLWrappers.close(resultSet);
            SQLStatements.releaseStatement(statement);
            Datastore.releaseConnection(connection);
        }

        return output.toArray(new String[output.size()]);
    }

    /**
     * Restarts the given job. This method, unlike JobMgr.restartJob, does not require the job status to be
     * "STOPPED" or "FAILED", and will restart the given job regardless of its status.
     *
     * @param job The job to be restarted.
     */
    public static void restart(GuaranteedJob job) {
        if (job != null) {
            job.reset();
            job.save();
        }
    }

    /**
     * Update the retry settings on the given job using the given settings, or the retry settings on the receiver's
     * profile if the given retryLimit is less than or equal to 0.
     *
     * @param job                       The job to be updated.
     * @param retryLimit                The number of retries this job should attempt.
     * @param retryFactor               The factor used to extend the time to wait on each retry.
     * @param timeToWait                The time to wait between each retry.
     * @throws ProfileStoreException    If a database error is encountered.
     * @throws SQLException             If a database error is encountered.
     */
    public static void setRetryStrategy(GuaranteedJob job, int retryLimit, float retryFactor, Duration timeToWait) throws ProfileStoreException, SQLException {
        setRetryStrategy(job, retryLimit, retryFactor, timeToWait == null ? 0 : timeToWait.getTimeInMillis(new Date()));
    }

    /**
     * Update the retry settings on the given job using the given settings, or the retry settings on the receiver's
     * profile if the given retryLimit is less than or equal to 0.
     *
     * @param job                       The job to be updated.
     * @param retryLimit                The number of retries this job should attempt.
     * @param retryFactor               The factor used to extend the time to wait on each retry.
     * @param timeToWait                The time in milliseconds to wait between each retry.
     * @throws ProfileStoreException    If a database error is encountered.
     * @throws SQLException             If a database error is encountered.
     */
    public static void setRetryStrategy(GuaranteedJob job, int retryLimit, float retryFactor, long timeToWait) throws ProfileStoreException, SQLException {
        if (job == null) return;

        Connection connection = null;
        PreparedStatement statement = null;

        try {
            int taskRetryLimit = job.getRetryLimit();
            int taskRetryFactor = job.getRetryFactor();
            int taskTTW = (int)job.getTTW();

            BizDocEnvelope bizdoc = job.getBizDocEnvelope();
            ProfileSummary receiver = ProfileStore.getProfileSummary(bizdoc.getReceiverId());

            if (retryLimit <= 0 && receiver.getDeliveryRetries() > 0) {
                retryLimit = receiver.getDeliveryRetries();
                retryFactor = receiver.getRetryFactor();
                timeToWait = receiver.getDeliveryWait();
            }

            if (taskRetryLimit != retryLimit || taskRetryFactor != retryFactor || taskTTW != timeToWait) {
                job.setRetryLimit(retryLimit);

                if (retryFactor >= RETRY_FACTOR_DECIMAL_MULTIPLIER || retryFactor % 1 != 0) {
                    // if retry factor has decimal precision, pack it into an integer by multiplying with a factor
                    // which preserves the configured precision
                    job.setRetryFactor(Math.round(retryFactor * RETRY_FACTOR_DECIMAL_MULTIPLIER));
                } else {
                    job.setRetryFactor(Math.round(retryFactor));
                }
                job.setTTW(timeToWait);

                connection = Datastore.getConnection();
                statement = connection.prepareStatement(UPDATE_DELIVERY_JOB_RETRY_STRATEGY_SQL);
                statement.setQueryTimeout(DEFAULT_SQL_STATEMENT_QUERY_TIMEOUT_SECONDS);
                statement.clearParameters();

                statement.setInt(1, job.getRetryLimit());
                statement.setInt(2, job.getRetryFactor());
                statement.setInt(3, (int)job.getTTW());
                SQLWrappers.setCharString(statement, 4, job.getJobId());

                statement.executeUpdate();
                connection.commit();
            }
        } catch (SQLException ex) {
            connection = Datastore.handleSQLException(connection, ex);
            throw ex;
        } finally {
            SQLWrappers.close(statement);
            Datastore.releaseConnection(connection);
        }
    }

    /**
     * Update the given job's status to "DELIVERING".
     *
     * @param job               The job to be updated.
     * @throws SQLException     If a database error is encountered.
     */
    protected static void setDelivering(GuaranteedJob job) throws SQLException {
        if (job == null) return;

        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = Datastore.getConnection();

            statement = SQLStatements.prepareStatement(connection, UPDATE_DELIVERY_JOB_STATUS_TO_DELIVERING_SQL);
            statement.setQueryTimeout(DEFAULT_SQL_STATEMENT_QUERY_TIMEOUT_SECONDS);
            statement.clearParameters();

            SQLWrappers.setChoppedString(statement, 1, JobMgr.getJobMgr().getServerId(), "DeliveryJob.ServerID");
            SQLWrappers.setCharString(statement, 2, job.getJobId());

            int rowCount = statement.executeUpdate();
            connection.commit();

            if (rowCount == 1) {
                job.delivering();
            } else {
                throw new ConcurrentModificationException(MessageFormat.format("GuaranteedJob {0} not changed to DELIVERING status due to modification by another thread or process", job.getJobId()));
            }
        } catch (SQLException ex) {
            connection = Datastore.handleSQLException(connection, ex);
            throw ex;
        } finally {
            SQLStatements.releaseStatement(statement);
            Datastore.releaseConnection(connection);
        }
    }

    /**
     * Saves the given job to the Trading Networks database. This method differs from job.save() as this method
     * preserves the job's updated time rather than setting it to current time as job.save() does.
     *
     * @param job           The job to be saved.
     * @throws IOException  If an I/O error is encountered.
     * @throws SQLException If a database error is encountered.
     */
    protected static void save(GuaranteedJob job) throws IOException, SQLException {
        if (job == null) return;

        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = Datastore.getConnection();
            statement = SQLStatements.prepareStatement(connection, UPDATE_DELIVERY_JOB_SQL);
            statement.setQueryTimeout(DEFAULT_SQL_STATEMENT_QUERY_TIMEOUT_SECONDS);
            statement.clearParameters();

            // instead of setting TimeUpdated to now, set it to the time in the job object
            SQLWrappers.setTimestamp(statement, 1, new java.sql.Timestamp(job.getTimeUpdated()));

            SQLWrappers.setChoppedString(statement, 2, job.getStatus(), "DeliveryJob.JobStatus");
            statement.setInt(3, job.getRetries());
            SQLWrappers.setChoppedString(statement, 4, job.getTransportStatus(), "DeliveryJob.TransportStatus");
            SQLWrappers.setChoppedString(statement, 5, job.getTransportStatusMessage(), "DeliveryJob.TransportStatusMessage");
            statement.setInt(6, (int)job.getTransportTime());
            SQLWrappers.setBinaryStream(statement, 7, job.getOutputData());
            SQLWrappers.setChoppedString(statement, 8, job.getServerId(), "DeliveryJob.ServerID");
            SQLWrappers.setBinaryStream(statement, 9, job.getDBIData());
            SQLWrappers.setChoppedString(statement, 10, job.getQueueName(), "DeliveryQueue.QueueName");
            SQLWrappers.setChoppedString(statement, 11, job.getInvokeAsUser(), "DeliveryJob.UserName");
            SQLWrappers.setCharString(statement, 12, job.getJobId());

            statement.executeUpdate();
            connection.commit();
        } catch (SQLException ex) {
            connection = Datastore.handleSQLException(connection, ex);
            throw ex;
        } finally {
            SQLStatements.releaseStatement(statement);
            Datastore.releaseConnection(connection);
        }
    }

    /**
     * Re-enqueues the given job for delivery, unless it has reached its retry limit or there are unrecoverable
     * errors on the owning BizDocEnvelope.
     *
     * @param job               The job to be retried.
     * @param suspend           Whether the owning delivery queue should be suspended if the job has
     *                          reached its retry limit.
     * @param exhaustedStatus   The user status set on the bizdoc when all retries are exhausted.
     * @throws IOException      If an I/O error is encountered.
     * @throws SQLException     If a database error is encountered.
     * @throws ServiceException If a service error is encountered.
     */
    public static void retry(GuaranteedJob job, boolean suspend, String exhaustedStatus) throws IOException, SQLException, ServiceException {
        if (job == null) return;
        if (exhaustedStatus == null) exhaustedStatus = BIZDOC_ENVELOPE_EXHAUSTED_USER_STATUS;

        job = refresh(job);

        if (job != null) {
            int retryLimit = job.getRetryLimit();
            int retries = job.getRetries();
            int status = job.getStatusVal();
            String queueName = job.getQueueName();

            DeliveryQueue queue = DeliveryQueueHelper.get(queueName);

            boolean statusSilence = DeliveryQueueHelper.getStatusSilence(queue);
            boolean exhausted = retries >= retryLimit && status == GuaranteedJob.FAILED;
            boolean failed = (retries > 0 && status == GuaranteedJob.QUEUED) || exhausted;

            if (failed) {
                if (exhausted) {
                    if (retryLimit > 0) {
                        BizDocEnvelopeHelper.setStatus(job.getBizDocEnvelope(), null, exhaustedStatus, statusSilence);
                        log(job, "ERROR", "Delivery", MessageFormat.format("Exhausted all retries ({0}/{1})", retries, retryLimit), MessageFormat.format("Exhausted all retries ({0} of {1}) of task \"{2}\" on {3} queue \"{4}\"", retries, retryLimit, job.getJobId(), queue.getQueueType(), queueName));
                    }

                    if (suspend) {
                        // reset retries back to 1
                        retries = 1;
                        job.setRetries(retries);
                        job.setStatus(GuaranteedJob.QUEUED);
                        job.setDefaultServerId();

                        long nextRetry = calculateNextRetryDateTime(job);
                        job.setTimeUpdated(nextRetry);
                        save(job);

                        boolean isSuspended = queue.isSuspended();

                        if (!isSuspended) {
                            // suspend the queue if not already suspended
                            DeliveryQueueHelper.suspend(queue);
                            log(job, "WARNING", "Delivery", MessageFormat.format("Suspended {0} queue \"{1}\"", queue.getQueueType(), queueName), MessageFormat.format("Delivery of {0} queue \"{1}\" was suspended due to task \"{2}\" exhaustion", queue.getQueueType(), queueName, job.getJobId()));
                        }

                        BizDocEnvelopeHelper.setStatus(job.getBizDocEnvelope(), BIZDOC_ENVELOPE_QUEUED_SYSTEM_STATUS, isSuspended ? BIZDOC_ENVELOPE_REQUEUED_USER_STATUS : BIZDOC_ENVELOPE_SUSPENDED_USER_STATUS, statusSilence);
                        log(job, "MESSAGE", "Delivery", MessageFormat.format("Retries reset ({0}/{1})", retries, retryLimit), MessageFormat.format("Retries reset to ensure task is processed upon queue delivery resumption; if this task is not required to be processed again, it should be manually deleted. Next retry ({0} of {1}) of task \"{2}\" on {3} queue \"{4}\" scheduled no earlier than \"{5}\"", retries, retryLimit, job.getJobId(), queue.getQueueType(), queueName, DateTimeHelper.format(nextRetry)));
                    }
                } else {
                    long nextRetry = calculateNextRetryDateTime(job);
                    job.setTimeUpdated(nextRetry); // force this job to wait for its next retry
                    save(job);

                    BizDocEnvelopeHelper.setStatus(job.getBizDocEnvelope(), BIZDOC_ENVELOPE_QUEUED_SYSTEM_STATUS, BIZDOC_ENVELOPE_REQUEUED_USER_STATUS, statusSilence);
                    log(job, "MESSAGE", "Delivery", MessageFormat.format("Next retry scheduled ({0}/{1})", retries, retryLimit), MessageFormat.format("Next retry ({0} of {1}) of task \"{2}\" on {3} queue \"{4}\" scheduled no earlier than \"{5}\"", retries, retryLimit, job.getJobId(), queue.getQueueType(), queueName, DateTimeHelper.format(nextRetry)));
                }
            }
        }
    }

    /**
     * Calculates the next time the given job should be retried according to its retry settings.
     *
     * @param job The job to be retried.
     * @return    The datetime, as the number of milliseconds since the epoch, at which the job should next be retried.
     */
    private static long calculateNextRetryDateTime(GuaranteedJob job) {
        long now = System.currentTimeMillis();
        long nextRetry = now;

        int retryCount = job.getRetries();
        float retryFactor = job.getRetryFactor();
        int ttw = (int)job.getTTW();

        if (ttw > 0) {
            if (retryFactor > 1.0f && retryCount > 1) {
                // if retryFactor is a packed decimal convert it to a fixed point decimal number (this is how we provide
                // support for non-integer retry factors)
                if (retryFactor >= RETRY_FACTOR_DECIMAL_MULTIPLIER) {
                    retryFactor = BigDecimalHelper.round(new BigDecimal(retryFactor / RETRY_FACTOR_DECIMAL_MULTIPLIER), RETRY_FACTOR_DECIMAL_PRECISION, RoundingModeHelper.DEFAULT_ROUNDING_MODE).floatValue();
                }

                nextRetry = now + (long)(ttw * Math.pow(retryFactor, retryCount - 1));
            } else {
                nextRetry = now + ttw;
            }
        }

        return nextRetry;
    }

    /**
     * Returns true if the owning BizDocEnvelope for the given GuaranteedJob has any unrecoverable errors.
     *
     * @param job The GuaranteedJob to check for unrecoverable errors.
     * @return    True if the given GuaranteedJob has unrecoverable errors.
     * @throws ServiceException If a database error occurs.
     */
    public static boolean hasUnrecoverableErrors(GuaranteedJob job) throws ServiceException {
        return BizDocEnvelopeHelper.hasUnrecoverableErrors(job.getBizDocEnvelope());
    }

    /**
     * Adds an activity log statement to the given job.
     *
     * @param job     The GuaranteedJob to add the activity log statement to.
     * @param type    The type of message to be logged.
     * @param klass   The class of the message to be logged.
     * @param summary The summary of the message to be logged.
     * @param message The detail of the message to be logged.
     * @throws ServiceException If an error occurs while logging.
     */
    public static void log(GuaranteedJob job, String type, String klass, String summary, String message) throws ServiceException {
        BizDocEnvelopeHelper.log(job.getBizDocEnvelope(), type, klass, summary, message);
    }
}
