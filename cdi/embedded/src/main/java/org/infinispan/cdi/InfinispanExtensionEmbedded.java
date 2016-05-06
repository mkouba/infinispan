package org.infinispan.cdi;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBean;
import javax.enterprise.inject.spi.ProcessProducer;
import javax.enterprise.inject.spi.Producer;
import javax.enterprise.util.TypeLiteral;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.cdi.event.cachemanager.CacheManagerEventBridge;
import org.infinispan.cdi.util.AnyLiteral;
import org.infinispan.cdi.util.BeanBuilder;
import org.infinispan.cdi.util.Beans;
import org.infinispan.cdi.util.ContextualLifecycle;
import org.infinispan.cdi.util.ContextualReference;
import org.infinispan.cdi.util.DefaultLiteral;
import org.infinispan.cdi.util.Reflections;
import org.infinispan.cdi.util.logging.EmbeddedLog;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * The Infinispan CDI extension for embedded caches
 *
 * @author Pete Muir
 * @author Kevin Pollet <kevin.pollet@serli.com> (C) 2011 SERLI
 */
public class InfinispanExtensionEmbedded implements Extension {

   private static final String CACHE_NAME = "CDIExtensionDefaultCacheManager";

   private static final EmbeddedLog LOGGER = LogFactory.getLog(InfinispanExtensionEmbedded.class, EmbeddedLog.class);

   private final Set<ConfigurationHolder> configurations;

   private volatile boolean registered = false;

   private final Object registerLock = new Object();

   private Set<Set<Annotation>> installedEmbeddedCacheManagers = new HashSet<Set<Annotation>>();

   public InfinispanExtensionEmbedded() {
      new ConfigurationBuilder(); // Attempt to initialize a core class
      this.configurations = new HashSet<InfinispanExtensionEmbedded.ConfigurationHolder>();
   }

   void processProducers(@Observes ProcessProducer<?, ?> event, BeanManager beanManager) {
      final ConfigureCache annotation = event.getAnnotatedMember().getAnnotation(ConfigureCache.class);

      if (annotation != null) {
         configurations.add(new ConfigurationHolder(
               (Producer<Configuration>)event.getProducer(),
               annotation.value(),
               Reflections.getQualifiers(beanManager, event.getAnnotatedMember().getAnnotations())
         ));
      }
   }

   // This is a work around for CDI Uber Jar deployment. When Weld scans the classpath it  pick up DefaultCacheManager
   // (this is an implementation, not an interface, so it gets instantiated). As a result we get duplicated classes
   // in CDI BeanManager.
   <T extends DefaultCacheManager> void removeDuplicatedRemoteCacheManager(@Observes ProcessAnnotatedType<T> bean) {
       if(DefaultCacheManager.class.getCanonicalName().equals(bean.getAnnotatedType().getJavaClass().getCanonicalName())) {
          LOGGER.info("removing duplicated  DefaultCacheManager" + bean.getAnnotatedType());
          bean.veto();
       }
   }

   @SuppressWarnings("unchecked")
   <T, X>void registerBeans(@Observes AfterBeanDiscovery event, final BeanManager beanManager) {

      if (beanManager.getBeans(Configuration.class).isEmpty()) {
         LOGGER.addDefaultEmbeddedConfiguration();
         // TODO simulate processProducers()/@ConfigureCache
         event.addBean(createDefaultEmbeddedConfigurationBean(beanManager));
      }
      if (beanManager.getBeans(EmbeddedCacheManager.class).isEmpty()) {
         LOGGER.addDefaultEmbeddedCacheManager();
         event.addBean(createDefaultEmbeddedCacheManagerBean(beanManager));
      }

      for (final ConfigurationHolder holder : configurations) {
          // register a AdvancedCache producer for each configuration
          Bean<?> b = new BeanBuilder(beanManager)
          .readFromType(beanManager.createAnnotatedType(AdvancedCache.class))
          .qualifiers(Beans.buildQualifiers(holder.getQualifiers()))
          .addType(new TypeLiteral<AdvancedCache<T, X>>() {}.getType())
          .addType(new TypeLiteral<Cache<T, X>>() {}.getType())
          .beanLifecycle(new ContextualLifecycle<AdvancedCache<?, ?>>() {
              @Override
              public AdvancedCache<?, ?> create(Bean<AdvancedCache<?, ?>> bean,
                 CreationalContext<AdvancedCache<?, ?>> creationalContext) {
                 return new ContextualReference<AdvancedCacheProducer>(beanManager, AdvancedCacheProducer.class).create(Reflections.<CreationalContext<AdvancedCacheProducer>>cast(creationalContext)).get().getAdvancedCache(holder.getName(), holder.getQualifiers());
              }
           }).create();
          event.addBean(b);
      }
   }

