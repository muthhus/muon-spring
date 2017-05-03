package io.muoncore.newton.eventsource.muon;

import io.muoncore.newton.AggregateRoot;
import io.muoncore.newton.AggregateRootId;
import io.muoncore.newton.NewtonEvent;
import io.muoncore.newton.eventsource.AggregateNotFoundException;
import io.muoncore.newton.eventsource.EventSourceRepository;
import io.muoncore.newton.eventsource.OptimisticLockException;
import io.muoncore.newton.utils.muon.MuonLookupUtils;
import io.muoncore.protocol.event.ClientEvent;
import io.muoncore.protocol.event.Event;
import io.muoncore.protocol.event.client.AggregateEventClient;
import io.muoncore.protocol.event.client.EventClient;
import io.muoncore.protocol.event.client.EventReplayMode;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Slf4j
public class MuonEventSourceRepository<A extends AggregateRoot<? extends AggregateRootId>> implements EventSourceRepository<A> {

	private Class<A> aggregateType;
	private AggregateEventClient aggregateEventClient;
	private EventClient eventClient;
	private final String boundedContextName;

	public MuonEventSourceRepository(Class<A> type, AggregateEventClient aggregateEventClient, EventClient eventClient, String boundedContextName) {
		aggregateType = type;
		this.aggregateEventClient = aggregateEventClient;
		this.eventClient = eventClient;
		this.boundedContextName = boundedContextName;
	}

	@Override
	public A load(AggregateRootId aggregateIdentifier) {
		try {
			A aggregate = aggregateType.newInstance();
			replayEvents(aggregateIdentifier).forEach(aggregate::handleEvent);
			return aggregate;
		} catch (AggregateNotFoundException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Unable to load aggregate: ".concat(aggregateType.getSimpleName()), e);
		}
	}

	@Override
	public A load(AggregateRootId aggregateIdentifier, Long version) {
		try {
			A aggregate = (A) aggregateType.newInstance();
			replayEvents(aggregateIdentifier).forEach(aggregate::handleEvent);
			if (aggregate.getVersion() != version) throw new OptimisticLockException(aggregateIdentifier, version, aggregate.getVersion());
			return aggregate;
		} catch (AggregateNotFoundException | OptimisticLockException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Unable to load aggregate: ".concat(aggregateType.getSimpleName()), e);
		}
	}

	@Override
	public A newInstance(Callable<A> factoryMethod) {
		try {
			A result = factoryMethod.call();
			save(result);
			return result;
		} catch (Exception e) {
			throw new IllegalStateException("Unable to create new instance: ".concat(aggregateType.getName()), e);
		}
	}

	@Override
	public void save(A aggregate) {
		emitForAggregatePersistence(aggregate);
		emitForStreamProcessing(aggregate);
	}

  private Publisher<NewtonEvent> subscribe(AggregateRootId aggregateIdentifier, EventReplayMode mode) {
	  return sub -> eventClient.replay("/aggregate/" + aggregateIdentifier.getValue(), mode, new Subscriber<Event>() {
        public void onSubscribe(Subscription s) {
          sub.onSubscribe(s);
        }

        public void onNext(Event o) {
          sub.onNext(o.getPayload(MuonLookupUtils.getDomainClass(o)));
        }

        public void onError(Throwable t) {
          sub.onError(t);
        }

        public void onComplete() {
          sub.onComplete();
        }
      });
  }

  @Override
  public Publisher<NewtonEvent> replay(AggregateRootId aggregateIdentifier) {
	  return subscribe(aggregateIdentifier, EventReplayMode.REPLAY_ONLY);
  }

  @Override
  public Publisher<NewtonEvent> subscribeColdHot(AggregateRootId aggregateIdentifier) {
    return subscribe(aggregateIdentifier, EventReplayMode.REPLAY_THEN_LIVE);
  }

  @Override
  public Publisher<NewtonEvent> subscribeHot(AggregateRootId aggregateIdentifier) {
    return subscribe(aggregateIdentifier, EventReplayMode.LIVE_ONLY);
  }

  private List<NewtonEvent> replayEvents(AggregateRootId id) {
		try {
			List<NewtonEvent> events = aggregateEventClient.loadAggregateRoot(id.toString())
				.stream()
				.map(event -> event.getPayload(MuonLookupUtils.getDomainClass(event)))
				.collect(Collectors.toList());

			if (events.size() == 0) throw new AggregateNotFoundException(id);

			return events;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void emitForAggregatePersistence(A aggregate) {
		aggregateEventClient.publishDomainEvents(
			aggregate.getId().toString(),
			aggregate.getNewOperations());
	}

	private void emitForStreamProcessing(A aggregate) {
		String streamName = boundedContextName + "/" + aggregate.getClass().getSimpleName();
		log.debug("Emitting event on " + streamName);
		aggregate.getNewOperations().forEach(
			event -> eventClient.event(
				ClientEvent
					.ofType(event.getClass().getSimpleName())
					.stream(streamName)
					.payload(event)
					.build()
			));
	}
}
