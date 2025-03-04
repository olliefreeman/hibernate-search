/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.pojo.work.impl;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.hibernate.search.engine.backend.common.spi.EntityReferenceFactory;
import org.hibernate.search.engine.backend.common.spi.MultiEntityOperationExecutionReport;
import org.hibernate.search.mapper.pojo.automaticindexing.impl.PojoReindexingCollector;
import org.hibernate.search.mapper.pojo.loading.impl.PojoLoadingPlan;
import org.hibernate.search.mapper.pojo.loading.impl.PojoMultiLoaderLoadingPlan;
import org.hibernate.search.mapper.pojo.logging.impl.Log;
import org.hibernate.search.mapper.pojo.model.spi.PojoRawTypeIdentifier;
import org.hibernate.search.mapper.pojo.model.spi.PojoRuntimeIntrospector;
import org.hibernate.search.mapper.pojo.route.DocumentRoutesDescriptor;
import org.hibernate.search.mapper.pojo.work.spi.PojoIndexingPlan;
import org.hibernate.search.mapper.pojo.work.spi.PojoWorkSessionContext;
import org.hibernate.search.util.common.AssertionFailure;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;

public class PojoIndexingPlanImpl implements PojoIndexingPlan, PojoLoadingPlanProvider, PojoReindexingCollector {

	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private final PojoWorkIndexedTypeContextProvider indexedTypeContextProvider;
	private final PojoWorkContainedTypeContextProvider containedTypeContextProvider;
	private final PojoWorkSessionContext sessionContext;
	private final PojoRuntimeIntrospector introspector;
	private final PojoIndexingPlanStrategy strategy;

	// Use a LinkedHashMap for deterministic iteration
	protected final Map<PojoRawTypeIdentifier<?>, PojoIndexedTypeIndexingPlan<?, ?>> indexedTypeDelegates = new LinkedHashMap<>();
	protected final Map<PojoRawTypeIdentifier<?>, PojoContainedTypeIndexingPlan<?, ?>> containedTypeDelegates = new LinkedHashMap<>();

	private boolean isProcessing = false;
	private boolean mayRequireLoading = false;
	private PojoLoadingPlan<Object> loadingPlan = null;

	public PojoIndexingPlanImpl(PojoWorkIndexedTypeContextProvider indexedTypeContextProvider,
			PojoWorkContainedTypeContextProvider containedTypeContextProvider,
			PojoWorkSessionContext sessionContext,
			PojoIndexingPlanStrategy strategy) {
		this.indexedTypeContextProvider = indexedTypeContextProvider;
		this.containedTypeContextProvider = containedTypeContextProvider;
		this.sessionContext = sessionContext;
		this.introspector = sessionContext.runtimeIntrospector();
		this.strategy = strategy;
	}

	@Override
	public void add(PojoRawTypeIdentifier<?> typeIdentifier, Object providedId,
			DocumentRoutesDescriptor providedRoutes, Object entity) {
		AbstractPojoTypeIndexingPlan<?, ?, ?> delegate = getDelegate( typeIdentifier );
		if ( ! mayRequireLoading && entity == null ) {
			mayRequireLoading = true;
		}
		delegate.add( providedId, providedRoutes, entity );
	}

	@Override
	public void addOrUpdate(PojoRawTypeIdentifier<?> typeIdentifier, Object providedId,
			DocumentRoutesDescriptor providedRoutes, Object entity,
			boolean forceSelfDirty, boolean forceContainingDirty, BitSet dirtyPaths) {
		AbstractPojoTypeIndexingPlan<?, ?, ?> delegate = getDelegate( typeIdentifier );
		if ( ! mayRequireLoading && entity == null ) {
			mayRequireLoading = true;
		}
		delegate.addOrUpdate( providedId, providedRoutes, entity, dirtyPaths, forceSelfDirty, forceContainingDirty );
	}

