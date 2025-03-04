/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.elasticsearch.impl;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

import org.hibernate.search.backend.elasticsearch.ElasticsearchVersion;
import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchBackendSettings;
import org.hibernate.search.backend.elasticsearch.client.impl.ElasticsearchClientUtils;
import org.hibernate.search.backend.elasticsearch.client.spi.ElasticsearchClient;
import org.hibernate.search.backend.elasticsearch.client.spi.ElasticsearchClientFactory;
import org.hibernate.search.backend.elasticsearch.client.spi.ElasticsearchClientImplementor;
import org.hibernate.search.backend.elasticsearch.dialect.impl.ElasticsearchDialectFactory;
import org.hibernate.search.backend.elasticsearch.dialect.protocol.impl.ElasticsearchProtocolDialect;
import org.hibernate.search.backend.elasticsearch.gson.spi.GsonProvider;
import org.hibernate.search.backend.elasticsearch.link.impl.ElasticsearchLink;
import org.hibernate.search.backend.elasticsearch.logging.impl.Log;
import org.hibernate.search.backend.elasticsearch.lowlevel.syntax.metadata.impl.ElasticsearchIndexMetadataSyntax;
import org.hibernate.search.backend.elasticsearch.lowlevel.syntax.search.impl.ElasticsearchSearchSyntax;
import org.hibernate.search.backend.elasticsearch.resources.impl.BackendThreads;
import org.hibernate.search.backend.elasticsearch.search.query.impl.ElasticsearchSearchResultExtractorFactory;
import org.hibernate.search.backend.elasticsearch.work.builder.factory.impl.ElasticsearchWorkBuilderFactory;
import org.hibernate.search.engine.cfg.ConfigurationPropertySource;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.OptionalConfigurationProperty;
import org.hibernate.search.engine.environment.bean.BeanHolder;
import org.hibernate.search.engine.environment.bean.BeanResolver;
import org.hibernate.search.util.common.AssertionFailure;
import org.hibernate.search.util.common.impl.Closer;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;

import com.google.gson.GsonBuilder;

class ElasticsearchLinkImpl implements ElasticsearchLink {
	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	static final OptionalConfigurationProperty<ElasticsearchVersion> VERSION =
			ConfigurationProperty.forKey( ElasticsearchBackendSettings.VERSION )
					.as( ElasticsearchVersion.class, ElasticsearchVersion::of )
					.build();

	private static final ConfigurationProperty<Boolean> VERSION_CHECK_ENABLED =
			ConfigurationProperty.forKey( ElasticsearchBackendSettings.VERSION_CHECK_ENABLED )
					.asBoolean()
					.withDefault( ElasticsearchBackendSettings.Defaults.VERSION_CHECK_ENABLED )
					.build();

	private static final ConfigurationProperty<Integer> SCROLL_TIMEOUT =
			ConfigurationProperty.forKey( ElasticsearchBackendSettings.SCROLL_TIMEOUT )
					.asIntegerStrictlyPositive()
					.withDefault( ElasticsearchBackendSettings.Defaults.SCROLL_TIMEOUT )
					.build();

	private final BeanHolder<? extends ElasticsearchClientFactory> clientFactoryHolder;
	private final BackendThreads threads;
	private final GsonProvider defaultGsonProvider;
	private final boolean logPrettyPrinting;
	private final ElasticsearchDialectFactory dialectFactory;
	private final Optional<ElasticsearchVersion> configuredVersionOnBackendCreationOptional;

	private ElasticsearchClientImplementor clientImplementor;
	private ElasticsearchVersion elasticsearchVersion;
	private GsonProvider gsonProvider;
	private ElasticsearchIndexMetadataSyntax indexMetadataSyntax;
	private ElasticsearchSearchSyntax searchSyntax;
	private ElasticsearchWorkBuilderFactory workBuilderFactory;
	private ElasticsearchSearchResultExtractorFactory searchResultExtractorFactory;
	private Integer scrollTimeout;

	ElasticsearchLinkImpl(BeanHolder<? extends ElasticsearchClientFactory> clientFactoryHolder,
			BackendThreads threads, GsonProvider defaultGsonProvider, boolean logPrettyPrinting,
			ElasticsearchDialectFactory dialectFactory,
			Optional<ElasticsearchVersion> configuredVersionOnBackendCreationOptional) {
		this.clientFactoryHolder = clientFactoryHolder;
		this.threads = threads;
		this.defaultGsonProvider = defaultGsonProvider;
		this.logPrettyPrinting = logPrettyPrinting;
		this.dialectFactory = dialectFactory;
		this.configuredVersionOnBackendCreationOptional = configuredVersionOnBackendCreationOptional;
	}

