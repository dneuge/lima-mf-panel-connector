package de.energiequant.limamf.connector.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.locks.Lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common helper methods to work with locks more easily.
 */
public class Locks {
    private static final Logger LOGGER = LoggerFactory.getLogger(Locks.class);

    private Locks() {
        // utility class; hide constructor
    }

    /**
     * Performs an action while holding a {@link Lock}.
     * <p>
     * If an exception occurs, the lock is released again and the exception rethrown as {@link CaughtWhileLocked}.
     * </p>
     *
     * @param lock   {@link Lock} to acquire
     * @param action action to perform; allowed to throw exceptions
     */
    public static void withLock(Lock lock, ThrowingCallable action) {
        Throwable rethrow = null;

        lock.lock();

        try {
            action.call();
        } catch (Exception caught) {
            rethrow = caught;
        }

        lock.unlock();

        if (rethrow != null) {
            throw new CaughtWhileLocked(rethrow);
        }
    }

    /**
     * Performs an action while holding multiple {@link Lock}s. If some locks cannot be acquired, the other ones are
     * released again and acquisition is reattempted after a delay to prevent deadlocks.
     * <p>
     * If an exception occurs, the lock is released again and the exception rethrown as {@link CaughtWhileLocked}.
     * </p>
     *
     * @param locks  {@link Lock}s to acquire
     * @param action action to perform; allowed to throw exceptions
     */
    private static void withLocks(Collection<Lock> locks, ThrowingCallable action) {
        Throwable rethrow = null;

        Collection<Lock> wantedLocks = new ArrayList<>(locks); // just in case structure gets changed outside this thread
        int numWanted = wantedLocks.size();

        Collection<Lock> acquiredLocks = new ArrayList<>();
        while (true) {
            // try locking all
            for (Lock currentLock : wantedLocks) {
                if (!currentLock.tryLock()) {
                    break;
                }

                acquiredLocks.add(currentLock);
            }

            // did we acquire all locks? then leave the loop and run the action below
            int numAcquired = acquiredLocks.size();
            if (numAcquired == numWanted) {
                break;
            }

            // if we got here we are missing at least one lock
            LOGGER.trace("Failed to acquire locks (got {} out of {})", numAcquired, numWanted);

            // release all already acquired locks
            for (Lock previousLock : acquiredLocks) {
                previousLock.unlock();
            }
            acquiredLocks.clear();

            // wait a short moment to try again
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                throw new LockAcquisitionFailed(ex);
            }
        }

        try {
            action.call();
        } catch (Exception caught) {
            rethrow = caught;
        }

        for (Lock lock : acquiredLocks) {
            lock.unlock();
        }

        if (rethrow != null) {
            throw new CaughtWhileLocked(rethrow);
        }
    }

    /**
     * Performs an action while holding multiple {@link Lock}s. If some locks cannot be acquired, the other ones are
     * released again and acquisition is reattempted after a delay to prevent deadlocks.
     * <p>
     * If an exception occurs, the lock is released again and the exception rethrown as {@link CaughtWhileLocked}.
     * </p>
     *
     * @param lock1  {@link Lock} to acquire
     * @param lock2  {@link Lock} to acquire
     * @param action action to perform; allowed to throw exceptions
     */
    public static void withLocks(Lock lock1, Lock lock2, ThrowingCallable action) {
        withLocks(Arrays.asList(lock1, lock2), action);
    }

    /**
     * Something that can be called and is allowed to throw any exception.
     */
    @FunctionalInterface
    public interface ThrowingCallable {
        void call() throws Exception;
    }

    private static class LockAcquisitionFailed extends RuntimeException {
        LockAcquisitionFailed(Throwable cause) {
            super("Exception occurred while acquiring locks", cause);
        }
    }

    private static class CaughtWhileLocked extends RuntimeException {
        CaughtWhileLocked(Throwable cause) {
            super("Exception occurred while locked", cause);
        }
    }
}
