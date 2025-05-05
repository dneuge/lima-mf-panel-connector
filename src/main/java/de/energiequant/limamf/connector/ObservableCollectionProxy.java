package de.energiequant.limamf.connector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObservableCollectionProxy<T, C extends Collection<T>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservableCollectionProxy.class);

    private final Supplier<C> constructor;
    private final C present;
    private final Collection<Listener<T>> listeners = new ArrayList<>();

    public interface Listener<T> {
        void onAdded(T obj);

        void onRemoved(T obj);
    }

    public ObservableCollectionProxy(Supplier<C> constructor) {
        this.constructor = constructor;
        present = constructor.get();
    }

    private void notifyListener(Listener<T> listener, BiConsumer<Listener<T>, T> method, T obj) {
        try {
            method.accept(listener, obj);
        } catch (Exception ex) {
            LOGGER.warn("failed to notify listener {} about {}", listener, obj, ex);
        }
    }

    private void notifyListeners(BiConsumer<Listener<T>, T> method, T obj) {
        for (Listener<T> listener : listeners) {
            notifyListener(listener, method, obj);
        }
    }

    public ObservableCollectionProxy<T, C> attach(boolean sendAllPresent, Listener<T> listener) {
        synchronized (this) {
            listeners.add(listener);

            if (sendAllPresent) {
                for (T obj : present) {
                    notifyListener(listener, Listener::onAdded, obj);
                }
            }
        }

        return this;
    }

    public ObservableCollectionProxy<T, C> detach(Listener<T> listener) {
        synchronized (this) {
            boolean removed = listeners.remove(listener);
            if (!removed) {
                LOGGER.warn("tried to detach a listener which has not been attached: {}; {}", listener, this);
            }
        }

        return this;
    }

    public boolean add(T obj) {
        boolean isNew;
        synchronized (this) {
            isNew = present.add(obj);

            if (isNew) {
                notifyListeners(Listener::onAdded, obj);
            }
        }

        return isNew;
    }

    public boolean remove(T obj) {
        boolean wasPresent;
        synchronized (this) {
            wasPresent = present.remove(obj);

            if (wasPresent) {
                notifyListeners(Listener::onRemoved, obj);
            }
        }

        return wasPresent;
    }

    public C getAllPresent() {
        C out = constructor.get();
        synchronized (this) {
            out.addAll(present);
        }
        return out;
    }

    public boolean contains(T obj) {
        synchronized (this) {
            return present.contains(obj);
        }
    }
}
