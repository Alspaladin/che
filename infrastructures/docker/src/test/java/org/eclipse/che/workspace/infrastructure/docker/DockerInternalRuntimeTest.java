/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.workspace.infrastructure.docker;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.eclipse.che.api.core.model.workspace.config.MachineConfig;
import org.eclipse.che.api.core.model.workspace.runtime.MachineStatus;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.installer.server.InstallerRegistry;
import org.eclipse.che.api.installer.server.model.impl.InstallerImpl;
import org.eclipse.che.api.workspace.server.DtoConverter;
import org.eclipse.che.api.workspace.server.URLRewriter;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.InternalMachineConfig;
import org.eclipse.che.api.workspace.server.spi.RuntimeIdentityImpl;
import org.eclipse.che.api.workspace.shared.dto.event.MachineStatusEvent;
import org.eclipse.che.dto.server.DtoFactory;
import org.eclipse.che.workspace.infrastructure.docker.bootstrap.DockerBootstrapper;
import org.eclipse.che.workspace.infrastructure.docker.bootstrap.DockerBootstrapperFactory;
import org.eclipse.che.workspace.infrastructure.docker.model.DockerContainerConfig;
import org.eclipse.che.workspace.infrastructure.docker.model.DockerEnvironment;
import org.eclipse.che.workspace.infrastructure.docker.monit.AbnormalMachineStopHandler;
import org.eclipse.che.workspace.infrastructure.docker.server.ServerCheckerFactory;
import org.eclipse.che.workspace.infrastructure.docker.snapshot.SnapshotDao;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.List;

import static java.util.Collections.emptyMap;
import static org.eclipse.che.api.core.model.workspace.runtime.MachineStatus.RUNNING;
import static org.eclipse.che.api.core.model.workspace.runtime.MachineStatus.STARTING;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * Tests {@link DockerInternalRuntime}.
 *
 * @author Anton Korneta
 */
@Listeners(MockitoTestNGListener.class)
public class DockerInternalRuntimeTest {

    private static final RuntimeIdentity IDENTITY    = new RuntimeIdentityImpl("ws1", "env1", "usr1");
    private static final String          DEV_MACHINE = "DEV_MACHINE";
    private static final String          DB_MACHINE  = "DB_MACHINE";

    @Mock
    private DockerBootstrapperFactory bootstrapperFactory;
    @Mock
    private DockerRuntimeContext      runtimeContext;
    @Mock
    private EventService              eventService;
    @Mock
    private DockerMachineStarter      starter;

    @Captor
    private ArgumentCaptor<MachineStatusEvent> eventCaptor;

    private DockerInternalRuntime dockerRuntime;

    @BeforeMethod
    public void setup() throws Exception {
        final DockerContainerConfig config1 = new DockerContainerConfig();
        final DockerContainerConfig config2 = new DockerContainerConfig();
        final DockerEnvironment environment = new DockerEnvironment();
        final InternalMachineConfig machineConfig1 = new InternalMachineConfig(mock(MachineConfig.class),
                                                                               mock(InstallerRegistry.class));
        final InternalMachineConfig machineConfig2 = new InternalMachineConfig(mock(MachineConfig.class),
                                                                               mock(InstallerRegistry.class));
        environment.setContainers(ImmutableMap.of(DEV_MACHINE, config1, DB_MACHINE, config2));

        when(runtimeContext.getIdentity()).thenReturn(IDENTITY);
        when(runtimeContext.getDevMachineName()).thenReturn(DB_MACHINE);
        when(runtimeContext.getDockerEnvironment()).thenReturn(environment);
        when(runtimeContext.getOrderedContainers()).thenReturn(ImmutableList.of(DEV_MACHINE, DB_MACHINE));
        when(runtimeContext.getMachineConfigs()).thenReturn(ImmutableMap.of(DEV_MACHINE, machineConfig1,
                                                                            DB_MACHINE, machineConfig2));
        dockerRuntime = new DockerInternalRuntime(runtimeContext,
                                                  mock(URLRewriter.class),
                                                  mock(NetworkLifecycle.class),
                                                  starter,
                                                  mock(SnapshotDao.class),
                                                  mock(DockerRegistryClient.class),
                                                  eventService,
                                                  bootstrapperFactory,
                                                  mock(ServerCheckerFactory.class),
                                                  mock(MachineLoggersFactory.class));
    }