	@Override
	public void delete(PojoRawTypeIdentifier<?> typeIdentifier, Object providedId,
			DocumentRoutesDescriptor providedRoutes,
			Object entity) {
		AbstractPojoTypeIndexingPlan<?, ?, ?> delegate = getDelegate( typeIdentifier );
		delegate.delete( providedId, providedRoutes, entity );
	}

	@Override
	public void addOrUpdateOrDelete(PojoRawTypeIdentifier<?> typeIdentifier, Object providedId,
			DocumentRoutesDescriptor providedRoutes, boolean forceSelfDirty, boolean forceContainingDirty,
			BitSet dirtyPaths) {
		AbstractPojoTypeIndexingPlan<?, ?, ?> delegate = getDelegate( typeIdentifier );
		if ( ! mayRequireLoading ) {
			mayRequireLoading = true;
		}
		delegate.addOrUpdateOrDelete( providedId, providedRoutes, dirtyPaths, forceSelfDirty, forceContainingDirty );
	}

	@Override
	public void process() {
		if ( isProcessing ) {
			throw log.recursiveIndexingPlanProcess();
		}

		isProcessing = true;
		try {
			if ( mayRequireLoading ) {
				for ( PojoContainedTypeIndexingPlan<?, ?> delegate : containedTypeDelegates.values() ) {
					delegate.planLoading( this );
				}
				for ( PojoIndexedTypeIndexingPlan<?, ?> delegate : indexedTypeDelegates.values() ) {
					delegate.planLoading( this );
				}
			}
			if ( loadingPlan != null ) {
				loadingPlan.loadBlocking( null );
			}
			boolean shouldResolveDirtyForDeleteOnly = strategy.shouldResolveDirtyForDeleteOnly();
			for ( PojoContainedTypeIndexingPlan<?, ?> delegate : containedTypeDelegates.values() ) {
				delegate.resolveDirty( this, this, shouldResolveDirtyForDeleteOnly );
			}
			// We need to iterate on a "frozen snapshot" of the indexedTypeDelegates values because of HSEARCH-3857
			List<PojoIndexedTypeIndexingPlan<?, ?>> frozenIndexedTypeDelegates =
					new ArrayList<>( indexedTypeDelegates.values() );
			for ( PojoIndexedTypeIndexingPlan<?, ?> delegate : frozenIndexedTypeDelegates ) {
				delegate.resolveDirty( this, this, shouldResolveDirtyForDeleteOnly );
			}
			for ( PojoContainedTypeIndexingPlan<?, ?> delegate : containedTypeDelegates.values() ) {
				delegate.process( this );
			}
			for ( PojoIndexedTypeIndexingPlan<?, ?> delegate : indexedTypeDelegates.values() ) {
				delegate.process( this );
			}
		}
		finally {
			isProcessing = false;
			mayRequireLoading = false;
			loadingPlan = null;
		}
	}

	@Override
	public <R> CompletableFuture<MultiEntityOperationExecutionReport<R>> executeAndReport(
			EntityReferenceFactory<R> entityReferenceFactory) {
		try {
			process();
			return strategy.doExecuteAndReport( indexedTypeDelegates.values(), this, entityReferenceFactory );
		}
		finally {
			indexedTypeDelegates.clear();
		}
	}

	@Override
	public void discard() {
		try {
			strategy.doDiscard( indexedTypeDelegates.values() );
		}
		finally {
			indexedTypeDelegates.clear();
		}
	}

	@Override
	public void discardNotProcessed() {
		for ( PojoIndexedTypeIndexingPlan<?, ?> delegate : indexedTypeDelegates.values() ) {
			delegate.discardNotProcessed();
		}
	}

	@Override
	public void markForReindexing(Object containingEntity) {
		// Note this method won't work when using provided identifiers
		// Fortunately, all platforms relying on provided identifiers (Infinispan)
		// also disable reindexing of other entities on updates,
		// so they won't ever call this method.

		PojoRawTypeIdentifier<?> typeIdentifier = getIntrospector().detectEntityType( containingEntity );
		if ( typeIdentifier == null ) {
			throw new AssertionFailure(
					"Attempt to reindex entity " + containingEntity + " because a contained entity was modified,"
							+ " but this entity type is not indexed directly."
			);
		}
		PojoIndexedTypeIndexingPlan<?, ?> delegate = getOrCreateIndexedDelegateForContainedUpdate( typeIdentifier );
		delegate.updateBecauseOfContained( containingEntity );
	}

