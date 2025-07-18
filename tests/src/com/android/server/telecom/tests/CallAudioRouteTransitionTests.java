/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.Handler;
import android.os.HandlerThread;
import android.telecom.CallAudioState;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioCommunicationDeviceTracker;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioRouteStateMachine;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.StatusBarNotifier;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.WiredHeadsetManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@RunWith(Parameterized.class)
public class CallAudioRouteTransitionTests extends TelecomTestCase {
    private static final int NONE = 0;
    private static final int ON = 1;
    private static final int OFF = 2;
    private static final int OPTIONAL = 3;

    // This is used to simulate the first bluetooth device getting connected --
    // it requires two messages: BT device list changed and active device present
    private static final int SPECIAL_CONNECT_BT_ACTION = 998;
    // Same, but for disconnection
    private static final int SPECIAL_DISCONNECT_BT_ACTION = 999;

    static class RoutingTestParameters {
        public String name;
        public int initialRoute;
        public BluetoothDevice initialBluetoothDevice = null;
        public int availableRoutes; // may excl. speakerphone, because that's always available
        public List<BluetoothDevice> availableBluetoothDevices = Collections.emptyList();
        public int speakerInteraction; // one of NONE, ON, or OFF
        public int bluetoothInteraction; // one of NONE, ON, or OFF
        public int action;
        public int expectedRoute;
        public BluetoothDevice expectedBluetoothDevice = null;
        public int expectedAvailableRoutes; // also may exclude the speakerphone.
        public int earpieceControl; // Allows disabling the earpiece to simulate Wear or Car

        public int callSupportedRoutes = CallAudioState.ROUTE_ALL;

        public RoutingTestParameters(String name, int initialRoute,
                int availableRoutes, int speakerInteraction,
                int bluetoothInteraction, int action, int expectedRoute,
                int expectedAvailableRoutes, int earpieceControl) {
            this.name = name;
            this.initialRoute = initialRoute;
            this.availableRoutes = availableRoutes;
            this.speakerInteraction = speakerInteraction;
            this.bluetoothInteraction = bluetoothInteraction;
            this.action = action;
            this.expectedRoute = expectedRoute;
            this.expectedAvailableRoutes = expectedAvailableRoutes;
            this.earpieceControl = earpieceControl;
        }

        public RoutingTestParameters setCallSupportedRoutes(int routes) {
            callSupportedRoutes = routes;
            return this;
        }

        public RoutingTestParameters setInitialBluetoothDevice(BluetoothDevice device) {
            initialBluetoothDevice = device;
            return this;
        }

        public RoutingTestParameters setAvailableBluetoothDevices(BluetoothDevice... devices) {
            availableBluetoothDevices = Arrays.asList(devices);
            return this;
        }

        public RoutingTestParameters setExpectedBluetoothDevice(BluetoothDevice device) {
            expectedBluetoothDevice = device;
            return this;
        }

        @Override
        public String toString() {
            return "RoutingTestParameters{" +
                    "name='" + name + '\'' +
                    ", initialRoute=" + initialRoute +
                    ", availableRoutes=" + availableRoutes +
                    ", speakerInteraction=" + speakerInteraction +
                    ", bluetoothInteraction=" + bluetoothInteraction +
                    ", action=" + action +
                    ", expectedRoute=" + expectedRoute +
                    ", expectedAvailableRoutes=" + expectedAvailableRoutes +
                    ", earpieceControl=" + earpieceControl +
                    '}';
        }
    }