    @Test
    public void startsDockerRuntimeAndPropagatesMachineStatusEvents() throws Exception {
        mockInstallersBootstrap();
        mockContainerStart();
        dockerRuntime.start(emptyMap());

        verify(starter, times(2)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(eventService, times(4)).publish(any(MachineStatusEvent.class));
        verifyEventsOrder(newEvent(DEV_MACHINE, STARTING, null),
                          newEvent(DEV_MACHINE, RUNNING, null),
                          newEvent(DB_MACHINE, STARTING, null),
                          newEvent(DB_MACHINE, RUNNING, null));
    }

    @Test(expectedExceptions = InfrastructureException.class)
    public void throwsExceptionWhenOneMachineStartFailed() throws Exception {
        mockInstallersBootstrap();
        mockContainerStartFailed();

        dockerRuntime.start(emptyMap());

        verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(eventService, times(1)).publish(any(MachineStatusEvent.class));
        verifyEventsOrder(newEvent(DEV_MACHINE, STARTING, null));
    }

    @Test(expectedExceptions = InfrastructureException.class)
    public void throwsExceptionWhenBootstrappingOfInstallersFailed() throws Exception {
        mockInstallersBootstrapFailed();
        mockContainerStart();

        dockerRuntime.start(emptyMap());

        verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(eventService, times(1)).publish(any(MachineStatusEvent.class));
        verifyEventsOrder(newEvent(DEV_MACHINE, STARTING, null));
    }

    private void verifyEventsOrder(MachineStatusEvent... expectedEvents) {
        final Iterator<MachineStatusEvent> actualEvents = captureEvents().iterator();
        for (MachineStatusEvent expected : expectedEvents) {
            if (!actualEvents.hasNext()) {
                fail("It is expected to receive machine status events");
            }
            final MachineStatusEvent actual = actualEvents.next();
            assertEquals(actual, expected);
        }
        if (actualEvents.hasNext()) {
            fail("No more events expected");
        }
    }

    private List<MachineStatusEvent> captureEvents() {
        verify(eventService, atLeastOnce()).publish(eventCaptor.capture());
        return eventCaptor.getAllValues();
    }

    private static MachineStatusEvent newEvent(String machineName,
                                               MachineStatus status,
                                               String error) {
        return DtoFactory.newDto(MachineStatusEvent.class)
                         .withIdentity(DtoConverter.asDto(IDENTITY))
                         .withMachineName(machineName)
                         .withEventType(status)
                         .withError(error);
    }

    private void mockContainerStart() throws InfrastructureException {
        when(starter.startContainer(anyString(),
                                    anyString(),
                                    any(DockerContainerConfig.class),
                                    any(RuntimeIdentity.class),
                                    anyBoolean(),
                                    any(AbnormalMachineStopHandler.class))).thenReturn(mock(DockerMachine.class));
    }

    private void mockContainerStartFailed() throws InfrastructureException {
        when(starter.startContainer(anyString(),
                                    anyString(),
                                    any(DockerContainerConfig.class),
                                    any(RuntimeIdentity.class),
                                    anyBoolean(),
                                    any(AbnormalMachineStopHandler.class)))
                .thenThrow(new InfrastructureException("container start failed"));
    }

    private void mockInstallersBootstrap() throws InfrastructureException {
        final DockerBootstrapper bootstrapper = mock(DockerBootstrapper.class);
        when(bootstrapperFactory.create(anyString(),
                                        any(RuntimeIdentity.class),
                                        anyListOf(InstallerImpl.class),
                                        any(DockerMachine.class))).thenReturn(bootstrapper);
        doNothing().when(bootstrapper).bootstrap();
    }

    private void mockInstallersBootstrapFailed() throws InfrastructureException {
        final DockerBootstrapper bootstrapper = mock(DockerBootstrapper.class);
        when(bootstrapperFactory.create(anyString(),
                                        any(RuntimeIdentity.class),
                                        anyListOf(InstallerImpl.class),
                                        any(DockerMachine.class))).thenReturn(bootstrapper);
        doThrow(InfrastructureException.class).when(bootstrapper).bootstrap();
    }

}
