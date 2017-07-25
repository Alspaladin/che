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

import org.eclipse.che.api.core.model.workspace.runtime.MachineStatus;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.installer.server.model.impl.InstallerImpl;
import org.eclipse.che.api.workspace.server.DtoConverter;
import org.eclipse.che.api.workspace.server.URLRewriter;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.InternalMachineConfig;
import org.eclipse.che.api.workspace.server.spi.RuntimeIdentityImpl;
import org.eclipse.che.api.workspace.server.spi.RuntimeStartInterruptedException;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.eclipse.che.api.core.model.workspace.runtime.MachineStatus.FAILED;
import static org.eclipse.che.api.core.model.workspace.runtime.MachineStatus.RUNNING;
import static org.eclipse.che.api.core.model.workspace.runtime.MachineStatus.STARTING;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Mock
    private NetworkLifecycle          networks;

    @Captor
    private ArgumentCaptor<MachineStatusEvent> eventCaptor;

    private DockerInternalRuntime dockerRuntime;

    @BeforeMethod
    public void setup() throws Exception {
        final DockerContainerConfig config1 = new DockerContainerConfig();
        final DockerContainerConfig config2 = new DockerContainerConfig();
        final DockerEnvironment environment = new DockerEnvironment();
        final InternalMachineConfig internalMachineCfg1 = mock(InternalMachineConfig.class);
        when(internalMachineCfg1.getInstallers()).thenReturn(singletonList(newInstaller(1)));
        final InternalMachineConfig internalMachineCfg2 = mock(InternalMachineConfig.class);
        when(internalMachineCfg2.getInstallers()).thenReturn(singletonList(newInstaller(2)));
        environment.setContainers(ImmutableMap.of(DEV_MACHINE, config1, DB_MACHINE, config2));

        doNothing().when(networks).createNetwork(anyString());
        when(runtimeContext.getIdentity()).thenReturn(IDENTITY);
        when(runtimeContext.getDevMachineName()).thenReturn(DB_MACHINE);
        when(runtimeContext.getDockerEnvironment()).thenReturn(environment);
        when(runtimeContext.getOrderedContainers()).thenReturn(ImmutableList.of(DEV_MACHINE, DB_MACHINE));
        when(runtimeContext.getMachineConfigs()).thenReturn(ImmutableMap.of(DEV_MACHINE, internalMachineCfg1,
                                                                            DB_MACHINE, internalMachineCfg2));
        dockerRuntime = new DockerInternalRuntime(runtimeContext,
                                                  mock(URLRewriter.class),
                                                  networks,
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
        mockContainerStartFailed(new InfrastructureException("container start failed"));

        dockerRuntime.start(emptyMap());

        verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(eventService, times(1)).publish(any(MachineStatusEvent.class));
        verifyEventsOrder(newEvent(DEV_MACHINE, STARTING, null));
    }

    @Test(expectedExceptions = InfrastructureException.class)
    public void throwsExceptionWhenBootstrappingOfInstallersFailed() throws Exception {
        mockInstallersBootstrapFailed(new InfrastructureException("bootstrap failed"));
        mockContainerStart();

        dockerRuntime.start(emptyMap());

        verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(eventService, times(1)).publish(any(MachineStatusEvent.class));
        verifyEventsOrder(newEvent(DEV_MACHINE, STARTING, null));
    }

    @Test(expectedExceptions = RuntimeStartInterruptedException.class)
    public void throwsInterruptionExceptionWhenNetworkCreationInterrupted() throws Exception {
        doThrow(RuntimeStartInterruptedException.class).when(networks).createNetwork(anyString());

        try {
            dockerRuntime.start(emptyMap());
        } catch (InfrastructureException rethrow) {
            verify(starter, never()).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
            throw rethrow;
        }
    }

    @Test(expectedExceptions = RuntimeStartInterruptedException.class)
    public void throwsInterruptionExceptionWhenContainerStartInterrupted() throws Exception {
        mockInstallersBootstrap();
        mockContainerStartFailed(new RuntimeStartInterruptedException(IDENTITY));

        try {
            dockerRuntime.start(emptyMap());
        } catch (InfrastructureException rethrow) {
            verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
            throw rethrow;
        }
    }

    @Test(expectedExceptions = RuntimeStartInterruptedException.class)
    public void throwsInterruptionExceptionWhenThreadInterruptedOnStarFailedBeforeDestroying() throws Exception {
        mockInstallersBootstrap();
        final String errorMsg = "container start failed";
        doAnswer(invocationOnMock -> {
            Thread.currentThread().interrupt();
            throw new InfrastructureException(errorMsg);
        }).when(starter).startContainer(anyString(),
                                       anyString(),
                                       any(DockerContainerConfig.class),
                                       any(RuntimeIdentity.class),
                                       anyBoolean(),
                                       any(AbnormalMachineStopHandler.class));
        try {
            dockerRuntime.start(emptyMap());
        } catch (InfrastructureException rethrow) {
            verify(starter, times(1)).startContainer(anyString(), anyString(), any(), any(), anyBoolean(), any());
            throw rethrow;
        }
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

    private void mockContainerStartFailed(InfrastructureException exception) throws InfrastructureException {
        when(starter.startContainer(anyString(),
                                    anyString(),
                                    any(DockerContainerConfig.class),
                                    any(RuntimeIdentity.class),
                                    anyBoolean(),
                                    any(AbnormalMachineStopHandler.class)))
                .thenThrow(exception);
    }

    private void mockInstallersBootstrap() throws InfrastructureException {
        final DockerBootstrapper bootstrapper = mock(DockerBootstrapper.class);
        when(bootstrapperFactory.create(anyString(),
                                        any(RuntimeIdentity.class),
                                        anyListOf(InstallerImpl.class),
                                        any(DockerMachine.class))).thenReturn(bootstrapper);
        doNothing().when(bootstrapper).bootstrap();
    }

    private void mockInstallersBootstrapFailed(InfrastructureException exception) throws InfrastructureException {
        final DockerBootstrapper bootstrapper = mock(DockerBootstrapper.class);
        when(bootstrapperFactory.create(anyString(),
                                        any(RuntimeIdentity.class),
                                        anyListOf(InstallerImpl.class),
                                        any(DockerMachine.class))).thenReturn(bootstrapper);
        doThrow(exception).when(bootstrapper).bootstrap();
    }

    private InstallerImpl newInstaller(int i) {
        return new InstallerImpl("installer_" + i,
                                 "installer_name" + i,
                                 String.valueOf(i),
                                 "test installer",
                                 Collections.emptyList(),
                                 emptyMap(),
                                 "echo hello",
                                 emptyMap());
    }

}