    private final RoutingTestParameters mParams;
    @Mock CallsManager mockCallsManager;
    @Mock BluetoothRouteManager mockBluetoothRouteManager;
    @Mock IAudioService mockAudioService;
    @Mock ConnectionServiceWrapper mockConnectionServiceWrapper;
    @Mock WiredHeadsetManager mockWiredHeadsetManager;
    @Mock StatusBarNotifier mockStatusBarNotifier;
    @Mock Call fakeCall;
    @Mock CallAudioManager mockCallAudioManager;
    private CallAudioCommunicationDeviceTracker mCommunicationDeviceTracker;
    private CallAudioManager.AudioServiceFactory mAudioServiceFactory;
    private static final int TEST_TIMEOUT = 500;
    private AudioManager mockAudioManager;
    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };
    private HandlerThread mHandlerThread;

    public CallAudioRouteTransitionTests(RoutingTestParameters params) {
        mParams = params;
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mHandlerThread = new HandlerThread("CallAudioRouteTransitionTests");
        mHandlerThread.start();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mockAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCommunicationDeviceTracker = new CallAudioCommunicationDeviceTracker(mContext);
        mCommunicationDeviceTracker.setBluetoothRouteManager(mockBluetoothRouteManager);

        mAudioServiceFactory = new CallAudioManager.AudioServiceFactory() {
            @Override
            public IAudioService getAudioService() {
                return mockAudioService;
            }
        };

        when(mockCallsManager.getForegroundCall()).thenReturn(fakeCall);
        when(mockCallsManager.getTrackedCalls()).thenReturn(Set.of(fakeCall));
        when(mockCallsManager.getLock()).thenReturn(mLock);
        when(mockCallsManager.hasVideoCall()).thenReturn(false);
        when(fakeCall.getConnectionService()).thenReturn(mockConnectionServiceWrapper);
        when(fakeCall.isAlive()).thenReturn(true);
        when(fakeCall.getSupportedAudioRoutes()).thenReturn(CallAudioState.ROUTE_ALL);

        doNothing().when(mockConnectionServiceWrapper).onCallAudioStateChanged(any(Call.class),
                any(CallAudioState.class));
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
        super.tearDown();
    }

    private void setupMocksForParams(final CallAudioRouteStateMachine sm,
            RoutingTestParameters params) {
        // Set up bluetooth and speakerphone state
        doReturn(params.initialRoute == CallAudioState.ROUTE_BLUETOOTH)
                .when(mockBluetoothRouteManager).isBluetoothAudioConnectedOrPending();
        doReturn((params.availableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0
                || (params.expectedAvailableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0)
                .when(mockBluetoothRouteManager).isBluetoothAvailable();
        doReturn(params.availableBluetoothDevices)
                .when(mockBluetoothRouteManager).getConnectedDevices();
        if (params.initialBluetoothDevice != null) {
            doReturn(params.initialBluetoothDevice)
                    .when(mockBluetoothRouteManager).getBluetoothAudioConnectedDevice();
        }


        doAnswer(invocation -> {
            sm.sendMessageWithSessionInfo(CallAudioRouteStateMachine.BT_AUDIO_CONNECTED);
            return null;
        }).when(mockBluetoothRouteManager).connectBluetoothAudio(nullable(String.class));

        // Set the speakerphone state depending on the message being sent. If it's one of the
        // speakerphone override ones, set accordingly. Otherwise consult the initial route.
        boolean speakerphoneOn;
        if (params.action == CallAudioRouteStateMachine.SPEAKER_ON) {
            speakerphoneOn = true;
        } else if (params.action == CallAudioRouteStateMachine.SPEAKER_OFF) {
            speakerphoneOn = false;
        } else {
            speakerphoneOn = params.initialRoute == CallAudioState.ROUTE_SPEAKER;
        }
        when(mockAudioManager.isSpeakerphoneOn()).thenReturn(speakerphoneOn);

        when(fakeCall.getSupportedAudioRoutes()).thenReturn(params.callSupportedRoutes);
    }

    private void sendActionToStateMachine(CallAudioRouteStateMachine sm) {
        switch (mParams.action) {
            case SPECIAL_CONNECT_BT_ACTION:
                sm.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.BLUETOOTH_DEVICE_LIST_CHANGED);
                sm.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.BT_ACTIVE_DEVICE_PRESENT);
                break;
            case SPECIAL_DISCONNECT_BT_ACTION:
                sm.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.BLUETOOTH_DEVICE_LIST_CHANGED);
                sm.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.BT_ACTIVE_DEVICE_GONE);
                break;
            default:
                sm.sendMessageWithSessionInfo(mParams.action);
                break;
        }
    }

    @Test
    @SmallTest
    public void testActiveTransition() {
        final CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothRouteManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory,
                mParams.earpieceControl,
                mHandlerThread.getLooper(),
                Runnable::run /** do async stuff sync for test purposes */,
                mCommunicationDeviceTracker,
                mFeatureFlags);
        stateMachine.setCallAudioManager(mockCallAudioManager);

        setupMocksForParams(stateMachine, mParams);

        // Set the initial CallAudioState object
        final CallAudioState initState = new CallAudioState(false,
                mParams.initialRoute, (mParams.availableRoutes | CallAudioState.ROUTE_SPEAKER),
                mParams.initialBluetoothDevice, mParams.availableBluetoothDevices);
        stateMachine.initialize(initState);

        // Make the state machine have focus so that we actually do something
        stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.SWITCH_FOCUS,
                CallAudioRouteStateMachine.ACTIVE_FOCUS);
        // Tell the state machine that BT is on, if that's what the mParams say.
        if (mParams.initialRoute == CallAudioState.ROUTE_BLUETOOTH) {
            stateMachine.sendMessageWithSessionInfo(CallAudioRouteStateMachine.BT_AUDIO_CONNECTED);
        }
        waitForHandlerAction(stateMachine.getAdapterHandler(), TEST_TIMEOUT);

        // Clear invocations on mocks to discard stuff from initialization
        clearInvocations();

        sendActionToStateMachine(stateMachine);

        waitForHandlerAction(stateMachine.getAdapterHandler(), TEST_TIMEOUT);
        waitForHandlerAction(stateMachine.getAdapterHandler(), TEST_TIMEOUT);

        Handler h = stateMachine.getAdapterHandler();
        waitForHandlerAction(h, TEST_TIMEOUT);
        stateMachine.quitStateMachine();

        // Verify interactions with the speakerphone and bluetooth systems
        switch (mParams.bluetoothInteraction) {
            case NONE:
                verify(mockBluetoothRouteManager, never()).disconnectBluetoothAudio();
                verify(mockBluetoothRouteManager, never())
                        .connectBluetoothAudio(nullable(String.class));
                break;
            case ON:
                if (mParams.expectedBluetoothDevice == null) {
                    verify(mockBluetoothRouteManager, atLeastOnce()).connectBluetoothAudio(null);
                } else {
                    verify(mockBluetoothRouteManager).connectBluetoothAudio(
                            mParams.expectedBluetoothDevice.getAddress());
                }
                verify(mockBluetoothRouteManager, never()).disconnectBluetoothAudio();
                break;
            case OFF:
                verify(mockBluetoothRouteManager, never())
                        .connectBluetoothAudio(nullable(String.class));
                verify(mockBluetoothRouteManager).disconnectBluetoothAudio();
                break;
            case OPTIONAL:
                // optional, don't test
                break;
        }

        switch (mParams.speakerInteraction) {
            case NONE:
                verify(mockAudioManager, never()).setCommunicationDevice(
                        any(AudioDeviceInfo.class));
                break;
            case ON:
                ArgumentCaptor<AudioDeviceInfo> infoArgumentCaptor = ArgumentCaptor.forClass(
                        AudioDeviceInfo.class);
                verify(mockAudioManager).setCommunicationDevice(infoArgumentCaptor.capture());
                var deviceType = infoArgumentCaptor.getValue().getType();
                if (deviceType != AudioDeviceInfo.TYPE_BUS) { // on automotive, we expect BUS
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, deviceType);
                }
                break;
            case OFF:
                verify(mockAudioManager).clearCommunicationDevice();
                break;
            case OPTIONAL:
                // optional, don't test
                break;
        }

        // Verify the end state
        CallAudioState expectedState = new CallAudioState(false, mParams.expectedRoute,
                mParams.expectedAvailableRoutes | CallAudioState.ROUTE_SPEAKER,
                mParams.expectedBluetoothDevice, mParams.availableBluetoothDevices);
        verifyNewSystemCallAudioState(initState, expectedState);
    }

    @Test
    @SmallTest
    public void testQuiescentTransition() {
        final CallAudioRouteStateMachine stateMachine = new CallAudioRouteStateMachine(
                mContext,
                mockCallsManager,
                mockBluetoothRouteManager,
                mockWiredHeadsetManager,
                mockStatusBarNotifier,
                mAudioServiceFactory,
                mParams.earpieceControl,
                mHandlerThread.getLooper(),
                Runnable::run /** do async stuff sync for test purposes */,
                mCommunicationDeviceTracker,
                mFeatureFlags);
        stateMachine.setCallAudioManager(mockCallAudioManager);

        // Set up bluetooth and speakerphone state
        doReturn((mParams.availableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0 ||
                (mParams.expectedAvailableRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0)
                .when(mockBluetoothRouteManager).isBluetoothAvailable();
        doReturn(mParams.availableBluetoothDevices)
                .when(mockBluetoothRouteManager).getConnectedDevices();
        when(mockAudioManager.isSpeakerphoneOn()).thenReturn(
                mParams.initialRoute == CallAudioState.ROUTE_SPEAKER);
        when(fakeCall.getSupportedAudioRoutes()).thenReturn(mParams.callSupportedRoutes);

        // Set the initial CallAudioState object
        CallAudioState initState = new CallAudioState(false,
                mParams.initialRoute, (mParams.availableRoutes | CallAudioState.ROUTE_SPEAKER),
                mParams.initialBluetoothDevice, mParams.availableBluetoothDevices);
        stateMachine.initialize(initState);
        // Omit the focus-getting statement
        sendActionToStateMachine(stateMachine);

        waitForHandlerAction(stateMachine.getAdapterHandler(), TEST_TIMEOUT);
        waitForHandlerAction(stateMachine.getAdapterHandler(), TEST_TIMEOUT);

        stateMachine.quitStateMachine();

        // Verify that no substantive interactions have taken place with the
        // rest of the system
        verifyNoSystemAudioChanges();

        // Special case for SPEAKER_ON -- we don't expect any route transitions to happen when
        // there are no calls, so set the expected state to the initial route.
        int expectedRoute = (mParams.action == CallAudioRouteStateMachine.SPEAKER_ON)
                ? mParams.initialRoute : mParams.expectedRoute;
        // Verify the end state
        CallAudioState expectedState = new CallAudioState(false, expectedRoute,
                mParams.expectedAvailableRoutes | CallAudioState.ROUTE_SPEAKER,
                mParams.expectedBluetoothDevice, mParams.availableBluetoothDevices);
        assertEquals(expectedState, stateMachine.getCurrentCallAudioState());
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<RoutingTestParameters> testParametersCollection() {
        List<RoutingTestParameters> params = new ArrayList<>();

        params.add(new RoutingTestParameters(
                "Connect headset during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Connect headset during bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // expectedAvai
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Connect headset during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect headset during headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect headset during headset with bluetooth available", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect headset during bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect headset during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect headset during speakerphone with bluetooth available", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Connect bluetooth during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                SPECIAL_CONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_EARPIECE, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ).setAvailableBluetoothDevices(BluetoothRouteManagerTest.DEVICE1));

        params.add(new RoutingTestParameters(
                "Connect bluetooth during wired headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                SPECIAL_CONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvai
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ).setAvailableBluetoothDevices(BluetoothRouteManagerTest.DEVICE1));

        params.add(new RoutingTestParameters(
                "Connect bluetooth during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                ON, // bluetoothInteraction
                SPECIAL_CONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_EARPIECE, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ).setAvailableBluetoothDevices(BluetoothRouteManagerTest.DEVICE1));

        params.add(new RoutingTestParameters(
                "Disconnect bluetooth during bluetooth without headset in", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                SPECIAL_DISCONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect bluetooth during bluetooth with headset in", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                SPECIAL_DISCONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect bluetooth during speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                SPECIAL_DISCONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect bluetooth during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                SPECIAL_DISCONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to speakerphone from earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to speakerphone from headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to speakerphone from bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                ON, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_SPEAKER, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // expectedAvai
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to earpiece from bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to earpiece from speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to earpiece from speakerphone, priority notifications", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to earpiece from speakerphone, silent mode", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_EARPIECE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to bluetooth from speakerphone", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OFF, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to bluetooth from earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailable
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch to bluetooth from wired headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // availableRou
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BLUETOOTH, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_BLUETOOTH, // expectedAvai
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Switch from bluetooth to wired/earpiece when neither are available", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                ON, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SWITCH_BASELINE_ROUTE, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_BLUETOOTH, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_DISABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect wired headset when device does not support earpiece", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_SPEAKER, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_DISABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect wired headset when call doesn't support earpiece", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_WIRED_HEADSET, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_SPEAKER, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ).setCallSupportedRoutes(CallAudioState.ROUTE_ALL & ~CallAudioState.ROUTE_EARPIECE));

        params.add(new RoutingTestParameters(
                "Disconnect bluetooth when call does not support earpiece", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_BLUETOOTH,  // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                SPECIAL_DISCONNECT_BT_ACTION, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_SPEAKER, // expectedAvailableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ).setCallSupportedRoutes(CallAudioState.ROUTE_ALL & ~CallAudioState.ROUTE_EARPIECE));

        params.add(new RoutingTestParameters(
                "Active device deselected during BT", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.BT_ACTIVE_DEVICE_GONE, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailabl
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Active device selected during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OPTIONAL, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.BT_ACTIVE_DEVICE_PRESENT, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailabl
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Speakerphone turned on during earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SPEAKER_ON, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailabl
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Speakerphone turned on during wired headset", // name
                CallAudioState.ROUTE_WIRED_HEADSET, // initialRoute
                CallAudioState.ROUTE_EARPIECE
                        | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                NONE, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.SPEAKER_ON, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE
                        | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_WIRED_HEADSET, // availableRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Speakerphone turned on during bluetooth", // name
                CallAudioState.ROUTE_BLUETOOTH, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                NONE, // speakerInteraction
                OFF, // bluetoothInteraction
                CallAudioRouteStateMachine.SPEAKER_ON, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailabl
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Speakerphone turned off externally during speaker", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // availableRoutes
                OFF, // speakerInteraction
                ON, // bluetoothInteraction
                CallAudioRouteStateMachine.SPEAKER_OFF, // action
                CallAudioState.ROUTE_BLUETOOTH, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH, // expectedAvailabl
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Connect dock from earpiece", // name
                CallAudioState.ROUTE_EARPIECE, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, // availableRoutes
                ON, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.CONNECT_DOCK, // action
                CallAudioState.ROUTE_SPEAKER, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, // expectedAvailRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        params.add(new RoutingTestParameters(
                "Disconnect dock from speaker", // name
                CallAudioState.ROUTE_SPEAKER, // initialRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, // availableRoutes
                OFF, // speakerInteraction
                NONE, // bluetoothInteraction
                CallAudioRouteStateMachine.DISCONNECT_DOCK, // action
                CallAudioState.ROUTE_EARPIECE, // expectedRoute
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, // expectedAvailRoutes
                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED // earpieceControl
        ));

        return params;
    }

    private void verifyNewSystemCallAudioState(CallAudioState expectedOldState,
            CallAudioState expectedNewState) {
        ArgumentCaptor<CallAudioState> oldStateCaptor = ArgumentCaptor.forClass(
                CallAudioState.class);
        ArgumentCaptor<CallAudioState> newStateCaptor1 = ArgumentCaptor.forClass(
                CallAudioState.class);
        ArgumentCaptor<CallAudioState> newStateCaptor2 = ArgumentCaptor.forClass(
                CallAudioState.class);
        verify(mockCallsManager, timeout(TEST_TIMEOUT).atLeastOnce()).onCallAudioStateChanged(
                oldStateCaptor.capture(), newStateCaptor1.capture());
        verify(mockConnectionServiceWrapper, timeout(TEST_TIMEOUT).atLeastOnce())
                .onCallAudioStateChanged(same(fakeCall), newStateCaptor2.capture());

        assertEquals(expectedOldState, oldStateCaptor.getAllValues().get(0));
        assertEquals(expectedNewState, newStateCaptor1.getValue());
        assertEquals(expectedNewState, newStateCaptor2.getValue());
    }

    private void verifyNoSystemAudioChanges() {
        verify(mockBluetoothRouteManager, never()).disconnectBluetoothAudio();
        verify(mockBluetoothRouteManager, never()).connectBluetoothAudio(nullable(String.class));
        verify(mockAudioManager, never()).setSpeakerphoneOn(any(Boolean.class));
        verify(mockCallsManager, never()).onCallAudioStateChanged(any(CallAudioState.class),
                any(CallAudioState.class));
        verify(mockConnectionServiceWrapper, never()).onCallAudioStateChanged(
                any(Call.class), any(CallAudioState.class));
    }

    private void clearInvocations() {
        Mockito.clearInvocations(mockAudioManager, mockBluetoothRouteManager, mockCallsManager,
                mockConnectionServiceWrapper);
    }
}