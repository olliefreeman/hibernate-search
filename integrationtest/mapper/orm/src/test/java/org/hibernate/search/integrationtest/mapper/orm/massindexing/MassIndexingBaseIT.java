/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.integrationtest.mapper.orm.massindexing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Fail.fail;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.Session;
import org.hibernate.search.engine.backend.work.execution.DocumentCommitStrategy;
import org.hibernate.search.engine.backend.work.execution.DocumentRefreshStrategy;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;
import org.hibernate.search.mapper.orm.mapping.SearchMapping;
import org.hibernate.search.mapper.orm.massindexing.MassIndexer;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.util.common.SearchException;
import org.hibernate.search.util.impl.integrationtest.common.rule.BackendMock;
import org.hibernate.search.util.impl.integrationtest.common.stub.backend.index.StubSchemaManagementWork;
import org.hibernate.search.util.impl.integrationtest.mapper.orm.OrmSetupHelper;
import org.hibernate.search.util.impl.integrationtest.mapper.orm.ReusableOrmSetupHolder;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;

/**
 * Very basic test to probe an use of {@link MassIndexer} api.
 */
public class MassIndexingBaseIT {

	public static final String TITLE_1 = "Oliver Twist";
	public static final String AUTHOR_1 = "Charles Dickens";
	public static final String TITLE_2 = "Ulysses";
	public static final String AUTHOR_2 = "James Joyce";
	public static final String TITLE_3 = "Frankenstein";
	public static final String AUTHOR_3 = "Mary Shelley";

	@ClassRule
	public static BackendMock backendMock = new BackendMock();

	@ClassRule
	public static ReusableOrmSetupHolder setupHolder = ReusableOrmSetupHolder.withBackendMock( backendMock );

	@Rule
	public MethodRule setupHolderMethodRule = setupHolder.methodRule();

	@ReusableOrmSetupHolder.Setup
	public void setup(OrmSetupHelper.SetupContext setupContext) {
		backendMock.expectAnySchema( Book.INDEX );

		setupContext.withPropertyRadical( HibernateOrmMapperSettings.Radicals.AUTOMATIC_INDEXING_ENABLED, false )
				.withAnnotatedTypes( Book.class );
	}

	@Before
	public void initData() {
		setupHolder.runInTransaction( session -> {
			session.persist( new Book( 1, TITLE_1, AUTHOR_1 ) );
			session.persist( new Book( 2, TITLE_2, AUTHOR_2 ) );
			session.persist( new Book( 3, TITLE_3, AUTHOR_3 ) );
		} );
	}

	@Test
	public void defaultMassIndexerStartAndWait() throws Exception {
		setupHolder.runNoTransaction( session -> {
			SearchSession searchSession = Search.session( session );
			MassIndexer indexer = searchSession.massIndexer();

			// add operations on indexes can follow any random order,
			// since they are executed by different threads
			backendMock.expectWorks(
					Book.INDEX, DocumentCommitStrategy.NONE, DocumentRefreshStrategy.NONE
			)
					.add( "1", b -> b
							.field( "title", TITLE_1 )
							.field( "author", AUTHOR_1 )
					)
					.add( "2", b -> b
							.field( "title", TITLE_2 )
							.field( "author", AUTHOR_2 )
					)
					.add( "3", b -> b
							.field( "title", TITLE_3 )
							.field( "author", AUTHOR_3 )
					);

			// purgeAtStart and mergeSegmentsAfterPurge are enabled by default,
			// so we expect 1 purge, 1 mergeSegments and 1 flush calls in this order:
			backendMock.expectIndexScaleWorks( Book.INDEX, session.getTenantIdentifier() )
					.purge()
					.mergeSegments()
					.flush()
					.refresh();

			try {
				indexer.startAndWait();
			}
			catch (InterruptedException e) {
				fail( "Unexpected InterruptedException: " + e.getMessage() );
			}

		} );

		backendMock.verifyExpectationsMet();
	}

	@Test
	public void dropAndCreateSchemaOnStart() {
		setupHolder.runNoTransaction( session -> {
			SearchSession searchSession = Search.session( session );
			MassIndexer indexer = searchSession.massIndexer().dropAndCreateSchemaOnStart( true );

			// add operations on indexes can follow any random order,
			// since they are executed by different threads
			backendMock.expectWorks(
					Book.INDEX, DocumentCommitStrategy.NONE, DocumentRefreshStrategy.NONE
			)
					.add( "1", b -> b
							.field( "title", TITLE_1 )
							.field( "author", AUTHOR_1 )
					)
					.add( "2", b -> b
							.field( "title", TITLE_2 )
							.field( "author", AUTHOR_2 )
					)
					.add( "3", b -> b
							.field( "title", TITLE_3 )
							.field( "author", AUTHOR_3 )
					);

			backendMock.expectSchemaManagementWorks( Book.INDEX )
					.work( StubSchemaManagementWork.Type.DROP_AND_CREATE );

			// purgeAtStart and mergeSegmentsAfterPurge are enabled by default,
			// so we expect 1 purge, 1 optimize and 1 flush calls in this order:
			backendMock.expectIndexScaleWorks( Book.INDEX, session.getTenantIdentifier() )
					.purge()
					.mergeSegments()
					.flush()
					.refresh();

			try {
				indexer.startAndWait();
			}
			catch (InterruptedException e) {
				fail( "Unexpected InterruptedException: " + e.getMessage() );
			}

		} );

		backendMock.verifyExpectationsMet();
	}

