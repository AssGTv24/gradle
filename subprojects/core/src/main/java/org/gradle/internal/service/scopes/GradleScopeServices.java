/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.service.scopes;

import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.internal.BuildScopeListenerRegistrationListener;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.collections.DomainObjectCollectionFactory;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.plugins.DefaultPluginManager;
import org.gradle.api.internal.plugins.ImperativeOnlyPluginTarget;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.api.internal.plugins.PluginTarget;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.tasks.options.OptionReader;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.services.internal.BuildServiceRegistryInternal;
import org.gradle.api.services.internal.DefaultBuildServicesRegistry;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.internal.DefaultFileContentCacheFactory;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.SplitFileContentCacheFactory;
import org.gradle.composite.internal.IncludedBuildControllers;
import org.gradle.composite.internal.IncludedBuildTaskGraph;
import org.gradle.configuration.ConfigurationTargetIdentifier;
import org.gradle.configuration.internal.ListenerBuildOperationDecorator;
import org.gradle.configuration.internal.UserCodeApplicationContext;
import org.gradle.execution.BuildConfigurationAction;
import org.gradle.execution.BuildConfigurationActionExecuter;
import org.gradle.execution.BuildOperationFiringBuildWorkerExecutor;
import org.gradle.execution.BuildWorkExecutor;
import org.gradle.execution.CompositeAwareTaskSelector;
import org.gradle.execution.DefaultBuildConfigurationActionExecuter;
import org.gradle.execution.DefaultBuildWorkExecutor;
import org.gradle.execution.DefaultTasksBuildExecutionAction;
import org.gradle.execution.DeprecateUndefinedBuildWorkExecutor;
import org.gradle.execution.DryRunBuildExecutionAction;
import org.gradle.execution.ExcludedTaskFilteringBuildConfigurationAction;
import org.gradle.execution.IncludedBuildLifecycleBuildWorkExecutor;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.execution.SelectedTaskExecutionAction;
import org.gradle.execution.TaskNameResolver;
import org.gradle.execution.TaskNameResolvingBuildConfigurationAction;
import org.gradle.execution.TaskSelector;
import org.gradle.execution.commandline.CommandLineTaskConfigurer;
import org.gradle.execution.commandline.CommandLineTaskParser;
import org.gradle.execution.plan.DefaultExecutionPlan;
import org.gradle.execution.plan.DefaultNodeValidator;
import org.gradle.execution.plan.DependencyResolver;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.execution.plan.LocalTaskNodeExecutor;
import org.gradle.execution.plan.NodeExecutor;
import org.gradle.execution.plan.PlanExecutor;
import org.gradle.execution.plan.TaskDependencyResolver;
import org.gradle.execution.plan.TaskNodeDependencyResolver;
import org.gradle.execution.plan.TaskNodeFactory;
import org.gradle.execution.plan.WorkNodeDependencyResolver;
import org.gradle.execution.plan.WorkNodeExecutor;
import org.gradle.execution.taskgraph.DefaultTaskExecutionGraph;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.execution.taskgraph.TaskListenerInternal;
import org.gradle.initialization.BuildOperationFiringTaskExecutionPreparer;
import org.gradle.initialization.DefaultTaskExecutionPreparer;
import org.gradle.initialization.TaskExecutionPreparer;
import org.gradle.internal.Factory;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.cleanup.DefaultBuildOutputCleanupRegistry;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;
import org.gradle.internal.id.UniqueId;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.resources.SharedResourceLeaseRegistry;
import org.gradle.internal.scopeids.id.BuildInvocationScopeId;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Contains the services for a given {@link GradleInternal} instance.
 */
