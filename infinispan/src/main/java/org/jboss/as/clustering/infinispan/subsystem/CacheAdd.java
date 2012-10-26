package org.jboss.as.clustering.infinispan.subsystem;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.FileCacheStoreConfigurationBuilder;
import org.infinispan.configuration.cache.FileCacheStoreConfigurationBuilder.FsyncMode;
import org.infinispan.configuration.cache.LoadersConfigurationBuilder;
import org.infinispan.configuration.cache.StoreConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.loaders.CacheStore;
import org.infinispan.loaders.jdbc.configuration.AbstractJdbcCacheStoreConfigurationBuilder;
import org.infinispan.loaders.jdbc.configuration.JdbcBinaryCacheStoreConfigurationBuilder;
import org.infinispan.loaders.jdbc.configuration.JdbcMixedCacheStoreConfigurationBuilder;
import org.infinispan.loaders.jdbc.configuration.JdbcStringBasedCacheStoreConfigurationBuilder;
import org.infinispan.loaders.jdbc.configuration.TableManipulationConfigurationBuilder;
import org.infinispan.loaders.remote.configuration.RemoteCacheStoreConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.tm.BatchModeTransactionManager;
import org.infinispan.util.TypedProperties;
import org.infinispan.util.concurrent.IsolationLevel;
import org.jboss.as.clustering.infinispan.InfinispanMessages;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ServiceVerificationHandler;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathManagerService;
import org.jboss.as.naming.ManagedReferenceInjector;
import org.jboss.as.naming.ServiceBasedNamingStore;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.Services;
import org.jboss.as.txn.service.TxnServices;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.logging.Logger;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoader;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.InjectedValue;
import org.jboss.msc.value.Value;
import org.jboss.tm.XAResourceRecoveryRegistry;

/**
 * Base class for cache add handlers
 *
 * @author Richard Achmatowicz (c) 2011 Red Hat Inc.
 */
public abstract class CacheAdd extends AbstractAddStepHandler {

    private static final Logger log = Logger.getLogger(CacheAdd.class.getPackage().getName());
    private static final String DEFAULTS = "infinispan-defaults.xml";
    private static volatile Map<CacheMode, Configuration> defaults = null;

    public static synchronized Configuration getDefaultConfiguration(CacheMode cacheMode) {
        if (defaults == null) {
            ConfigurationBuilderHolder holder = load(DEFAULTS);
            Configuration defaultConfig = holder.getDefaultConfigurationBuilder().build();
            Map<CacheMode, Configuration> map = new EnumMap<CacheMode, Configuration>(CacheMode.class);
            map.put(defaultConfig.clustering().cacheMode(), defaultConfig);
            for (ConfigurationBuilder builder : holder.getNamedConfigurationBuilders().values()) {
                Configuration config = builder.build();
                map.put(config.clustering().cacheMode(), config);
            }
            for (CacheMode mode : CacheMode.values()) {
                if (!map.containsKey(mode)) {
                    map.put(mode, new ConfigurationBuilder().read(defaultConfig).clustering().cacheMode(mode).build());
                }
            }
            defaults = map;
        }
        return defaults.get(cacheMode);
    }

