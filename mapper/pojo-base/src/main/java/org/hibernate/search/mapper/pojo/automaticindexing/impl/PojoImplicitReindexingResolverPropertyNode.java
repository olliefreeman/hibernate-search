/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.pojo.automaticindexing.impl;

import org.hibernate.search.util.common.reflect.spi.ValueReadHandle;
import org.hibernate.search.util.common.impl.Closer;
import org.hibernate.search.util.common.impl.ToStringTreeBuilder;

/**
 * A {@link PojoImplicitReindexingResolverNode} dealing with a specific property of a specific type,
 * getting the value from that property then applying nested resolvers to that value.
 * <p>
 * This node will only delegate to nested nodes for deeper resolution,
 * and will never contribute entities to reindex directly.
 * At the time of writing, nested nodes are either type nodes or container element nodes,
 * but we might allow other nodes in the future for optimization purposes.
 *
 * @param <T> The property holder type received as input.
 * @param <P> The property type.
 */
public class PojoImplicitReindexingResolverPropertyNode<T, P> extends PojoImplicitReindexingResolverNode<T> {

	private final ValueReadHandle<P> handle;
	private final PojoImplicitReindexingResolverNode<? super P> nested;

	public PojoImplicitReindexingResolverPropertyNode(ValueReadHandle<P> handle,
			PojoImplicitReindexingResolverNode<? super P> nested) {
		this.handle = handle;
		this.nested = nested;
	}

	@Override
	public void close() {
		try ( Closer<RuntimeException> closer = new Closer<>() ) {
			closer.push( PojoImplicitReindexingResolverNode::close, nested );
		}
	}

	@Override
	public void appendTo(ToStringTreeBuilder builder) {
		builder.attribute( "operation", "process property" );
		builder.attribute( "handle", handle );
		builder.attribute( "nested", nested );
	}

	@Override
	public void resolveEntitiesToReindex(PojoReindexingCollector collector,
			T dirty, PojoImplicitReindexingResolverRootContext context) {
		P propertyValue;
		try {
			propertyValue = handle.get( dirty );
		}
		catch (RuntimeException e) {
			context.propagateOrIgnorePropertyAccessException( e );
			return;
		}
		if ( propertyValue != null ) {
			nested.resolveEntitiesToReindex( collector, propertyValue, context );
		}
	}
}