	@Test
	public void mergeSegmentsOnFinish() {
		setupHolder.runNoTransaction( session -> {
			SearchSession searchSession = Search.session( session );
			MassIndexer indexer = searchSession.massIndexer().mergeSegmentsOnFinish( true );

			// add operations on indexes can follow any random order,
			// since they are executed by different threads
			backendMock.expectWorks(
					Book.INDEX, DocumentCommitStrategy.NONE, DocumentRefreshStrategy.NONE
			)
					.add( "1", b -> b
							.field( "title", TITLE_1 )
							.field( "author", AUTHOR_1 )
					)
					.add( "2", b -> b
							.field( "title", TITLE_2 )
							.field( "author", AUTHOR_2 )
					)
					.add( "3", b -> b
							.field( "title", TITLE_3 )
							.field( "author", AUTHOR_3 )
					);

			// purgeAtStart and mergeSegmentsAfterPurge are enabled by default,
			// and optimizeOnFinish is enabled explicitly,
			// so we expect 1 purge, 2 optimize and 1 flush calls in this order:
			backendMock.expectIndexScaleWorks( Book.INDEX, session.getTenantIdentifier() )
					.purge()
					.mergeSegments()
					.mergeSegments()
					.flush()
					.refresh();

			try {
				indexer.startAndWait();
			}
			catch (InterruptedException e) {
				fail( "Unexpected InterruptedException: " + e.getMessage() );
			}

		} );

		backendMock.verifyExpectationsMet();
	}

	@Test
	public void fromMappingWithoutSession() throws Exception {
		SearchMapping searchMapping = Search.mapping( setupHolder.sessionFactory() );
		MassIndexer indexer = searchMapping.scope( Object.class ).massIndexer();

		// add operations on indexes can follow any random order,
		// since they are executed by different threads
		backendMock.expectWorks(
				Book.INDEX, DocumentCommitStrategy.NONE, DocumentRefreshStrategy.NONE
		)
				.add( "1", b -> b
						.field( "title", TITLE_1 )
						.field( "author", AUTHOR_1 )
				)
				.add( "2", b -> b
						.field( "title", TITLE_2 )
						.field( "author", AUTHOR_2 )
				)
				.add( "3", b -> b
						.field( "title", TITLE_3 )
						.field( "author", AUTHOR_3 )
				);

		// purgeAtStart and mergeSegmentsAfterPurge are enabled by default,
		// so we expect 1 purge, 1 mergeSegments and 1 flush calls in this order:
		backendMock.expectIndexScaleWorks( Book.INDEX )
				.purge()
				.mergeSegments()
				.flush()
				.refresh();

		try {
			indexer.startAndWait();
		}
		catch (InterruptedException e) {
			fail( "Unexpected InterruptedException: " + e.getMessage() );
		}

		backendMock.verifyExpectationsMet();
	}

	@Test
	public void reuseSearchSessionAfterOrmSessionIsClosed_createMassIndexer() {
		Session session = setupHolder.sessionFactory().openSession();
		SearchSession searchSession = Search.session( session );
		// a SearchSession instance is created lazily,
		// so we need to use it to have an instance of it
		searchSession.massIndexer();
		session.close();

		assertThatThrownBy( () -> {
			searchSession.massIndexer();
		} )
				.isInstanceOf( SearchException.class )
				.hasMessageContainingAll( "Unable to access Hibernate ORM session", "is closed" );
	}

	@Test
	public void lazyCreateSearchSessionAfterOrmSessionIsClosed_createMassIndexer() {
		Session session = setupHolder.sessionFactory().openSession();
		// Search session is not created, since we don't use it
		SearchSession searchSession = Search.session( session );
		session.close();

		assertThatThrownBy( () -> {
			searchSession.massIndexer();
		} )
				.isInstanceOf( SearchException.class )
				.hasMessageContainingAll( "Unable to access Hibernate ORM session", "is closed" );
	}

	@Entity
	@Table(name = "book")
	@Indexed(index = Book.INDEX)
	public static class Book {

		public static final String INDEX = "Book";

		@Id
		private Integer id;

		@GenericField
		private String title;

		@GenericField
		private String author;

		public Book() {
		}

		public Book(Integer id, String title, String author) {
			this.id = id;
			this.title = title;
			this.author = author;
		}

		public Integer getId() {
			return id;
		}

		public String getTitle() {
			return title;
		}

		public String getAuthor() {
			return author;
		}
	}
}
