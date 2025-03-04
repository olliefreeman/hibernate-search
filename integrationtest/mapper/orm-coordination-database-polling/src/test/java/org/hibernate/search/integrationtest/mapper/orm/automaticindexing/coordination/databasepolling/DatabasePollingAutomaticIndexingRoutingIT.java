/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.integrationtest.mapper.orm.automaticindexing.coordination.databasepolling;

import static org.hibernate.search.util.impl.integrationtest.mapper.orm.OrmUtils.withinTransaction;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.search.integrationtest.mapper.orm.coordination.databasepolling.FilteringOutboxEventFinder;
import org.hibernate.search.util.impl.integrationtest.common.rule.BackendMock;
import org.hibernate.search.util.impl.integrationtest.common.stub.backend.document.StubDocumentNode;
import org.hibernate.search.util.impl.integrationtest.mapper.orm.CoordinationStrategyExpectations;
import org.hibernate.search.util.impl.integrationtest.mapper.orm.OrmSetupHelper;
import org.hibernate.search.util.impl.test.annotation.TestForIssue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DatabasePollingAutomaticIndexingRoutingIT {

	@Rule
	public BackendMock backendMock = new BackendMock();

	@Rule
	public OrmSetupHelper setupHelper = OrmSetupHelper.withBackendMock( backendMock )
			.coordinationStrategy( CoordinationStrategyExpectations.outboxPolling() );

	private final FilteringOutboxEventFinder outboxEventFinder = new FilteringOutboxEventFinder();

	private SessionFactory sessionFactory;

	@Before
	public void setup() {
		backendMock.expectAnySchema( RoutedIndexedEntity.NAME );
		sessionFactory = setupHelper.start()
				.withProperty( "hibernate.search.coordination.processors.indexing.outbox_event_finder.provider", outboxEventFinder.provider() )
				.setup( RoutedIndexedEntity.class );
		backendMock.verifyExpectationsMet();
	}

	@Test
	@TestForIssue(jiraKey = "HSEARCH-4186")
	public void processingEventsWithOutdatedRoutingKey() {
		withinTransaction( sessionFactory, session -> {
			RoutedIndexedEntity entity = new RoutedIndexedEntity( 1, "first", RoutedIndexedEntity.Status.FIRST );
			session.persist( entity );
		} );

		backendMock.expectWorks( RoutedIndexedEntity.NAME )
				.add( b -> b.identifier( "1" ).routingKey( "FIRST" )
						.document( StubDocumentNode.document()
								.field( "text", "first" )
								.build() ) );
		outboxEventFinder.showAllEventsUpToNow( sessionFactory );
		backendMock.verifyExpectationsMet();

		// Update the current routing key (but don't trigger indexing yet: events are being filtered)
		withinTransaction( sessionFactory, session -> {
			RoutedIndexedEntity entity = session.find( RoutedIndexedEntity.class, 1 );
			entity.setStatus( RoutedIndexedEntity.Status.SECOND );
			entity.setText( "second" );
		} );

		// Remember the events at this point
		List<Long> eventIdsAtSecondStatus = new ArrayList<>();
		withinTransaction( sessionFactory, session -> {
			eventIdsAtSecondStatus.addAll( outboxEventFinder.findOutboxEventIdsNoFilter( session ) );
		} );

		// Update the current routing key again (but don't trigger indexing yet: events are being filtered)
		withinTransaction( sessionFactory, session -> {
			RoutedIndexedEntity entity = session.find( RoutedIndexedEntity.class, 1 );
			entity.setStatus( RoutedIndexedEntity.Status.THIRD );
			entity.setText( "third" );
		} );

		// This is the point of this test:
		// simulate the processing of the first update event while the second update has already been applied in DB;
		// i.e. the processing of outdated events.
		// This could happen if the outbox table contained many pending events, for example:
		// older events would be processed first, potentially in a different batch than newer events.
		// We expect the current routing key to be up-to-date regardless,
		// because it will be generated from the current state of the database.
		// We also expect "FIRST" to be acknowledged as a possible previous route,
		// because it was mentioned in the (outdated) events.
		backendMock.expectWorks( RoutedIndexedEntity.NAME )
				.delete( b -> b.identifier( "1" ).routingKey( "FIRST" ) )
				.delete( b -> b.identifier( "1" ).routingKey( "SECOND" ) )
				.addOrUpdate( b -> b.identifier( "1" ).routingKey( "THIRD" )
						.document( StubDocumentNode.document()
								.field( "text", "third" )
								.build() ) );
		outboxEventFinder.showOnlyEvents( eventIdsAtSecondStatus );
		backendMock.verifyExpectationsMet();

		// Simulate the processing of all remaining events
		backendMock.expectWorks( RoutedIndexedEntity.NAME )
				.delete( b -> b.identifier( "1" ).routingKey( "SECOND" ) )
				.addOrUpdate( b -> b.identifier( "1" ).routingKey( "THIRD" )
						.document( StubDocumentNode.document()
								.field( "text", "third" )
								.build() ) );
		outboxEventFinder.showAllEventsUpToNow( sessionFactory );
		backendMock.verifyExpectationsMet();
	}

}
