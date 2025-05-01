package de.energiequant.limamf.connector;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public abstract class AsyncMonitor<T, C extends Collection<T>> {
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final ObservableCollectionProxy<T, C> collectionProxy;

    protected AsyncMonitor(Supplier<C> collectionConstructor) {
        this(new ObservableCollectionProxy<>(collectionConstructor));
    }

    protected AsyncMonitor(ObservableCollectionProxy<T, C> collectionProxy) {
        this.collectionProxy = collectionProxy;
    }

    public ObservableCollectionProxy<T, C> getCollectionProxy() {
        return collectionProxy;
    }

    public void start() {
        boolean alreadyStarted = started.getAndSet(true);
        if (alreadyStarted) {
            return;
        }

        doStart();
    }

    protected abstract void doStart();

    public void shutdown() {
        boolean alreadyShutdown = shutdown.getAndSet(true);
        if (alreadyShutdown) {
            return;
        }

        doShutdown();

        collectionProxy.getAllPresent()
                       .forEach(collectionProxy::remove);
    }

    protected boolean shouldShutdown() {
        return shutdown.get();
    }

    protected abstract void doShutdown();
}