	@Override
	public ElasticsearchClient getClient() {
		checkStarted();
		return clientImplementor;
	}

	@Override
	public GsonProvider getGsonProvider() {
		checkStarted();
		return gsonProvider;
	}

	@Override
	public ElasticsearchIndexMetadataSyntax getIndexMetadataSyntax() {
		checkStarted();
		return indexMetadataSyntax;
	}

	@Override
	public ElasticsearchSearchSyntax getSearchSyntax() {
		checkStarted();
		return searchSyntax;
	}

	@Override
	public ElasticsearchWorkBuilderFactory getWorkBuilderFactory() {
		checkStarted();
		return workBuilderFactory;
	}

	@Override
	public ElasticsearchSearchResultExtractorFactory getSearchResultExtractorFactory() {
		checkStarted();
		return searchResultExtractorFactory;
	}

	@Override
	public Integer getScrollTimeout() {
		checkStarted();
		return scrollTimeout;
	}

	ElasticsearchVersion getElasticsearchVersion() {
		checkStarted();
		return elasticsearchVersion;
	}

	void onStart(BeanResolver beanResolver, ConfigurationPropertySource propertySource) {
		if ( clientImplementor == null ) {
			clientImplementor = clientFactoryHolder.get().create(
					beanResolver, propertySource, threads.getThreadProvider(), threads.getPrefix(),
					threads.getWorkExecutor(), defaultGsonProvider
			);
			clientFactoryHolder.close(); // We won't need it anymore

			elasticsearchVersion = initVersion( propertySource );

			ElasticsearchProtocolDialect protocolDialect = dialectFactory.createProtocolDialect( elasticsearchVersion );
			gsonProvider = GsonProvider.create( GsonBuilder::new, logPrettyPrinting );
			indexMetadataSyntax = protocolDialect.createIndexMetadataSyntax();
			searchSyntax = protocolDialect.createSearchSyntax();
			workBuilderFactory = protocolDialect.createWorkBuilderFactory( gsonProvider );
			searchResultExtractorFactory = protocolDialect.createSearchResultExtractorFactory();
			scrollTimeout = SCROLL_TIMEOUT.get( propertySource );
		}
	}

	void onStop() {
		try ( Closer<RuntimeException> closer = new Closer<>() ) {
			closer.push( BeanHolder::close, clientFactoryHolder ); // Just in case start() was not called
			closer.push( ElasticsearchClientImplementor::close, clientImplementor );
		}
	}

	private void checkStarted() {
		if ( clientImplementor == null ) {
			throw new AssertionFailure(
					"Attempt to retrieve Elasticsearch client or related information before the backend was started."
			);
		}
	}

	private ElasticsearchVersion initVersion(ConfigurationPropertySource propertySource) {
		boolean versionCheckEnabled = VERSION_CHECK_ENABLED.get( propertySource );
		Optional<ElasticsearchVersion> configuredVersionOptional = VERSION.getAndTransform( propertySource,
				configuredVersionOnStartOptional -> {
					Optional<ElasticsearchVersion> resultOptional;
					if ( configuredVersionOnStartOptional.isPresent() ) {
						// Allow overriding the version on start,
						// but expect it to match the version configured on backend creation (if any)
						if ( configuredVersionOnBackendCreationOptional.isPresent()
								&& !configuredVersionOnBackendCreationOptional.get()
								.matches( configuredVersionOnStartOptional.get() ) ) {
							throw log.incompatibleElasticsearchVersionOnStart(
									configuredVersionOnBackendCreationOptional.get(),
									configuredVersionOnStartOptional.get() );
						}
						resultOptional = configuredVersionOnStartOptional;
					}
					else {
						// Default to the version configured when the backend was created
						resultOptional = configuredVersionOnBackendCreationOptional;
					}
					if ( !versionCheckEnabled
							&& ( !resultOptional.isPresent() || !resultOptional.get().minor().isPresent() ) ) {
						throw log.impreciseElasticsearchVersionWhenNoVersionCheck(
								VERSION_CHECK_ENABLED.resolveOrRaw( propertySource ) );
					}
					return resultOptional;
				} );

		if ( versionCheckEnabled ) {
			ElasticsearchVersion versionFromCluster =
					ElasticsearchClientUtils.getElasticsearchVersion( clientImplementor );
			if ( configuredVersionOptional.isPresent() ) {
				ElasticsearchVersion configuredVersion = configuredVersionOptional.get();
				if ( !configuredVersion.matches( versionFromCluster ) ) {
					throw log.unexpectedElasticsearchVersion( configuredVersion, versionFromCluster );
				}
			}
			return versionFromCluster;
		}
		else {
			// In this case we know the optional is non-empty, see above.
			return configuredVersionOptional.get();
		}
	}
}
