/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.util.impl.integrationtest.mapper.orm.coordination.localheap;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.hibernate.Session;
import org.hibernate.search.engine.backend.common.spi.MultiEntityOperationExecutionReport;
import org.hibernate.search.engine.backend.orchestration.spi.BatchedWorkProcessor;
import org.hibernate.search.mapper.orm.automaticindexing.spi.AutomaticIndexingMappingContext;
import org.hibernate.search.mapper.orm.automaticindexing.spi.AutomaticIndexingQueueEventProcessingPlan;
import org.hibernate.search.mapper.orm.common.EntityReference;
import org.hibernate.search.mapper.orm.logging.impl.Log;
import org.hibernate.search.mapper.pojo.work.spi.PojoIndexingQueueEventPayload;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;
import org.hibernate.search.util.common.serialization.spi.SerializationUtils;

public class LocalHeapQueueProcessor implements BatchedWorkProcessor {

	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private final AutomaticIndexingMappingContext mapping;

	private Session session;
	private AutomaticIndexingQueueEventProcessingPlan plan;
	private final List<LocalHeapQueueIndexingEvent> eventsInBatch = new ArrayList<>();

	public LocalHeapQueueProcessor(AutomaticIndexingMappingContext mapping) {
		this.mapping = mapping;
	}

	@Override
	public void beginBatch() {
		if ( session == null ) {
			session = mapping.sessionFactory().openSession();
		}
		plan = mapping.createIndexingQueueEventProcessingPlan( session );
	}

	public void process(LocalHeapQueueIndexingEvent event) {
		PojoIndexingQueueEventPayload payload = SerializationUtils.deserialize(
				PojoIndexingQueueEventPayload.class, event.payload );
		plan.append( event.entityName, event.serializedId, payload );
		eventsInBatch.add( event );
	}

	@Override
	public CompletableFuture<?> endBatch() {
		try {
			return plan.executeAndReport()
					.whenComplete( this::reportBackendResult );
		}
		catch (Throwable t) {
			reportMapperFailure( t );
			return CompletableFuture.completedFuture( null );
		}
		finally {
			// We're sure no entity will be loaded after executeAndReport() returns a future.
			// Make sure the next batch actually loads entities from the database:
			// if it relied on the first-level cache from a previous batch,
			// it could end up indexing out-of-date data.
			if ( session != null ) {
				session.clear();
			}
		}
	}

	private void reportMapperFailure(Throwable throwable) {
		try {
			releaseSession();
			// Something failed, but we don't know what.
			// Assume all events failed.
			reportAllEventsFailure( throwable );
		}
		catch (Throwable t) {
			throwable.addSuppressed( t );
		}
	}

	private void reportBackendResult(MultiEntityOperationExecutionReport<EntityReference> report,
			Throwable throwable) {
		if ( throwable != null ) {
			// Something failed, but we don't know what.
			// Assume all events failed.
			reportAllEventsFailure( throwable );
		}
		else if ( report.throwable().isPresent() ) {
			// Something failed, but we know which entities are affected.
			Throwable reportThrowable = report.throwable().get();
			reportSpecificEntitiesIndexingFailure( reportThrowable, report.failingEntityReferences() );
		}
	}

	private void reportAllEventsFailure(Throwable throwable) {
		// Report that events weren't correctly processed
		for ( LocalHeapQueueIndexingEvent event : eventsInBatch ) {
			event.markAsFailed( throwable );
		}
	}

	private void reportSpecificEntitiesIndexingFailure(Throwable throwable,
			List<EntityReference> failingEntityReferences) {
		// In a real implementation we would put these references in a queue, to re-try later.
		// But here it's just for testing.
		log.errorf( throwable, "Failed to reindex entities '%s'", failingEntityReferences );
	}

	@Override
	public void complete() {
		// Parking: release the session/connection
		releaseSession();
		eventsInBatch.clear();
	}

	private void releaseSession() {
		try {
			if ( session != null ) {
				session.close();
			}
		}
		finally {
			session = null;
		}
	}
}