   <K, V> void registerInputCacheCustomBean(@Observes AfterBeanDiscovery event, BeanManager beanManager) {

      @SuppressWarnings("serial")
      TypeLiteral<Cache<K, V>> typeLiteral = new TypeLiteral<Cache<K, V>>() {};
      event.addBean(new BeanBuilder<Cache<K, V>>(beanManager)
               .readFromType(beanManager.createAnnotatedType(typeLiteral.getRawType()))
               .addType(typeLiteral.getType()).qualifiers(new InputLiteral())
               .beanLifecycle(new ContextualLifecycle<Cache<K, V>>() {

                  @Override
                  public Cache<K, V> create(Bean<Cache<K, V>> bean,
                           CreationalContext<Cache<K, V>> creationalContext) {
                     return ContextInputCache.get();
                  }

                  @Override
                  public void destroy(Bean<Cache<K, V>> bean, Cache<K, V> instance,
                           CreationalContext<Cache<K, V>> creationalContext) {

                  }
               }).create());
   }

   public Set<InstalledCacheManager> getInstalledEmbeddedCacheManagers(BeanManager beanManager) {
       Set<InstalledCacheManager> installedCacheManagers = new HashSet<InstalledCacheManager>();
       for (Set<Annotation> qualifiers : installedEmbeddedCacheManagers) {
           Bean<?> b = beanManager.resolve(beanManager.getBeans(EmbeddedCacheManager.class, qualifiers.toArray(Reflections.EMPTY_ANNOTATION_ARRAY)));
           EmbeddedCacheManager cm = (EmbeddedCacheManager) beanManager.getReference(b, EmbeddedCacheManager.class, beanManager.createCreationalContext(b));
           installedCacheManagers.add(new InstalledCacheManager(cm, qualifiers.contains(DefaultLiteral.INSTANCE)));
       }
       return installedCacheManagers;
   }

   public void observeEmbeddedCacheManagerBean(@Observes ProcessBean<?> processBean) {
       if (processBean.getBean().getTypes().contains(EmbeddedCacheManager.class)) {
           installedEmbeddedCacheManagers.add(processBean.getBean().getQualifiers());
       }
   }

   public void registerCacheConfigurations(CacheManagerEventBridge eventBridge, Instance<EmbeddedCacheManager> cacheManagers, BeanManager beanManager) {
      if (!registered) {
         synchronized (registerLock) {
            if (!registered) {
               final CreationalContext<Configuration> ctx = beanManager.createCreationalContext(null);
               final EmbeddedCacheManager defaultCacheManager = cacheManagers.select(DefaultLiteral.INSTANCE).get();

               for (ConfigurationHolder oneConfigurationHolder : configurations) {
                  final String cacheName = oneConfigurationHolder.getName();
                  final Configuration cacheConfiguration = oneConfigurationHolder.getProducer().produce(ctx);
                  final Set<Annotation> cacheQualifiers = oneConfigurationHolder.getQualifiers();

                  // if a specific cache manager is defined for this cache we use it
                  final Instance<EmbeddedCacheManager> specificCacheManager = cacheManagers.select(cacheQualifiers.toArray(new Annotation[cacheQualifiers.size()]));
                  final EmbeddedCacheManager cacheManager = specificCacheManager.isUnsatisfied() ? defaultCacheManager : specificCacheManager.get();

                  // the default configuration is registered by the default cache manager producer
                  if (!cacheName.trim().isEmpty()) {
                     if (cacheConfiguration != null) {
                        cacheManager.defineConfiguration(cacheName, cacheConfiguration);
                        LOGGER.cacheConfigurationDefined(cacheName, cacheManager);
                     } else if (!cacheManager.getCacheNames().contains(cacheName)) {
                        cacheManager.defineConfiguration(cacheName, cacheManager.getDefaultCacheConfiguration());
                        LOGGER.cacheConfigurationDefined(cacheName, cacheManager);
                     }
                  }

                  // register cache manager observers
                  eventBridge.registerObservers(cacheQualifiers, cacheName, cacheManager);
               }

               // only set registered to true at the end to keep other threads waiting until we have finished registration
               registered = true;
            }
         }
      }

   }