    private static ConfigurationBuilderHolder load(String resource) {
        URL url = find(resource, CacheAdd.class.getClassLoader());
        log.debugf("Loading Infinispan defaults from %s", url.toString());
        try {
            InputStream input = url.openStream();
            ParserRegistry parser = new ParserRegistry(ParserRegistry.class.getClassLoader());
            try {
                return parser.parse(input);
            } finally {
                try {
                    input.close();
                } catch (IOException e) {
                    log.warn(e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Failed to parse %s", url), e);
        }
    }

    private static URL find(String resource, ClassLoader... loaders) {
        for (ClassLoader loader : loaders) {
            if (loader != null) {
                URL url = loader.getResource(resource);
                if (url != null) {
                    return url;
                }
            }
        }
        throw new IllegalArgumentException(String.format("Failed to locate %s", resource));
    }

    final CacheMode mode;

    CacheAdd(CacheMode mode) {
        this.mode = mode;
    }

    @Override
    protected void populateModel(ModelNode operation, ModelNode model) throws OperationFailedException {

        this.populate(operation, model);
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model, ServiceVerificationHandler verificationHandler, List<ServiceController<?>> newControllers) throws OperationFailedException {

        // Because we use child resources in a read-only manner to configure the cache, replace the local model with the full model
        ModelNode cacheModel = Resource.Tools.readModel(context.readResource(PathAddress.EMPTY_ADDRESS));

        // we also need the containerModel
        PathAddress containerAddress = getCacheContainerAddressFromOperation(operation);
        ModelNode containerModel = context.readResourceFromRoot(containerAddress).getModel();

        // install the services from a reusable method
        newControllers.addAll(this.installRuntimeServices(context, operation, containerModel, cacheModel, verificationHandler));
    }

    Collection<ServiceController<?>> installRuntimeServices(OperationContext context, ModelNode operation, ModelNode containerModel, ModelNode cacheModel, ServiceVerificationHandler verificationHandler) throws OperationFailedException {

        // get all required addresses, names and service names
        PathAddress cacheAddress = getCacheAddressFromOperation(operation);
        PathAddress containerAddress = getCacheContainerAddressFromOperation(operation);
        String cacheName = cacheAddress.getLastElement().getValue();
        String containerName = containerAddress.getLastElement().getValue();

        // get model attributes
        ModelNode resolvedValue = null;
        final String jndiName = ((resolvedValue = CommonAttributes.JNDI_NAME.resolveModelAttribute(context, cacheModel)).isDefined()) ? resolvedValue.asString() : null;
        final ServiceController.Mode initialMode = StartMode.valueOf(CommonAttributes.START.resolveModelAttribute(context, cacheModel).asString()).getMode();

        final ModuleIdentifier moduleId = (resolvedValue = CommonAttributes.CACHE_MODULE.resolveModelAttribute(context, cacheModel)).isDefined() ? ModuleIdentifier.fromString(resolvedValue.asString()) : null;

        // create a list for dependencies which may need to be added during processing
        List<Dependency<?>> dependencies = new LinkedList<Dependency<?>>();
        // Infinispan Configuration to hold the operation data
        ConfigurationBuilder builder = new ConfigurationBuilder().read(getDefaultConfiguration(this.mode));

        // process cache configuration ModelNode describing overrides to defaults
        processModelNode(context, containerName, cacheModel, builder, dependencies);

        // get container Model to pick up the value of the default cache of the container
        // AS7-3488 make default-cache no required attribute
        String defaultCache = CommonAttributes.DEFAULT_CACHE.resolveModelAttribute(context, containerModel).asString();

        ServiceTarget target = context.getServiceTarget();
        Configuration config = builder.build();

        Collection<ServiceController<?>> controllers = new ArrayList<ServiceController<?>>(3);

        // install the cache configuration service (configures a cache)
        controllers.add(this.installCacheConfigurationService(target, containerName, cacheName, defaultCache, moduleId,
                        builder, config, dependencies, verificationHandler));
        log.debugf("Cache configuration service for %s installed for container %s", cacheName, containerName);

        // now install the corresponding cache service (starts a configured cache)
        controllers.add(this.installCacheService(target, containerName, cacheName, defaultCache, initialMode, builder, config, verificationHandler));

        // install a name service entry for the cache
        controllers.add(this.installJndiService(target, containerName, cacheName, jndiName, verificationHandler));
        log.debugf("Cache service for cache %s installed for container %s", cacheName, containerName);

        return controllers;
    }

    void removeRuntimeServices(OperationContext context, ModelNode operation, ModelNode model)
            throws OperationFailedException {
        // get container and cache addresses
        final PathAddress cacheAddress = getCacheAddressFromOperation(operation) ;
        final PathAddress containerAddress = getCacheContainerAddressFromOperation(operation) ;
        // get container and cache names
        final String cacheName = cacheAddress.getLastElement().getValue() ;
        final String containerName = containerAddress.getLastElement().getValue() ;

        // remove all services started by CacheAdd, in reverse order
        // remove the binder service
        ModelNode resolvedValue = null;
        final String jndiNameString = (resolvedValue = CommonAttributes.JNDI_NAME.resolveModelAttribute(context, model)).isDefined() ? resolvedValue.asString() : null;
        final String jndiName = InfinispanJndiName.createCacheJndiNameOrDefault(jndiNameString, containerName, cacheName);
        ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(jndiName);
        context.removeService(bindInfo.getBinderServiceName()) ;
        // remove the CacheService instance
        context.removeService(CacheService.getServiceName(containerName, cacheName));
        // remove the cache configuration service
        context.removeService(CacheConfigurationService.getServiceName(containerName, cacheName));

        log.debugf("cache %s removed for container %s", cacheName, containerName);
    }

    protected PathAddress getCacheAddressFromOperation(ModelNode operation) {
        return PathAddress.pathAddress(operation.get(OP_ADDR)) ;
    }

    protected PathAddress getCacheContainerAddressFromOperation(ModelNode operation) {
        final PathAddress cacheAddress = getCacheAddressFromOperation(operation) ;
        final PathAddress containerAddress = cacheAddress.subAddress(0, cacheAddress.size()-1) ;
        return containerAddress ;
    }

    ServiceController<?> installCacheConfigurationService(ServiceTarget target, String containerName, String cacheName, String defaultCache, ModuleIdentifier moduleId,
            ConfigurationBuilder builder, Configuration config, List<Dependency<?>> dependencies, ServiceVerificationHandler verificationHandler) {

        InjectedValue<EmbeddedCacheManager> container = new InjectedValue<EmbeddedCacheManager>();
        CacheConfigurationDependencies cacheConfigurationDependencies = new CacheConfigurationDependencies(container);
        CacheConfigurationService cacheConfigurationService = new CacheConfigurationService(cacheName, builder, moduleId, cacheConfigurationDependencies);

        ServiceBuilder<?> configBuilder = target.addService(CacheConfigurationService.getServiceName(containerName, cacheName), cacheConfigurationService)
                .addDependency(EmbeddedCacheManagerService.getServiceName(containerName), EmbeddedCacheManager.class, container)
                .addDependency(Services.JBOSS_SERVICE_MODULE_LOADER, ModuleLoader.class, cacheConfigurationDependencies.getModuleLoaderInjector())
                .setInitialMode(ServiceController.Mode.PASSIVE)
        ;
        if (config.invocationBatching().enabled()) {
            cacheConfigurationDependencies.getTransactionManagerInjector().inject(BatchModeTransactionManager.getInstance());
        } else if (config.transaction().transactionMode() == org.infinispan.transaction.TransactionMode.TRANSACTIONAL) {
            configBuilder.addDependency(TxnServices.JBOSS_TXN_TRANSACTION_MANAGER, TransactionManager.class, cacheConfigurationDependencies.getTransactionManagerInjector());
            if (config.transaction().useSynchronization()) {
                configBuilder.addDependency(TxnServices.JBOSS_TXN_SYNCHRONIZATION_REGISTRY, TransactionSynchronizationRegistry.class, cacheConfigurationDependencies.getTransactionSynchronizationRegistryInjector());
            }
        }

        // add in any additional dependencies resulting from ModelNode parsing
        for (Dependency<?> dependency : dependencies) {
            this.addDependency(configBuilder, dependency);
        }
        // add an alias for the default cache
        if (cacheName.equals(defaultCache)) {
            configBuilder.addAliases(CacheConfigurationService.getServiceName(containerName, null));
        }
        return configBuilder.install();
    }

    ServiceController<?> installCacheService(ServiceTarget target, String containerName, String cacheName, String defaultCache, ServiceController.Mode initialMode,
            ConfigurationBuilder builder, Configuration config, ServiceVerificationHandler verificationHandler) {

        InjectedValue<EmbeddedCacheManager> container = new InjectedValue<EmbeddedCacheManager>();
        CacheDependencies cacheDependencies = new CacheDependencies(container);
        CacheService<Object, Object> cacheService = new CacheService<Object, Object>(cacheName, cacheDependencies);

        ServiceBuilder<?> cacheBuilder = target.addService(CacheService.getServiceName(containerName, cacheName), cacheService)
                .addDependency(CacheConfigurationService.getServiceName(containerName, cacheName))
                .addDependency(EmbeddedCacheManagerService.getServiceName(containerName), EmbeddedCacheManager.class, container)
                .setInitialMode(initialMode)
        ;
        if (config.transaction().recovery().enabled()) {
            cacheBuilder.addDependency(TxnServices.JBOSS_TXN_ARJUNA_RECOVERY_MANAGER, XAResourceRecoveryRegistry.class, cacheDependencies.getRecoveryRegistryInjector());
        }

        // add an alias for the default cache
        if (cacheName.equals(defaultCache)) {
            cacheBuilder.addAliases(CacheService.getServiceName(containerName, null));
        }

        if (initialMode == ServiceController.Mode.ACTIVE) {
            cacheBuilder.addListener(verificationHandler);
        }

        return cacheBuilder.install();
    }

    @SuppressWarnings("rawtypes")
    ServiceController<?> installJndiService(ServiceTarget target, String containerName, String cacheName, String jndiNameString, ServiceVerificationHandler verificationHandler) {

        String jndiName = InfinispanJndiName.createCacheJndiNameOrDefault(jndiNameString, containerName, cacheName);

        ServiceName cacheServiceName = CacheService.getServiceName(containerName, cacheName);
        ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(jndiName);

        BinderService binder = new BinderService(bindInfo.getBindName());
        return target.addService(bindInfo.getBinderServiceName(), binder)
                .addAliases(ContextNames.JAVA_CONTEXT_SERVICE_NAME.append(jndiName))
                .addDependency(cacheServiceName, Cache.class, new ManagedReferenceInjector<Cache>(binder.getManagedObjectInjector()))
                .addDependency(bindInfo.getParentContextServiceName(), ServiceBasedNamingStore.class, binder.getNamingStoreInjector())
                .setInitialMode(ServiceController.Mode.PASSIVE)
                .install()
        ;
    }

    private <T> void addDependency(ServiceBuilder<?> builder, Dependency<T> dependency) {
        ServiceName name = dependency.getName();
        Injector<T> injector = dependency.getInjector();
        if (injector != null) {
            builder.addDependency(name, dependency.getType(), injector);
        } else {
            builder.addDependency(name);
        }
    }

    /**
     * Transfer elements common to both operations and models
     *
     * @param fromModel
     * @param toModel
     */
    void populate(ModelNode fromModel, ModelNode toModel) throws OperationFailedException {

        CommonAttributes.START.validateAndSet(fromModel, toModel);
        CommonAttributes.BATCHING.validateAndSet(fromModel, toModel);
        CommonAttributes.INDEXING.validateAndSet(fromModel, toModel);
        CommonAttributes.JNDI_NAME.validateAndSet(fromModel, toModel);
        CommonAttributes.CACHE_MODULE.validateAndSet(fromModel, toModel);
    }

    /**
     * Create a Configuration object initialized from the operation ModelNode
     *
     * @param containerName the name of the cache container
     * @param cache         ModelNode representing cache configuration
     * @param builder       ConfigurationBuilder object to add data to
     * @return initialised Configuration object
     */
    void processModelNode(OperationContext context, String containerName, ModelNode cache, ConfigurationBuilder builder, List<Dependency<?>> dependencies)
            throws OperationFailedException {

        final Indexing indexing = Indexing.valueOf(CommonAttributes.INDEXING.resolveModelAttribute(context, cache).asString());
        final boolean batching = CommonAttributes.BATCHING.resolveModelAttribute(context, cache).asBoolean();

        // set the cache mode (may be modified when setting up clustering attributes)
        builder.clustering().cacheMode(this.mode);

        builder.indexing()
                .enabled(indexing.isEnabled())
                .indexLocalOnly(indexing.isLocalOnly())
        ;

        // locking is a child resource
        if (cache.hasDefined(ModelKeys.LOCKING) && cache.get(ModelKeys.LOCKING, ModelKeys.LOCKING_NAME).isDefined()) {
            ModelNode locking = cache.get(ModelKeys.LOCKING, ModelKeys.LOCKING_NAME);

            final IsolationLevel isolationLevel = IsolationLevel.valueOf(CommonAttributes.ISOLATION.resolveModelAttribute(context, locking).asString());
            final boolean striping = CommonAttributes.STRIPING.resolveModelAttribute(context, locking).asBoolean();
            final long acquireTimeout = CommonAttributes.ACQUIRE_TIMEOUT.resolveModelAttribute(context, locking).asLong();
            final int concurrencyLevel = CommonAttributes.CONCURRENCY_LEVEL.resolveModelAttribute(context, locking).asInt();

            builder.locking()
                    .isolationLevel(isolationLevel)
                    .useLockStriping(striping)
                    .lockAcquisitionTimeout(acquireTimeout)
                    .concurrencyLevel(concurrencyLevel)
            ;
        }

        TransactionMode txMode = TransactionMode.NONE;
        LockingMode lockingMode = LockingMode.OPTIMISTIC;
        // locking is a child resource
        if (cache.hasDefined(ModelKeys.TRANSACTION) && cache.get(ModelKeys.TRANSACTION, ModelKeys.TRANSACTION_NAME).isDefined()) {
            ModelNode transaction = cache.get(ModelKeys.TRANSACTION, ModelKeys.TRANSACTION_NAME);

            final long stopTimeout = CommonAttributes.STOP_TIMEOUT.resolveModelAttribute(context, transaction).asLong();
            txMode = TransactionMode.valueOf(CommonAttributes.MODE.resolveModelAttribute(context, transaction).asString());
            lockingMode = LockingMode.valueOf(CommonAttributes.LOCKING.resolveModelAttribute(context, transaction).asString());

            builder.transaction().cacheStopTimeout(stopTimeout);
        }
        builder.transaction()
                .transactionMode(txMode.getMode())
                .lockingMode(lockingMode)
                .useSynchronization(!txMode.isXAEnabled())
                .recovery().enabled(txMode.isRecoveryEnabled())
        ;
        if (txMode.isRecoveryEnabled()) {
            builder.transaction().syncCommitPhase(true).syncRollbackPhase(true);
        }

        if (batching) {
            builder.transaction().transactionMode(org.infinispan.transaction.TransactionMode.TRANSACTIONAL).invocationBatching().enable();
        } else {
            builder.transaction().invocationBatching().disable();
        }

        // eviction is a child resource
        if (cache.hasDefined(ModelKeys.EVICTION) && cache.get(ModelKeys.EVICTION, ModelKeys.EVICTION_NAME).isDefined()) {
            ModelNode eviction = cache.get(ModelKeys.EVICTION, ModelKeys.EVICTION_NAME);

            final EvictionStrategy strategy = EvictionStrategy.valueOf(CommonAttributes.EVICTION_STRATEGY.resolveModelAttribute(context, eviction).asString());
            builder.eviction().strategy(strategy);

            if (strategy.isEnabled()) {
                final int maxEntries = CommonAttributes.MAX_ENTRIES.resolveModelAttribute(context, eviction).asInt();
                builder.eviction().maxEntries(maxEntries);
            }
        }
        // expiration is a child resource
        if (cache.hasDefined(ModelKeys.EXPIRATION) && cache.get(ModelKeys.EXPIRATION, ModelKeys.EXPIRATION_NAME).isDefined()) {

            ModelNode expiration = cache.get(ModelKeys.EXPIRATION, ModelKeys.EXPIRATION_NAME);

            final long maxIdle = CommonAttributes.MAX_IDLE.resolveModelAttribute(context, expiration).asLong();
            final long lifespan = CommonAttributes.LIFESPAN.resolveModelAttribute(context, expiration).asLong();
            final long interval = CommonAttributes.INTERVAL.resolveModelAttribute(context, expiration).asLong();

            builder.expiration()
                    .maxIdle(maxIdle)
                    .lifespan(lifespan)
                    .wakeUpInterval(interval)
            ;
            // Only enable the reaper thread if we need it
            if ((maxIdle > 0) || (lifespan > 0)) {
                builder.expiration().enableReaper();
            } else {
                builder.expiration().disableReaper();
            }
        }

        // stores are a child resource
        String storeKey = this.findStoreKey(cache);
        if (storeKey != null) {
            ModelNode store = this.getStoreModelNode(cache);

            final boolean shared = CommonAttributes.SHARED.resolveModelAttribute(context, store).asBoolean();
            final boolean preload = CommonAttributes.PRELOAD.resolveModelAttribute(context, store).asBoolean();
            final boolean passivation = CommonAttributes.PASSIVATION.resolveModelAttribute(context, store).asBoolean();
            final boolean fetchState = CommonAttributes.FETCH_STATE.resolveModelAttribute(context, store).asBoolean();
            final boolean purge = CommonAttributes.PURGE.resolveModelAttribute(context, store).asBoolean();
            final boolean singleton = CommonAttributes.SINGLETON.resolveModelAttribute(context, store).asBoolean();
            final boolean async = store.hasDefined(ModelKeys.WRITE_BEHIND) && store.get(ModelKeys.WRITE_BEHIND, ModelKeys.WRITE_BEHIND_NAME).isDefined();

            LoadersConfigurationBuilder loadersBuilder = builder.loaders()
                    .shared(shared)
                    .preload(preload)
                    .passivation(passivation)
            ;
            StoreConfigurationBuilder<?, ?> storeBuilder = this.buildCacheStore(context, loadersBuilder, containerName, store, storeKey, dependencies)
                    .fetchPersistentState(fetchState)
                    .purgeOnStartup(purge)
                    .purgeSynchronously(true)
            ;
            storeBuilder.singletonStore().enabled(singleton);

            if (async) {
                ModelNode writeBehind = store.get(ModelKeys.WRITE_BEHIND, ModelKeys.WRITE_BEHIND_NAME);
                storeBuilder.async().enable()
                        .flushLockTimeout(CommonAttributes.FLUSH_LOCK_TIMEOUT.resolveModelAttribute(context, writeBehind).asLong())
                        .modificationQueueSize(CommonAttributes.MODIFICATION_QUEUE_SIZE.resolveModelAttribute(context, writeBehind).asInt())
                        .shutdownTimeout(CommonAttributes.SHUTDOWN_TIMEOUT.resolveModelAttribute(context, writeBehind).asLong())
                        .threadPoolSize(CommonAttributes.THREAD_POOL_SIZE.resolveModelAttribute(context, writeBehind).asInt())
                ;
            }

            final Properties properties = new TypedProperties();
            if (store.hasDefined(ModelKeys.PROPERTY)) {
                for (Property property : store.get(ModelKeys.PROPERTY).asPropertyList()) {
                    String propertyName = property.getName();
                    Property complexValue = property.getValue().asProperty();
                    String propertyValue = complexValue.getValue().asString();
                    properties.setProperty(propertyName, propertyValue);
                }
            }
            storeBuilder.withProperties(properties);
        }
    }

    private String findStoreKey(ModelNode cache) {
        if (cache.hasDefined(ModelKeys.STORE)) {
            return ModelKeys.STORE;
        } else if (cache.hasDefined(ModelKeys.FILE_STORE)) {
            return ModelKeys.FILE_STORE;
        } else if (cache.hasDefined(ModelKeys.STRING_KEYED_JDBC_STORE)) {
            return ModelKeys.STRING_KEYED_JDBC_STORE;
        } else if (cache.hasDefined(ModelKeys.BINARY_KEYED_JDBC_STORE)) {
            return ModelKeys.BINARY_KEYED_JDBC_STORE;
        } else if (cache.hasDefined(ModelKeys.MIXED_KEYED_JDBC_STORE)) {
            return ModelKeys.MIXED_KEYED_JDBC_STORE;
        } else if (cache.hasDefined(ModelKeys.REMOTE_STORE)) {
            return ModelKeys.REMOTE_STORE;
        }
        return null;
    }

    private ModelNode getStoreModelNode(ModelNode cache) {
        if (cache.hasDefined(ModelKeys.STORE)) {
            return cache.get(ModelKeys.STORE, ModelKeys.STORE_NAME);
        } else if (cache.hasDefined(ModelKeys.FILE_STORE)) {
            return cache.get(ModelKeys.FILE_STORE, ModelKeys.FILE_STORE_NAME);
        } else if (cache.hasDefined(ModelKeys.STRING_KEYED_JDBC_STORE)) {
            return cache.get(ModelKeys.STRING_KEYED_JDBC_STORE, ModelKeys.STRING_KEYED_JDBC_STORE_NAME);
        } else if (cache.hasDefined(ModelKeys.BINARY_KEYED_JDBC_STORE)) {
            return cache.get(ModelKeys.BINARY_KEYED_JDBC_STORE, ModelKeys.BINARY_KEYED_JDBC_STORE_NAME);
        } else if (cache.hasDefined(ModelKeys.MIXED_KEYED_JDBC_STORE)) {
            return cache.get(ModelKeys.MIXED_KEYED_JDBC_STORE, ModelKeys.MIXED_KEYED_JDBC_STORE_NAME);
        } else if (cache.hasDefined(ModelKeys.REMOTE_STORE)) {
            return cache.get(ModelKeys.REMOTE_STORE, ModelKeys.REMOTE_STORE_NAME);
        }
        return null;
    }


    private StoreConfigurationBuilder<?, ?> buildCacheStore(OperationContext context, LoadersConfigurationBuilder loadersBuilder, String containerName, ModelNode store, String storeKey, List<Dependency<?>> dependencies) throws OperationFailedException {

        ModelNode resolvedValue = null;
        if (storeKey.equals(ModelKeys.FILE_STORE)) {
            final FileCacheStoreConfigurationBuilder builder = loadersBuilder.addStore(FileCacheStoreConfigurationBuilder.class);

            final String path = ((resolvedValue = CommonAttributes.PATH.resolveModelAttribute(context, store)).isDefined()) ? resolvedValue.asString() : InfinispanExtension.SUBSYSTEM_NAME + File.separatorChar + containerName;
            final String relativeTo = ((resolvedValue = CommonAttributes.RELATIVE_TO.resolveModelAttribute(context, store)).isDefined()) ? resolvedValue.asString() : ServerEnvironment.SERVER_DATA_DIR;
            Injector<PathManager> injector = new SimpleInjector<PathManager>() {
                volatile PathManager.Callback.Handle callbackHandle;
                @Override
                public void inject(PathManager value) {
                    callbackHandle = value.registerCallback(relativeTo, PathManager.ReloadServerCallback.create(), PathManager.Event.UPDATED, PathManager.Event.REMOVED);
                    builder.location(value.resolveRelativePathEntry(path, relativeTo));
                }

                @Override
                public void uninject() {
                    super.uninject();
                    if (callbackHandle != null) {
                        callbackHandle.remove();
                    }
                }
            };
            dependencies.add(new Dependency<PathManager>(PathManagerService.SERVICE_NAME, PathManager.class, injector));
            return builder.fsyncMode(FsyncMode.PER_WRITE);
        } else if (storeKey.equals(ModelKeys.STRING_KEYED_JDBC_STORE) || storeKey.equals(ModelKeys.BINARY_KEYED_JDBC_STORE) || storeKey.equals(ModelKeys.MIXED_KEYED_JDBC_STORE)) {
            final AbstractJdbcCacheStoreConfigurationBuilder<?, ?> builder = this.buildJdbcStore(loadersBuilder, context, store);

            final String datasource = CommonAttributes.DATA_SOURCE.resolveModelAttribute(context, store).asString();

            dependencies.add(new Dependency<Object>(ServiceName.JBOSS.append("data-source", datasource)));
            builder.dataSource().jndiUrl(datasource);
            return builder;
        } else if (storeKey.equals(ModelKeys.REMOTE_STORE)) {
            final RemoteCacheStoreConfigurationBuilder builder = loadersBuilder.addStore(RemoteCacheStoreConfigurationBuilder.class);
            for (ModelNode server : store.require(ModelKeys.REMOTE_SERVERS).asList()) {
                String outboundSocketBinding = server.get(ModelKeys.OUTBOUND_SOCKET_BINDING).asString();
                Injector<OutboundSocketBinding> injector = new SimpleInjector<OutboundSocketBinding>() {
                    @Override
                    public void inject(OutboundSocketBinding value) {
                        try {
                            builder.addServer().host(value.getDestinationAddress().getHostAddress()).port(value.getDestinationPort());
                        } catch (UnknownHostException e) {
                            throw InfinispanMessages.MESSAGES.failedToInjectSocketBinding(e, value);
                        }
                    }
                };
                dependencies.add(new Dependency<OutboundSocketBinding>(OutboundSocketBinding.OUTBOUND_SOCKET_BINDING_BASE_SERVICE_NAME.append(outboundSocketBinding), OutboundSocketBinding.class, injector));
            }
            if (store.hasDefined(ModelKeys.CACHE)) {
                builder.remoteCacheName(store.get(ModelKeys.CACHE).asString());
            }
            if (store.hasDefined(ModelKeys.SOCKET_TIMEOUT)) {
                builder.socketTimeout(store.require(ModelKeys.SOCKET_TIMEOUT).asLong());
            }
            if (store.hasDefined(ModelKeys.TCP_NO_DELAY)) {
                builder.tcpNoDelay(store.require(ModelKeys.TCP_NO_DELAY).asBoolean());
            }
            return builder;
        } else {
            String className = store.require(ModelKeys.CLASS).asString();
            try {
                Class<? extends CacheStore> storeClass = CacheStore.class.getClassLoader().loadClass(className).asSubclass(CacheStore.class);
                return loadersBuilder.loaders().addStore().cacheStore(storeClass.newInstance());
            } catch (Exception e) {
                throw InfinispanMessages.MESSAGES.invalidCacheStore(e, className);
            }
        }
    }

    private AbstractJdbcCacheStoreConfigurationBuilder<?, ?> buildJdbcStore(LoadersConfigurationBuilder loadersBuilder, OperationContext context, ModelNode store) throws OperationFailedException {
        boolean useStringKeyedTable = store.hasDefined(ModelKeys.STRING_KEYED_TABLE);
        boolean useBinaryKeyedTable = store.hasDefined(ModelKeys.BINARY_KEYED_TABLE);
        if (useStringKeyedTable && !useBinaryKeyedTable) {
            JdbcStringBasedCacheStoreConfigurationBuilder builder = loadersBuilder.addStore(JdbcStringBasedCacheStoreConfigurationBuilder.class);
            this.buildStringKeyedTable(builder.table(), context, store.get(ModelKeys.STRING_KEYED_TABLE));
            return builder;
        } else if (useBinaryKeyedTable && !useStringKeyedTable) {
            JdbcBinaryCacheStoreConfigurationBuilder builder = loadersBuilder.addStore(JdbcBinaryCacheStoreConfigurationBuilder.class);
            this.buildBinaryKeyedTable(builder.table(), context, store.get(ModelKeys.BINARY_KEYED_TABLE));
            return builder;
        }
        // Else, use mixed mode
        JdbcMixedCacheStoreConfigurationBuilder builder = loadersBuilder.addStore(JdbcMixedCacheStoreConfigurationBuilder.class);
        this.buildStringKeyedTable(builder.stringTable(), context, store.get(ModelKeys.STRING_KEYED_TABLE));
        this.buildBinaryKeyedTable(builder.binaryTable(), context, store.get(ModelKeys.BINARY_KEYED_TABLE));
        return builder;
    }

    private void buildBinaryKeyedTable(TableManipulationConfigurationBuilder<?, ?> builder, OperationContext context, ModelNode table) throws OperationFailedException {
        this.buildTable(builder, context, table, "ispn_bucket");
    }

    private void buildStringKeyedTable(TableManipulationConfigurationBuilder<?, ?> builder, OperationContext context, ModelNode table) throws OperationFailedException {
        this.buildTable(builder, context, table, "ispn_entry");
    }

    private void buildTable(TableManipulationConfigurationBuilder<?, ?> builder, OperationContext context, ModelNode table, String defaultTableNamePrefix) throws OperationFailedException {
        ModelNode tableNamePrefix = CommonAttributes.PREFIX.resolveModelAttribute(context, table);
        builder.batchSize(CommonAttributes.BATCH_SIZE.resolveModelAttribute(context, table).asInt())
                .fetchSize(CommonAttributes.FETCH_SIZE.resolveModelAttribute(context, table).asInt())
                .tableNamePrefix(tableNamePrefix.isDefined() ? tableNamePrefix.asString() : defaultTableNamePrefix)
                .idColumnName(this.getColumnProperty(table, ModelKeys.ID_COLUMN, ModelKeys.NAME, "id"))
                .idColumnType(this.getColumnProperty(table, ModelKeys.ID_COLUMN, ModelKeys.TYPE, "VARCHAR"))
                .dataColumnName(this.getColumnProperty(table, ModelKeys.DATA_COLUMN, ModelKeys.NAME, "datum"))
                .dataColumnType(this.getColumnProperty(table, ModelKeys.DATA_COLUMN, ModelKeys.TYPE, "BINARY"))
                .timestampColumnName(this.getColumnProperty(table, ModelKeys.TIMESTAMP_COLUMN, ModelKeys.NAME, "version"))
                .timestampColumnType(this.getColumnProperty(table, ModelKeys.TIMESTAMP_COLUMN, ModelKeys.TYPE, "BIGINT"))
        ;
    }

    private String getColumnProperty(ModelNode table, String columnKey, String key, String defaultValue) {
        if (!table.isDefined() || !table.hasDefined(columnKey)) return defaultValue;
        ModelNode column = table.get(columnKey);
        return column.hasDefined(key) ? column.get(key).asString() : defaultValue;
    }

    /*
     * Allows us to store dependency requirements for later processing.
     */
    protected class Dependency<I> {
        private final ServiceName name;
        private final Class<I> type;
        private final Injector<I> target;

        Dependency(ServiceName name) {
            this(name, null, null);
        }

        Dependency(ServiceName name, Class<I> type, Injector<I> target) {
            this.name = name;
            this.type = type;
            this.target = target;
        }

        ServiceName getName() {
            return name;
        }

        public Class<I> getType() {
            return type;
        }

        public Injector<I> getInjector() {
            return target;
        }
    }

    private abstract class SimpleInjector<I> implements Injector<I> {
        @Override
        public void uninject() {
            // Do nothing
        }
    }

    private static class CacheDependencies implements CacheService.Dependencies {

        private final Value<EmbeddedCacheManager> container;
        private final InjectedValue<XAResourceRecoveryRegistry> recoveryRegistry = new InjectedValue<XAResourceRecoveryRegistry>();

        CacheDependencies(Value<EmbeddedCacheManager> container) {
            this.container = container;
        }

        Injector<XAResourceRecoveryRegistry> getRecoveryRegistryInjector() {
            return this.recoveryRegistry;
        }

        @Override
        public EmbeddedCacheManager getCacheContainer() {
            return this.container.getValue();
        }

        @Override
        public XAResourceRecoveryRegistry getRecoveryRegistry() {
            return this.recoveryRegistry.getOptionalValue();
        }
    }

    private static class CacheConfigurationDependencies implements CacheConfigurationService.Dependencies {

        private final Value<EmbeddedCacheManager> container;
        private final InjectedValue<TransactionManager> tm = new InjectedValue<TransactionManager>();
        private final InjectedValue<TransactionSynchronizationRegistry> tsr = new InjectedValue<TransactionSynchronizationRegistry>();
        private final InjectedValue<ModuleLoader> moduleLoader = new InjectedValue<ModuleLoader>();

        CacheConfigurationDependencies(Value<EmbeddedCacheManager> container) {
            this.container = container;
        }

        Injector<TransactionManager> getTransactionManagerInjector() {
            return this.tm;
        }

        Injector<TransactionSynchronizationRegistry> getTransactionSynchronizationRegistryInjector() {
            return this.tsr;
        }

        Injector<ModuleLoader> getModuleLoaderInjector() {
            return this.moduleLoader;
        }

        @Override
        public EmbeddedCacheManager getCacheContainer() {
            return this.container.getValue();
        }

        @Override
        public TransactionManager getTransactionManager() {
            return this.tm.getOptionalValue();
        }

        @Override
        public TransactionSynchronizationRegistry getTransactionSynchronizationRegistry() {
            return this.tsr.getOptionalValue();
        }

        @Override
        public ModuleLoader getModuleLoader() {
            return this.moduleLoader.getValue();
        }
    }
}