public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent, final GradleInternal gradle) {
        super(parent);
        add(GradleInternal.class, gradle);
        register(registration -> {
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerGradleServices(registration);
            }
        });
    }

    TaskSelector createTaskSelector(GradleInternal gradle, BuildStateRegistry buildStateRegistry, ProjectConfigurer projectConfigurer) {
        return new CompositeAwareTaskSelector(gradle, buildStateRegistry, projectConfigurer, new TaskNameResolver());
    }

    OptionReader createOptionReader() {
        return new OptionReader();
    }

    CommandLineTaskParser createCommandLineTaskParser(OptionReader optionReader, TaskSelector taskSelector) {
        return new CommandLineTaskParser(new CommandLineTaskConfigurer(optionReader), taskSelector);
    }

    BuildWorkExecutor createBuildExecuter(StyledTextOutputFactory textOutputFactory, IncludedBuildControllers includedBuildControllers, BuildOperationExecutor buildOperationExecutor) {
        return new BuildOperationFiringBuildWorkerExecutor(
            new DeprecateUndefinedBuildWorkExecutor(
                new IncludedBuildLifecycleBuildWorkExecutor(
                    new DefaultBuildWorkExecutor(
                        asList(new DryRunBuildExecutionAction(textOutputFactory),
                            new SelectedTaskExecutionAction())),
                    includedBuildControllers)),
            buildOperationExecutor);
    }

    BuildConfigurationActionExecuter createBuildConfigurationActionExecuter(CommandLineTaskParser commandLineTaskParser, TaskSelector taskSelector, ProjectConfigurer projectConfigurer, ProjectStateRegistry projectStateRegistry) {
        List<BuildConfigurationAction> taskSelectionActions = new LinkedList<BuildConfigurationAction>();
        taskSelectionActions.add(new DefaultTasksBuildExecutionAction(projectConfigurer));
        taskSelectionActions.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));
        return new DefaultBuildConfigurationActionExecuter(Arrays.asList(new ExcludedTaskFilteringBuildConfigurationAction(taskSelector)), taskSelectionActions, projectStateRegistry);
    }

    IncludedBuildControllers createIncludedBuildControllers(GradleInternal gradle, IncludedBuildControllers sharedControllers) {
        if (gradle.isRootBuild()) {
            return sharedControllers;
        } else {
            // TODO: buildSrc shouldn't be special here, but since buildSrc is built separately from other included builds and the root build
            // We need to treat buildSrc as if it's a root build so that it can depend on included builds that may substitute dependencies
            // into buildSrc
            if (gradle.getOwner().getBuildIdentifier().getName().equals(SettingsInternal.BUILD_SRC)) {
                return new IncludedBuildControllers.BuildSrcIncludedBuildControllers(sharedControllers);
            }
            return IncludedBuildControllers.EMPTY;
        }
    }

    TaskExecutionPreparer createTaskExecutionPreparer(BuildConfigurationActionExecuter buildConfigurationActionExecuter, IncludedBuildControllers includedBuildControllers, BuildOperationExecutor buildOperationExecutor) {
        return new BuildOperationFiringTaskExecutionPreparer(
            new DefaultTaskExecutionPreparer(buildConfigurationActionExecuter, includedBuildControllers, buildOperationExecutor),
            buildOperationExecutor);
    }

    ProjectFinder createProjectFinder(final GradleInternal gradle) {
        return new DefaultProjectFinder(() -> gradle.getRootProject());
    }

    TaskNodeFactory createTaskNodeFactory(GradleInternal gradle, IncludedBuildTaskGraph includedBuildTaskGraph) {
        return new TaskNodeFactory(gradle, includedBuildTaskGraph);
    }

    TaskNodeDependencyResolver createTaskNodeResolver(TaskNodeFactory taskNodeFactory) {
        return new TaskNodeDependencyResolver(taskNodeFactory);
    }

    WorkNodeDependencyResolver createWorkNodeResolver() {
        return new WorkNodeDependencyResolver();
    }

    TaskDependencyResolver createTaskDependencyResolver(List<DependencyResolver> dependencyResolvers) {
        return new TaskDependencyResolver(dependencyResolvers);
    }

    LocalTaskNodeExecutor createLocalTaskNodeExecutor() {
        return new LocalTaskNodeExecutor();
    }

    WorkNodeExecutor createWorkNodeExecutor() {
        return new WorkNodeExecutor();
    }

    ListenerBroadcast<TaskExecutionListener> createTaskExecutionListenerBroadcast(ListenerManager listenerManager) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionListener.class);
    }

    TaskExecutionListener createTaskExecutionListener(ListenerBroadcast<TaskExecutionListener> broadcast) {
        return broadcast.getSource();
    }

    TaskListenerInternal createTaskListenerInternal(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(TaskListenerInternal.class);
    }

    ListenerBroadcast<TaskExecutionGraphListener> createTaskExecutionGraphListenerBroadcast(ListenerManager listenerManager) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionGraphListener.class);
    }

    ExecutionPlan createExecutionPlan(
        GradleInternal gradleInternal,
        TaskNodeFactory taskNodeFactory,
        TaskDependencyResolver dependencyResolver
    ) {
        return new DefaultExecutionPlan(gradleInternal.getIdentityPath().toString(), taskNodeFactory, dependencyResolver, new DefaultNodeValidator());
    }

    TaskExecutionGraphInternal createTaskExecutionGraph(
        PlanExecutor planExecutor,
        List<NodeExecutor> nodeExecutors,
        BuildOperationExecutor buildOperationExecutor,
        ListenerBuildOperationDecorator listenerBuildOperationDecorator,
        ResourceLockCoordinationService coordinationService,
        GradleInternal gradleInternal,
        ExecutionPlan executionPlan,
        ListenerBroadcast<TaskExecutionListener> taskListeners,
        ListenerBroadcast<TaskExecutionGraphListener> graphListeners,
        ListenerManager listenerManager,
        ProjectStateRegistry projectStateRegistry,
        ServiceRegistry gradleScopedServices,
        TaskSelector taskSelector
    ) {
        return new DefaultTaskExecutionGraph(
            planExecutor,
            nodeExecutors,
            buildOperationExecutor,
            listenerBuildOperationDecorator,
            coordinationService,
            gradleInternal,
            executionPlan,
            graphListeners,
            taskListeners,
            listenerManager.getBroadcaster(BuildScopeListenerRegistrationListener.class),
            projectStateRegistry,
            gradleScopedServices,
            taskSelector
        );
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            @Override
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject, loggingManagerInternalFactory);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    PluginRegistry createPluginRegistry(PluginRegistry parentRegistry) {
        return parentRegistry.createChild(get(GradleInternal.class).getClassLoaderScope());
    }

    PluginManagerInternal createPluginManager(Instantiator instantiator, GradleInternal gradleInternal, PluginRegistry pluginRegistry, InstantiatorFactory instantiatorFactory, BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext, CollectionCallbackActionDecorator decorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
        PluginTarget target = new ImperativeOnlyPluginTarget<GradleInternal>(gradleInternal);
        return instantiator.newInstance(DefaultPluginManager.class, pluginRegistry, instantiatorFactory.inject(this), target, buildOperationExecutor, userCodeApplicationContext, decorator, domainObjectCollectionFactory);
    }

    FileContentCacheFactory createFileContentCacheFactory(
        GlobalCacheLocations globalCacheLocations,
        CacheRepository cacheRepository,
        FileContentCacheFactory globalCacheFactory,
        Gradle gradle,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        ListenerManager listenerManager,
        FileSystemAccess fileSystemAccess
    ) {
        DefaultFileContentCacheFactory localCacheFactory = new DefaultFileContentCacheFactory(
            listenerManager,
            fileSystemAccess,
            cacheRepository,
            inMemoryCacheDecoratorFactory,
            gradle
        );
        return new SplitFileContentCacheFactory(
            globalCacheFactory,
            localCacheFactory,
            globalCacheLocations
        );
    }

    BuildServiceRegistryInternal createSharedServiceRegistry(
        BuildState buildState,
        Instantiator instantiator,
        DomainObjectCollectionFactory factory,
        InstantiatorFactory instantiatorFactory,
        ServiceRegistry services,
        ListenerManager listenerManager,
        IsolatableFactory isolatableFactory,
        SharedResourceLeaseRegistry sharedResourceLeaseRegistry
    ) {
        return instantiator.newInstance(
            DefaultBuildServicesRegistry.class,
            buildState.getBuildIdentifier(),
            factory,
            instantiatorFactory,
            services,
            listenerManager,
            isolatableFactory,
            sharedResourceLeaseRegistry
        );
    }

    protected BuildOutputCleanupRegistry createBuildOutputCleanupRegistry(FileCollectionFactory fileCollectionFactory) {
        return new DefaultBuildOutputCleanupRegistry(fileCollectionFactory);
    }

    protected ConfigurationTargetIdentifier createConfigurationTargetIdentifier(GradleInternal gradle) {
        return ConfigurationTargetIdentifier.of(gradle);
    }

    // This needs to go here instead of being “build tree” scoped due to the GradleBuild task.
    // Builds launched by that task are part of the same build tree, but should have their own invocation ID.
    // Such builds also have their own root Gradle object.
    protected BuildInvocationScopeId createBuildInvocationScopeId(GradleInternal gradle) {
        GradleInternal rootGradle = gradle.getRoot();
        if (gradle == rootGradle) {
            return new BuildInvocationScopeId(UniqueId.generate());
        } else {
            return rootGradle.getServices().get(BuildInvocationScopeId.class);
        }
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }

}