	private PojoRuntimeIntrospector getIntrospector() {
		return introspector;
	}

	private AbstractPojoTypeIndexingPlan<?, ?, ?> getDelegate(PojoRawTypeIdentifier<?> typeIdentifier) {
		AbstractPojoTypeIndexingPlan<?, ?, ?> delegate = indexedTypeDelegates.get( typeIdentifier );
		if ( delegate == null ) {
			delegate = containedTypeDelegates.get( typeIdentifier );
			if ( delegate == null ) {
				delegate = createDelegate( typeIdentifier );
			}
		}
		return delegate;
	}

	private AbstractPojoTypeIndexingPlan<?, ?, ?> createDelegate(PojoRawTypeIdentifier<?> typeIdentifier) {
		Optional<? extends PojoWorkIndexedTypeContext<?, ?>> indexedTypeContextOptional =
				indexedTypeContextProvider.forExactType( typeIdentifier );
		if ( indexedTypeContextOptional.isPresent() ) {
			// extracting a variable to workaround an Eclipse compiler issue
			PojoWorkIndexedTypeContext<?, ?> typeContext = indexedTypeContextOptional.get();
			PojoIndexedTypeIndexingPlan<?, ?> delegate = createDelegate( typeContext );
			indexedTypeDelegates.put( typeIdentifier, delegate );
			return delegate;
		}
		else {
			Optional<? extends PojoWorkContainedTypeContext<?, ?>> containedTypeContextOptional =
					containedTypeContextProvider.forExactType( typeIdentifier );
			if ( containedTypeContextOptional.isPresent() ) {
				PojoContainedTypeIndexingPlan<?, ?> delegate = createDelegate( containedTypeContextOptional.get() );
				containedTypeDelegates.put( typeIdentifier, delegate );
				return delegate;
			}
		}
		throw log.nonIndexedNorContainedTypeInIndexingPlan( typeIdentifier );
	}

	private PojoIndexedTypeIndexingPlan<?, ?> getOrCreateIndexedDelegateForContainedUpdate(
			PojoRawTypeIdentifier<?> typeIdentifier) {
		PojoIndexedTypeIndexingPlan<?, ?> delegate = indexedTypeDelegates.get( typeIdentifier );
		if ( delegate != null ) {
			return delegate;
		}

		Optional<? extends PojoWorkIndexedTypeContext<?, ?>> indexedTypeContextOptional =
				indexedTypeContextProvider.forExactType( typeIdentifier );
		if ( indexedTypeContextOptional.isPresent() ) {
			// extracting a variable to workaround an Eclipse compiler issue
			PojoWorkIndexedTypeContext<?, ?> typeContext = indexedTypeContextOptional.get();
			delegate = createDelegate( typeContext );
			indexedTypeDelegates.put( typeIdentifier, delegate );
			return delegate;
		}

		throw new AssertionFailure(
				"Attempt to reindex an entity of type " + typeIdentifier + " because a contained entity was modified,"
				+ " but this entity type is not indexed directly."
		);
	}

	@Override
	public PojoLoadingPlan<Object> loadingPlan() {
		if ( loadingPlan == null ) {
			loadingPlan = new PojoMultiLoaderLoadingPlan<>( sessionContext.defaultLoadingContext() );
		}
		return loadingPlan;
	}

	private <I, E> PojoIndexedTypeIndexingPlan<I, E> createDelegate(PojoWorkIndexedTypeContext<I, E> typeContext) {
		return strategy.createDelegate( typeContext, sessionContext );
	}

	private PojoContainedTypeIndexingPlan<?, ?> createDelegate(PojoWorkContainedTypeContext<?, ?> typeContext) {
		return strategy.createDelegate( typeContext, sessionContext );
	}
}
