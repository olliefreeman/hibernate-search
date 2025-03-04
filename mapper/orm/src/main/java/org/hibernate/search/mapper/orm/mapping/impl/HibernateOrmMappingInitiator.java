/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.orm.mapping.impl;

import org.hibernate.MultiTenancyStrategy;
import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.search.engine.cfg.ConfigurationPropertySource;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.OptionalConfigurationProperty;
import org.hibernate.search.engine.environment.bean.BeanHolder;
import org.hibernate.search.engine.environment.bean.BeanResolver;
import org.hibernate.search.engine.environment.bean.BeanReference;
import org.hibernate.search.engine.mapper.mapping.building.spi.MappingBuildContext;
import org.hibernate.search.engine.mapper.mapping.building.spi.MappingConfigurationCollector;
import org.hibernate.search.mapper.orm.bootstrap.impl.HibernateSearchPreIntegrationService;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;
import org.hibernate.search.mapper.orm.common.impl.HibernateOrmUtils;
import org.hibernate.search.mapper.orm.coordination.common.spi.CooordinationStrategy;
import org.hibernate.search.mapper.orm.mapping.HibernateOrmMappingConfigurationContext;
import org.hibernate.search.mapper.orm.mapping.HibernateOrmSearchMappingConfigurer;
import org.hibernate.search.mapper.orm.model.impl.HibernateOrmBasicTypeMetadataProvider;
import org.hibernate.search.mapper.orm.model.impl.HibernateOrmBootstrapIntrospector;
import org.hibernate.search.mapper.orm.coordination.impl.CoordinationConfigurationContextImpl;
import org.hibernate.search.mapper.orm.session.impl.ConfiguredAutomaticIndexingStrategy;
import org.hibernate.search.mapper.pojo.mapping.building.spi.PojoMapperDelegate;
import org.hibernate.search.mapper.pojo.mapping.building.spi.PojoTypeMetadataContributor;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.AnnotationMappingConfigurationContext;
import org.hibernate.search.mapper.pojo.mapping.spi.AbstractPojoMappingInitiator;
import org.hibernate.search.util.common.impl.Closer;
import org.hibernate.search.util.common.reflect.spi.ValueReadHandleFactory;
import org.hibernate.service.ServiceRegistry;