   /**
    * The default embedded cache configuration can be overridden by creating a producer which
    * produces the new default configuration. The configuration produced must have the scope
    * {@linkplain javax.enterprise.context.Dependent Dependent} and the
    * {@linkplain javax.enterprise.inject.Default Default} qualifier.
    *
    * @param beanManager
    * @return a custom bean
    */
   private Bean<Configuration> createDefaultEmbeddedConfigurationBean(BeanManager beanManager) {
      return new BeanBuilder<Configuration>(beanManager).beanClass(InfinispanExtensionEmbedded.class)
            .addTypes(Object.class, Configuration.class)
            .scope(Dependent.class)
            .qualifiers(DefaultLiteral.INSTANCE, AnyLiteral.INSTANCE)
            .beanLifecycle(new ContextualLifecycle<Configuration>() {
               @Override
               public Configuration create(Bean<Configuration> bean,
                     CreationalContext<Configuration> creationalContext) {
                  return new ConfigurationBuilder().build();
               }
            }).create();
   }

   /**
    * The default cache manager is an instance of {@link DefaultCacheManager} initialized with the
    * default configuration (either produced by
    * {@link #createDefaultEmbeddedConfigurationBean(BeanManager)} or provided by user). The default
    * cache manager can be overridden by creating a producer which produces the new default cache
    * manager. The cache manager produced must have the scope {@link ApplicationScoped} and the
    * {@linkplain javax.enterprise.inject.Default Default} qualifier.
    *
    * @param beanManager
    * @return a custom bean
    */
   private Bean<EmbeddedCacheManager> createDefaultEmbeddedCacheManagerBean(BeanManager beanManager) {
      return new BeanBuilder<EmbeddedCacheManager>(beanManager).beanClass(InfinispanExtensionEmbedded.class)
            .addTypes(Object.class, EmbeddedCacheManager.class)
            .scope(ApplicationScoped.class)
            .qualifiers(DefaultLiteral.INSTANCE, AnyLiteral.INSTANCE)
            .beanLifecycle(new ContextualLifecycle<EmbeddedCacheManager>() {

               @Override
               public EmbeddedCacheManager create(Bean<EmbeddedCacheManager> bean,
                     CreationalContext<EmbeddedCacheManager> creationalContext) {
                  GlobalConfiguration globalConfiguration = new GlobalConfigurationBuilder().globalJmxStatistics()
                        .cacheManagerName(CACHE_NAME).build();
                  @SuppressWarnings("unchecked")
                  Bean<Configuration> configurationBean = (Bean<Configuration>) beanManager
                        .resolve(beanManager.getBeans(Configuration.class));
                  Configuration defaultConfiguration = (Configuration) beanManager.getReference(configurationBean,
                        Configuration.class, beanManager.createCreationalContext(configurationBean));
                  return new DefaultCacheManager(globalConfiguration, defaultConfiguration);
               }

               @Override
               public void destroy(Bean<EmbeddedCacheManager> bean, EmbeddedCacheManager instance,
                     CreationalContext<EmbeddedCacheManager> creationalContext) {
                  instance.stop();
               }
            }).create();
   }

   static class ConfigurationHolder {
      private final Producer<Configuration> producer;
      private final Set<Annotation> qualifiers;
      private final String name;

      ConfigurationHolder(Producer<Configuration> producer, String name, Set<Annotation> qualifiers) {
         this.producer = producer;
         this.name = name;
         this.qualifiers = qualifiers;
      }

      public Producer<Configuration> getProducer() {
         return producer;
      }

      public String getName() {
         return name;
      }

      public Set<Annotation> getQualifiers() {
         return qualifiers;
      }
   }

   public static class InstalledCacheManager {
      final EmbeddedCacheManager cacheManager;
      final boolean isDefault;

      InstalledCacheManager(EmbeddedCacheManager cacheManager, boolean aDefault) {
         this.cacheManager = cacheManager;
         isDefault = aDefault;
      }

      public EmbeddedCacheManager getCacheManager() {
         return cacheManager;
      }

      public boolean isDefault() {
         return isDefault;
      }
   }
}
