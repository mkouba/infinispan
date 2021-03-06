package org.infinispan.interceptors;

import org.infinispan.commands.VisitableCommand;
import org.infinispan.commons.util.Experimental;
import org.infinispan.context.InvocationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interceptor chain using {@link SequentialInterceptor}s.
 *
 * Experimental: The ability to modify the interceptors at runtime may be removed in future versions.
 *
 * @author Dan Berindei
 * @since 9.0
 */
@Experimental
public interface SequentialInterceptorChain {
   List<SequentialInterceptor> getInterceptors();

   /**
    * Inserts the given interceptor at the specified position in the chain (o based indexing).
    *
    * @throws IllegalArgumentException if the position is invalid (e.g. 5 and there are only 2 interceptors in the
    *                                  chain)
    */
   void addInterceptor(SequentialInterceptor interceptor, int position);

   /**
    * Removes the interceptor at the given postion.
    *
    * @throws IllegalArgumentException if the position is invalid (e.g. 5 and there are only 2 interceptors in the
    *                                  chain)
    */
   void removeInterceptor(int position);

   /**
    * Returns the number of interceptors in the chain.
    */
   int size();

   /**
    * Removes all the occurences of supplied interceptor type from the chain.
    */
   void removeInterceptor(Class<? extends SequentialInterceptor> clazz);

   /**
    * Adds a new interceptor in list after an interceptor of a given type.
    *
    * @return true if the interceptor was added; i.e. the afterInterceptor exists
    */
   boolean addInterceptorAfter(SequentialInterceptor toAdd, Class<? extends
         SequentialInterceptor> afterInterceptor);

   /**
    * Adds a new interceptor in list after an interceptor of a given type.
    *
    * @return true if the interceptor was added; i.e. the afterInterceptor exists
    */
   boolean addInterceptorBefore(SequentialInterceptor toAdd,
                                                Class<? extends SequentialInterceptor> beforeInterceptor);

   /**
    * Replaces an existing interceptor of the given type in the interceptor chain with a new interceptor instance passed as parameter.
    *
    * @param replacingInterceptor        the interceptor to add to the interceptor chain
    * @param toBeReplacedInterceptorType the type of interceptor that should be swapped with the new one
    * @return true if the interceptor was replaced
    */
   boolean replaceInterceptor(SequentialInterceptor replacingInterceptor,
                                              Class<? extends SequentialInterceptor> toBeReplacedInterceptorType);

   /**
    * Appends at the end.
    */
   void appendInterceptor(SequentialInterceptor ci, boolean isCustom);

   /**
    * Walks the command through the interceptor chain. The received ctx is being passed in.
    *
    * <p>Note: Reusing the context for multiple invocations is allowed. However, the two invocations
    * must not overlap, so calling {@link #invoke(InvocationContext, VisitableCommand)} from an interceptor
    * is not allowed. If an interceptor wants to invoke a new command and cannot use {@link org.infinispan
    * .context.SequentialInvocationContext#forkInvocation(VisitableCommand, SequentialInterceptor
    * .ForkReturnHandler)} or
    * {@link org.infinispan.context.SequentialInvocationContext#forkInvocationSync(VisitableCommand)},
    * it must first copy the invocation context with {@link InvocationContext#clone()}.</p>
    */
   Object invoke(InvocationContext ctx, VisitableCommand command);

   /**
    * Walks the command through the interceptor chain. The received ctx is being passed in.
    */
   CompletableFuture<Object> invokeAsync(InvocationContext ctx, VisitableCommand command);

   /**
    * Returns the first interceptor extending the given class, or {@code null} if there is none.
    */
   <T extends SequentialInterceptor> T findInterceptorExtending(Class<T> interceptorClass);

   /**
    * Returns the first interceptor with the given class, or {@code null} if there is none.
    */
   <T extends SequentialInterceptor> T findInterceptorWithClass(Class<T> interceptorClass);

   /**
    * Checks whether the chain contains the supplied interceptor instance.
    */
   boolean containsInstance(SequentialInterceptor interceptor);

   /**
    * Checks whether the chain contains an interceptor with the given class.
    */
   boolean containsInterceptorType(Class<? extends SequentialInterceptor> interceptorType);

   /**
    * Checks whether the chain contains an interceptor with the given class, or a subclass.
    */
   boolean containsInterceptorType(Class<? extends SequentialInterceptor> interceptorType,
                                                   boolean alsoMatchSubClasses);
}
