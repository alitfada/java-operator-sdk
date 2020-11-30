package io.javaoperatorsdk.operator.processing.event;

import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.processing.DefaultEventHandler;
import io.javaoperatorsdk.operator.processing.KubernetesResourceUtils;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class DefaultEventSourceManager implements EventSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventSourceManager.class);

    private final ReentrantLock lock = new ReentrantLock();
    private Map<String, Map<String, EventSource>> eventSources = new ConcurrentHashMap<>();
    private CustomResourceEventSource customResourceEventSource;
    private DefaultEventHandler defaultEventHandler;

    public DefaultEventSourceManager(DefaultEventHandler defaultEventHandler) {
        this.defaultEventHandler = defaultEventHandler;
    }

    public void registerCustomResourceEventSource(CustomResourceEventSource customResourceEventSource) {
        this.customResourceEventSource = customResourceEventSource;
        this.customResourceEventSource.addedToEventManager();
    }

    @Override
    public <T extends EventSource> void registerEventSource(CustomResource customResource, String name, T eventSource) {
        try {
            lock.lock();
            Map<String, EventSource> eventSourceList = eventSources.get(KubernetesResourceUtils.getUID(customResource));
            if (eventSourceList == null) {
                eventSourceList = new HashMap<>(1);
                eventSources.put(KubernetesResourceUtils.getUID(customResource), eventSourceList);
            }
            if (eventSourceList.get(name) != null) {
                throw new IllegalStateException("Event source with name already registered. Resource id: "
                        + KubernetesResourceUtils.getUID(customResource) + ", event source name: " + name);
            }
            eventSourceList.put(name, eventSource);
            eventSource.setEventHandler(defaultEventHandler);
            eventSource.eventSourceRegisteredForResource(customResource);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T extends EventSource> T registerEventSourceIfNotRegistered(CustomResource customResource, String name, Supplier<T> eventSourceSupplier) {
        try {
            lock.lock();
            if (eventSources.get(KubernetesResourceUtils.getUID(customResource)) == null ||
                    eventSources.get(KubernetesResourceUtils.getUID(customResource)).get(name) == null) {
                EventSource eventSource = eventSourceSupplier.get();
                registerEventSource(customResource, name, eventSource);
                return (T) eventSource;
            }
            return (T) eventSources.get(KubernetesResourceUtils.getUID(customResource)).get(name);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<EventSource> deRegisterEventSource(String customResourceUid, String name) {
        try {
            lock.lock();
            Map<String, EventSource> eventSources = this.eventSources.get(customResourceUid);
            if (eventSources == null || !eventSources.containsKey(name)) {
                log.warn("Event producer: {} not found for custom resource: {}", name, customResourceUid);
                return Optional.empty();
            } else {
                EventSource eventSource = eventSources.remove(name);
                eventSource.eventSourceDeRegisteredForResource(customResourceUid);
                return Optional.of(eventSource);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, EventSource> getRegisteredEventSources(String customResourceUid) {
        Map<String, EventSource> eventSourceMap = eventSources.get(customResourceUid);
        return eventSourceMap != null ? eventSourceMap : Collections.EMPTY_MAP;
    }

    public void controllerExecuted(ExecutionDescriptor executionDescriptor) {
        String uid = executionDescriptor.getExecutionScope().getCustomResourceUid();
        Map<String, EventSource> sources = getRegisteredEventSources(uid);
        sources.values().forEach(es -> es.controllerExecuted(executionDescriptor));
    }

    public void cleanup(String customResourceUid) {
        getRegisteredEventSources(customResourceUid).keySet().forEach(k -> deRegisterEventSource(customResourceUid, k));
        eventSources.remove(customResourceUid);
    }

}