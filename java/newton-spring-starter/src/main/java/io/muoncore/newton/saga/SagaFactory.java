package io.muoncore.newton.saga;


import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.muoncore.newton.NewtonEvent;
import io.muoncore.newton.AggregateRootId;
import io.muoncore.newton.command.CommandBus;
import io.muoncore.newton.saga.events.SagaLifecycleEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import io.muoncore.newton.command.CommandIntent;
import io.muoncore.newton.saga.events.SagaEndEvent;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

@Slf4j
public class SagaFactory implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private SagaRepository sagaRepository;
    private CommandBus commandBus;

    private EventBus bus = new EventBus();

    public SagaFactory(SagaRepository sagaRepository, CommandBus commandBus) {
        this.commandBus = commandBus;
        this.sagaRepository = sagaRepository;
    }

    public void notifySagaLifeCycle(AggregateRootId id, SagaLifecycleEvent event) {
        bus.post(event);
    }

    public <ID extends AggregateRootId, T extends Saga<P, ID>, P extends NewtonEvent> SagaMonitor<ID, T> create(Class<T> sagaType, P payload) {
        log.info("Creating new saga of type " + sagaType + " with payload " + payload);
        T saga = (T) loadFromSpringContext(sagaType);
        final T thesaga = saga;
        thesaga.start(payload);

        sagaRepository.saveNewSaga(saga, payload);

        EventedSagaMonitor monitor = new EventedSagaMonitor<>(saga.getId(), sagaType);

        processCommands(saga);

        return monitor;
    }

    public <T extends Saga<? extends NewtonEvent, ID>,ID extends AggregateRootId> SagaMonitor<ID, T>monitor(AggregateRootId sagaId, Class<T> type) {

        Optional<T> saga = sagaRepository.load(sagaId, type);
        if (!saga.isPresent()) {
            throw new IllegalStateException("Saga with ID " + sagaId + " does not exist");
        }
        return new EventedSagaMonitor(sagaId, type);
    }

    private void processCommands(Saga saga) {
        for (CommandIntent intent : (List<CommandIntent>)saga.getNewOperations()) {
            commandBus.dispatch(intent);
        }
        saga.getNewOperations().clear();
    }


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private <ID extends AggregateRootId, P extends NewtonEvent> Saga<P, ID> loadFromSpringContext(Class<? extends Saga> sagaType) {
        return applicationContext.getBean(sagaType);
    }

    class EventedSagaMonitor<ID extends AggregateRootId, T extends Saga<P, ID>, P extends NewtonEvent> implements SagaMonitor<ID, T> {
        private ID id;
        private Class<T> sagaType;
        private List<SagaListener> listeners = new ArrayList<>();
        boolean finished;
        private BlockingQueue<SagaLifecycleEvent> events = new LinkedBlockingQueue<>();

        public EventedSagaMonitor(ID id, Class<T> sagaType) {
            this.id = id;
            this.sagaType = sagaType;
            bus.register(this);
        }

        @Override
        public ID getId() {
            return id;
        }

        private void dispatchListener(SagaListener listener, SagaLifecycleEvent event) {
            if(event instanceof SagaEndEvent) {
                finished = true;
                log.debug("Received SagaEndEvent, and have local ID, releasing listeners and removing local monitor");
                T saga = sagaRepository.load(getId(), sagaType).get();
                listener.onComplete(saga);
                bus.unregister(this);
            }
        }

        public void dispatchListeners(SagaLifecycleEvent event) {
            events.add(event);

            listeners.stream().forEach(listener -> dispatchListener(listener, event));
        }

        @Override
        public void onFinished(SagaListener eventListener) {
            synchronized (listeners) {
                listeners.add(eventListener);

                events.stream().forEach(event -> {
                    dispatchListener(eventListener, event);
                });
            }
        }

        @Subscribe
        public void handle(SagaLifecycleEvent event) {
            if (event.getId().equals(getId())) {
                dispatchListeners(event);
            }
        }

        @Override
        public T waitForCompletion(TimeUnit unit, long timeout) {
            if (!finished) {
                CountDownLatch latch = new CountDownLatch(1);
                onFinished(saga -> {
                    latch.countDown();
                });

                try {
                    latch.await(timeout, unit);
                } catch (InterruptedException e) {
                }
            }
            return sagaRepository.load(id, sagaType).get();
        }
    }

    @Data
    @AllArgsConstructor
    static class LifeCycleEvent {
        private AggregateRootId id;
        private NewtonEvent event;
    }
}