public class HibernateOrmMappingInitiator extends AbstractPojoMappingInitiator<HibernateOrmMappingPartialBuildState>
		implements HibernateOrmMappingConfigurationContext {

	private static final ConfigurationProperty<Boolean> MAPPING_PROCESS_ANNOTATIONS =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.MAPPING_PROCESS_ANNOTATIONS )
					.asBoolean()
					.withDefault( HibernateOrmMapperSettings.Defaults.MAPPING_PROCESS_ANNOTATIONS )
					.build();

	private static final OptionalConfigurationProperty<BeanReference<? extends HibernateOrmSearchMappingConfigurer>> MAPPING_CONFIGURER =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.MAPPING_CONFIGURER )
					.asBeanReference( HibernateOrmSearchMappingConfigurer.class )
					.build();

	public static HibernateOrmMappingInitiator create(Metadata metadata, ReflectionManager reflectionManager,
			ValueReadHandleFactory valueReadHandleFactory, ServiceRegistry serviceRegistry) {
		HibernateOrmBasicTypeMetadataProvider basicTypeMetadataProvider =
				HibernateOrmBasicTypeMetadataProvider.create( metadata );
		HibernateOrmBootstrapIntrospector introspector = HibernateOrmBootstrapIntrospector.create(
				basicTypeMetadataProvider, reflectionManager, valueReadHandleFactory );
		ConfigurationService ormConfigurationService =
				HibernateOrmUtils.getServiceOrFail( serviceRegistry, ConfigurationService.class );
		HibernateSearchPreIntegrationService preIntegrationService =
				HibernateOrmUtils.getServiceOrFail( serviceRegistry, HibernateSearchPreIntegrationService.class );

		return new HibernateOrmMappingInitiator( basicTypeMetadataProvider, introspector, ormConfigurationService,
				preIntegrationService );
	}

	private final HibernateOrmBasicTypeMetadataProvider basicTypeMetadataProvider;
	private final HibernateOrmBootstrapIntrospector introspector;
	private final HibernateSearchPreIntegrationService preIntegrationService;

	private BeanHolder<? extends CooordinationStrategy> coordinationStrategyHolder;
	private ConfiguredAutomaticIndexingStrategy configuredAutomaticIndexingStrategy;

	private HibernateOrmMappingInitiator(HibernateOrmBasicTypeMetadataProvider basicTypeMetadataProvider,
			HibernateOrmBootstrapIntrospector introspector, ConfigurationService ormConfigurationService,
			HibernateSearchPreIntegrationService preIntegrationService) {
		super( introspector );

		this.basicTypeMetadataProvider = basicTypeMetadataProvider;
		this.introspector = introspector;

		/*
		 * This method is called when the session factory is created, and once again when HSearch boots.
		 * It logs a warning when the configuration property is invalid,
		 * so the warning will be logged twice.
		 * Since it only happens when the configuration is invalid,
		 * we can live with this quirk.
		 */
		MultiTenancyStrategy multiTenancyStrategy =
				MultiTenancyStrategy.determineMultiTenancyStrategy( ormConfigurationService.getSettings() );

		multiTenancyEnabled(
				!MultiTenancyStrategy.NONE.equals( multiTenancyStrategy )
		);

		this.preIntegrationService = preIntegrationService;
	}

	public void closeOnFailure() {
		try ( Closer<RuntimeException> closer = new Closer<>() ) {
			closer.push( ConfiguredAutomaticIndexingStrategy::stop, configuredAutomaticIndexingStrategy );
			closer.push( CooordinationStrategy::stop, coordinationStrategyHolder, BeanHolder::get );
			closer.push( BeanHolder::close, coordinationStrategyHolder );
		}
	}

	@Override
	public void configure(MappingBuildContext buildContext,
			MappingConfigurationCollector<PojoTypeMetadataContributor> configurationCollector) {
		BeanResolver beanResolver = buildContext.beanResolver();
		ConfigurationPropertySource propertySource = buildContext.configurationPropertySource();

		addConfigurationContributor(
				new HibernateOrmMappingConfigurationContributor( basicTypeMetadataProvider, introspector )
		);

		CoordinationConfigurationContextImpl coordinationStrategyConfiguration =
				preIntegrationService.coordinationStrategyConfiguration();
		coordinationStrategyHolder = coordinationStrategyConfiguration.strategyHolder();
		configuredAutomaticIndexingStrategy = coordinationStrategyConfiguration.createAutomaticIndexingStrategy();

		// If the automatic indexing strategy uses an event queue,
		// it will need to send events relative to contained entities,
		// and thus contained entities need to have an identity mapping.
		containedEntityIdentityMappingRequired( configuredAutomaticIndexingStrategy.usesEventQueue() );

		// Enable annotation mapping if necessary
		boolean processAnnotations = MAPPING_PROCESS_ANNOTATIONS.get( propertySource );
		if ( processAnnotations ) {
			annotatedTypeDiscoveryEnabled( true );

			AnnotationMappingConfigurationContext annotationMapping = annotationMapping();
			for ( PersistentClass persistentClass : basicTypeMetadataProvider.getPersistentClasses() ) {
				if ( persistentClass.hasPojoRepresentation() ) {
					annotationMapping.add( persistentClass.getMappedClass() );
				}
			}
		}

		// Apply the user-provided mapping configurer if necessary
		MAPPING_CONFIGURER.getAndMap( propertySource, beanResolver::resolve )
				.ifPresent( holder -> {
					try ( BeanHolder<? extends HibernateOrmSearchMappingConfigurer> configurerHolder = holder ) {
						configurerHolder.get().configure( this );
					}
				} );

		super.configure( buildContext, configurationCollector );
	}

	@Override
	protected PojoMapperDelegate<HibernateOrmMappingPartialBuildState> createMapperDelegate() {
		return new HibernateOrmMapperDelegate( basicTypeMetadataProvider, coordinationStrategyHolder,
				configuredAutomaticIndexingStrategy );
	}
}
