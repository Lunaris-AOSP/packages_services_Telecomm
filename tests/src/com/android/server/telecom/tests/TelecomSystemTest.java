/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.telecom.tests;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.telecom.Call;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.ParcelableCall;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;

import com.android.internal.telecom.IInCallAdapter;
import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.CallAudioCommunicationDeviceTracker;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioModeStateMachine;
import com.android.server.telecom.CallAudioRouteStateMachine;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceFocusManager;
import com.android.server.telecom.ContactsAsyncHelper;
import com.android.server.telecom.DeviceIdleControllerAdapter;
import com.android.server.telecom.HeadsetMediaButton;
import com.android.server.telecom.HeadsetMediaButtonFactory;
import com.android.server.telecom.InCallWakeLockController;
import com.android.server.telecom.InCallWakeLockControllerFactory;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.PhoneNumberUtilsAdapterImpl;
import com.android.server.telecom.ProximitySensorManager;
import com.android.server.telecom.ProximitySensorManagerFactory;
import com.android.server.telecom.Ringer;
import com.android.server.telecom.RoleManagerAdapter;
import com.android.server.telecom.StatusBarNotifier;
import com.android.server.telecom.SystemStateHelper;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.WiredHeadsetManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.callfiltering.BlockedNumbersAdapter;
import com.android.server.telecom.callsequencing.voip.VoipCallMonitor;
import com.android.server.telecom.components.UserCallIntentProcessor;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.ui.IncomingCallNotifier;

import com.google.common.base.Predicate;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Implements mocks and functionality required to implement telecom system tests.
 */
public class TelecomSystemTest extends TelecomTestCase{

    private static final String CALLING_PACKAGE = TelecomSystemTest.class.getPackageName();
    static final int TEST_POLL_INTERVAL = 10;  // milliseconds
    static final int TEST_TIMEOUT = 1000;  // milliseconds

    // Purposely keep the connect time (which is wall clock) and elapsed time (which is time since
    // boot) different to test that wall clock time operations and elapsed time operations perform
    // as they individually should.
    static final long TEST_CREATE_TIME = 100;
    static final long TEST_CREATE_ELAPSED_TIME = 200;
    static final long TEST_CONNECT_TIME = 1000;
    static final long TEST_CONNECT_ELAPSED_TIME = 2000;
    static final long TEST_DISCONNECT_TIME = 8000;
    static final long TEST_DISCONNECT_ELAPSED_TIME = 4000;

    public class HeadsetMediaButtonFactoryF implements HeadsetMediaButtonFactory  {
        @Override
        public HeadsetMediaButton create(Context context, CallsManager callsManager,
                TelecomSystem.SyncRoot lock) {
            return mHeadsetMediaButton;
        }
    }

    public class ProximitySensorManagerFactoryF implements ProximitySensorManagerFactory {
        @Override
        public ProximitySensorManager create(Context context, CallsManager callsManager) {
            return mProximitySensorManager;
        }
    }

    public class InCallWakeLockControllerFactoryF implements InCallWakeLockControllerFactory {
        @Override
        public InCallWakeLockController create(Context context, CallsManager callsManager) {
            return mInCallWakeLockController;
        }
    }

    public static class MissedCallNotifierFakeImpl extends CallsManagerListenerBase
            implements MissedCallNotifier {
        List<CallInfo> missedCallsNotified = new ArrayList<>();

        @Override
        public void clearMissedCalls(UserHandle userHandle) {

        }

        @Override
        public void showMissedCallNotification(CallInfo call, @Nullable Uri uri) {
            missedCallsNotified.add(call);
        }

        @Override
        public void reloadAfterBootComplete(CallerInfoLookupHelper callerInfoLookupHelper,
                CallInfoFactory callInfoFactory) { }

        @Override
        public void reloadFromDatabase(CallerInfoLookupHelper callerInfoLookupHelper,
                CallInfoFactory callInfoFactory, UserHandle userHandle) { }

        @Override
        public void setCurrentUserHandle(UserHandle userHandle) {

        }
    }

    MissedCallNotifierFakeImpl mMissedCallNotifier = new MissedCallNotifierFakeImpl();

    private class IncomingCallAddedListener extends CallsManagerListenerBase {

        private final CountDownLatch mCountDownLatch;

        public IncomingCallAddedListener(CountDownLatch latch) {
            mCountDownLatch = latch;
        }

        @Override
        public void onCallAdded(com.android.server.telecom.Call call) {
            mCountDownLatch.countDown();
        }
    }

    @Mock HeadsetMediaButton mHeadsetMediaButton;
    @Mock ProximitySensorManager mProximitySensorManager;
    @Mock InCallWakeLockController mInCallWakeLockController;
    @Mock AsyncRingtonePlayer mAsyncRingtonePlayer;
    @Mock IncomingCallNotifier mIncomingCallNotifier;
    @Mock ClockProxy mClockProxy;
    @Mock RoleManagerAdapter mRoleManagerAdapter;
    @Mock ToneGenerator mToneGenerator;
    @Mock DeviceIdleControllerAdapter mDeviceIdleControllerAdapter;

    @Mock Ringer.AccessibilityManagerAdapter mAccessibilityManagerAdapter;
    @Mock
    BlockedNumbersAdapter mBlockedNumbersAdapter;
    @Mock
    CallAudioCommunicationDeviceTracker mCommunicationDeviceTracker;
    @Mock
    FeatureFlags mFeatureFlags;
    @Mock
    com.android.internal.telephony.flags.FeatureFlags mTelephonyFlags;

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    final ComponentName mInCallServiceComponentNameX =
            new ComponentName(
                    "incall-service-package-X",
                    "incall-service-class-X");
    private static final int SERVICE_X_UID = 1;
    final ComponentName mInCallServiceComponentNameY =
            new ComponentName(
                    "incall-service-package-Y",
                    "incall-service-class-Y");
    private static final int SERVICE_Y_UID = 1;
    InCallServiceFixture mInCallServiceFixtureX;
    InCallServiceFixture mInCallServiceFixtureY;

    final ComponentName mConnectionServiceComponentNameA =
            new ComponentName(
                    "connection-service-package-A",
                    "connection-service-class-A");
    final ComponentName mConnectionServiceComponentNameB =
            new ComponentName(
                    "connection-service-package-B",
                    "connection-service-class-B");

    final PhoneAccount mPhoneAccountA0 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id A 0"),
                    "Phone account service A ID 0")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_VIDEO_CALLING)
                    .build();
    final PhoneAccount mPhoneAccountA1 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id A 1"),
                    "Phone account service A ID 1")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_VIDEO_CALLING)
                    .build();
    final PhoneAccount mPhoneAccountA2 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id A 2"),
                    "Phone account service A ID 2")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    .build();
    final PhoneAccount mPhoneAccountSelfManaged =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id SM"),
                    "Phone account service A SM")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build();
    final PhoneAccount mPhoneAccountB0 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameB,
                            "id B 0"),
                    "Phone account service B ID 0")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_VIDEO_CALLING)
                    .build();
    final PhoneAccount mPhoneAccountE0 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id E 0"),
                    "Phone account service E ID 0")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)
                    .build();

    final PhoneAccount mPhoneAccountE1 =
            PhoneAccount.builder(
                    new PhoneAccountHandle(
                            mConnectionServiceComponentNameA,
                            "id E 1"),
                    "Phone account service E ID 1")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)
                    .build();

    final PhoneAccount mPhoneAccountMultiUser =
            PhoneAccount.builder(
                            new PhoneAccountHandle(
                                    mConnectionServiceComponentNameA,
                                    "id MU", UserHandle.of(12)),
                            "Phone account service MU")
                    .addSupportedUriScheme("tel")
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_CALL_PROVIDER |
                                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                                    PhoneAccount.CAPABILITY_VIDEO_CALLING |
                                    PhoneAccount.CAPABILITY_MULTI_USER)
                    .build();

    ConnectionServiceFixture mConnectionServiceFixtureA;
    ConnectionServiceFixture mConnectionServiceFixtureB;
    Timeouts.Adapter mTimeoutsAdapter;

    CallerInfoAsyncQueryFactoryFixture mCallerInfoAsyncQueryFactoryFixture;

    IAudioService mAudioService;

    TelecomSystem mTelecomSystem;

    Context mSpyContext;

    ConnectionServiceFocusManager mConnectionServiceFocusManager;

    private HandlerThread mHandlerThread;

    private int mNumOutgoingCallsMade;

    class IdPair {
        final String mConnectionId;
        final String mCallId;

        public IdPair(String connectionId, String callId) {
            this.mConnectionId = connectionId;
            this.mCallId = callId;
        }
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mSpyContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        doReturn(mSpyContext).when(mSpyContext).getApplicationContext();
        doNothing().when(mSpyContext).sendBroadcastAsUser(any(), any(), any());

        doReturn(mock(AppOpsManager.class)).when(mSpyContext).getSystemService(AppOpsManager.class);
        doReturn(mock(BluetoothManager.class)).when(mSpyContext).getSystemService(BluetoothManager.class);

        mHandlerThread = new HandlerThread("TelecomHandlerThread");
        mHandlerThread.start();

        mNumOutgoingCallsMade = 0;

        doReturn(false).when(mComponentContextFixture.getTelephonyManager())
                .isEmergencyNumber(any());
        doReturn(false).when(mComponentContextFixture.getTelephonyManager())
                .isPotentialEmergencyNumber(any());

        // First set up information about the In-Call services in the mock Context, since
        // Telecom will search for these as soon as it is instantiated
        setupInCallServices();

        // Next, create the TelecomSystem, our system under test
        setupTelecomSystem();
        // Need to reset testing tag here
        Log.setTag(TESTING_TAG);

        // Finally, register the ConnectionServices with the PhoneAccountRegistrar of the
        // now-running TelecomSystem
        setupConnectionServices();

        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
    }

    @Override
    public void tearDown() throws Exception {
        if (mTelecomSystem != null && mTelecomSystem.getCallsManager() != null) {
            mTelecomSystem.getCallsManager().waitOnHandlers();
            LinkedList<HandlerThread> handlerThreads = mTelecomSystem.getCallsManager()
                    .getGraphHandlerThreads();
            for (HandlerThread handlerThread : handlerThreads) {
                handlerThread.quitSafely();
            }
            handlerThreads.clear();

            VoipCallMonitor vcm = mTelecomSystem.getCallsManager().getVoipCallMonitor();
            if (vcm != null) {
                vcm.unregisterNotificationListener();
            }
        }
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        waitForHandlerAction(mHandlerThread.getThreadHandler(), TEST_TIMEOUT);
        // Bring down the threads that are active.
        mHandlerThread.quit();
        try {
            mHandlerThread.join();
        } catch (InterruptedException e) {
            // don't do anything
        }

        if (mConnectionServiceFocusManager != null) {
            mConnectionServiceFocusManager.getHandler().removeCallbacksAndMessages(null);
            waitForHandlerAction(mConnectionServiceFocusManager.getHandler(), TEST_TIMEOUT);
            mConnectionServiceFocusManager.getHandler().getLooper().quit();
        }

        if (mConnectionServiceFixtureA != null) {
            mConnectionServiceFixtureA.waitForHandlerToClear();
        }

        if (mConnectionServiceFixtureA != null) {
            mConnectionServiceFixtureB.waitForHandlerToClear();
        }

        // Forcefully clean all sessions at the end of the test, which will also log any stale
        // sessions for debugging.
        Log.getSessionManager().cleanupStaleSessions(0);

        mTelecomSystem = null;
        super.tearDown();
    }

    protected ParcelableCall makeConferenceCall(
            Intent callIntentExtras1, Intent callIntentExtras2) throws Exception {
        IdPair callId1 = startAndMakeActiveOutgoingCallWithExtras("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA, callIntentExtras1);

        IdPair callId2 = startAndMakeActiveOutgoingCallWithExtras("650-555-1213",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA, callIntentExtras2);

        IInCallAdapter inCallAdapter = mInCallServiceFixtureX.getInCallAdapter();
        inCallAdapter.conference(callId1.mCallId, callId2.mCallId);
        // Wait for the handler in ConnectionService
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        ParcelableCall call1 = mInCallServiceFixtureX.getCall(callId1.mCallId);
        ParcelableCall call2 = mInCallServiceFixtureX.getCall(callId2.mCallId);
        // Check that the two calls end up with a parent in the end
        assertNotNull(call1.getParentCallId());
        assertNotNull(call2.getParentCallId());
        assertEquals(call1.getParentCallId(), call2.getParentCallId());

        // Check to make sure that the parent call made it to the in-call service
        String parentCallId = call1.getParentCallId();
        ParcelableCall conferenceCall = mInCallServiceFixtureX.getCall(parentCallId);
        assertEquals(2, conferenceCall.getChildCallIds().size());
        assertTrue(conferenceCall.getChildCallIds().contains(callId1.mCallId));
        assertTrue(conferenceCall.getChildCallIds().contains(callId2.mCallId));
        return conferenceCall;
    }

    private void setupTelecomSystem() throws Exception {
        // Remove any cached PhoneAccount xml
        File phoneAccountFile =
                new File(mComponentContextFixture.getTestDouble()
                        .getApplicationContext().getFilesDir(),
                        PhoneAccountRegistrar.FILE_NAME);
        if (phoneAccountFile.exists()) {
            phoneAccountFile.delete();
        }

        // Use actual implementations instead of mocking the interface out.
        HeadsetMediaButtonFactory headsetMediaButtonFactory =
                spy(new HeadsetMediaButtonFactoryF());
        ProximitySensorManagerFactory proximitySensorManagerFactory =
                spy(new ProximitySensorManagerFactoryF());
        InCallWakeLockControllerFactory inCallWakeLockControllerFactory =
                spy(new InCallWakeLockControllerFactoryF());
        mAudioService = setupAudioService();

        mCallerInfoAsyncQueryFactoryFixture = new CallerInfoAsyncQueryFactoryFixture();

        ConnectionServiceFocusManager.ConnectionServiceFocusManagerFactory mConnServFMFactory =
                requester -> {
                    mConnectionServiceFocusManager = new ConnectionServiceFocusManager(requester);
                    return mConnectionServiceFocusManager;
                };

        mTimeoutsAdapter = mock(Timeouts.Adapter.class);
        when(mTimeoutsAdapter.getCallScreeningTimeoutMillis(any(ContentResolver.class)))
                .thenReturn(TEST_TIMEOUT / 5L);
        mIncomingCallNotifier = mock(IncomingCallNotifier.class);
        mClockProxy = mock(ClockProxy.class);
        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_CREATE_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_CREATE_ELAPSED_TIME);
        when(mRoleManagerAdapter.getCallCompanionApps()).thenReturn(Collections.emptyList());
        when(mRoleManagerAdapter.getDefaultCallScreeningApp(any(UserHandle.class)))
                .thenReturn(null);
        when(mRoleManagerAdapter.getBTInCallService()).thenReturn(new String[] {"bt_pkg"});
        when(mFeatureFlags.callAudioCommunicationDeviceRefactor()).thenReturn(true);
        when(mFeatureFlags.useRefactoredAudioRouteSwitching()).thenReturn(false);
        mTelecomSystem = new TelecomSystem(
                mComponentContextFixture.getTestDouble(),
                (context, phoneAccountRegistrar, defaultDialerCache, mDeviceIdleControllerAdapter,
                        mFeatureFlag)
                        -> mMissedCallNotifier,
                mCallerInfoAsyncQueryFactoryFixture.getTestDouble(),
                headsetMediaButtonFactory,
                proximitySensorManagerFactory,
                inCallWakeLockControllerFactory,
                () -> mAudioService,
                mConnServFMFactory,
                mTimeoutsAdapter,
                mAsyncRingtonePlayer,
                new PhoneNumberUtilsAdapterImpl(),
                mIncomingCallNotifier,
                (streamType, volume) -> mToneGenerator,
                new CallAudioRouteStateMachine.Factory() {
                    @Override
                    public CallAudioRouteStateMachine create(
                            Context context,
                            CallsManager callsManager,
                            BluetoothRouteManager bluetoothManager,
                            WiredHeadsetManager wiredHeadsetManager,
                            StatusBarNotifier statusBarNotifier,
                            CallAudioManager.AudioServiceFactory audioServiceFactory,
                            int earpieceControl,
                            Executor asyncTaskExecutor,
                            CallAudioCommunicationDeviceTracker communicationDeviceTracker,
                            FeatureFlags featureFlags) {
                        return new CallAudioRouteStateMachine(context,
                                callsManager,
                                bluetoothManager,
                                wiredHeadsetManager,
                                statusBarNotifier,
                                audioServiceFactory,
                                // Force enable an earpiece for the end-to-end tests
                                CallAudioRouteStateMachine.EARPIECE_FORCE_ENABLED,
                                mHandlerThread.getLooper(),
                                Runnable::run /* async tasks as now sync for testing! */,
                                communicationDeviceTracker,
                                featureFlags);
                    }
                },
                new CallAudioModeStateMachine.Factory() {
                    @Override
                    public CallAudioModeStateMachine create(SystemStateHelper systemStateHelper,
                            AudioManager am, FeatureFlags featureFlags,
                            CallAudioCommunicationDeviceTracker callAudioCommunicationDeviceTracker
                    ) {
                        return new CallAudioModeStateMachine(systemStateHelper, am,
                                mHandlerThread.getLooper(), featureFlags,
                                callAudioCommunicationDeviceTracker);
                    }
                },
                mClockProxy,
                mRoleManagerAdapter,
                new ContactsAsyncHelper.Factory() {
                    @Override
                    public ContactsAsyncHelper create(
                            ContactsAsyncHelper.ContentResolverAdapter adapter) {
                        return new ContactsAsyncHelper(adapter, mHandlerThread.getLooper());
                    }
                }, mDeviceIdleControllerAdapter, SYSTEM_UI_PACKAGE,
                mAccessibilityManagerAdapter,
                Runnable::run,
                Runnable::run,
                mBlockedNumbersAdapter,
                mFeatureFlags,
                mTelephonyFlags,
                mHandlerThread.getLooper());

        mComponentContextFixture.setTelecomManager(new TelecomManager(
                mComponentContextFixture.getTestDouble(),
                mTelecomSystem.getTelecomServiceImpl().getBinder()));

        verify(headsetMediaButtonFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class),
                any(TelecomSystem.SyncRoot.class));
        verify(proximitySensorManagerFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class));
        verify(inCallWakeLockControllerFactory).create(
                eq(mComponentContextFixture.getTestDouble().getApplicationContext()),
                any(CallsManager.class));
    }

    private void setupConnectionServices() throws Exception {
        mConnectionServiceFixtureA = new ConnectionServiceFixture(mContext);
        mConnectionServiceFixtureB = new ConnectionServiceFixture(mContext);

        mComponentContextFixture.addConnectionService(mConnectionServiceComponentNameA,
                mConnectionServiceFixtureA.getTestDouble());
        mComponentContextFixture.addConnectionService(mConnectionServiceComponentNameB,
                mConnectionServiceFixtureB.getTestDouble());

        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountA0);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountA1);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountA2);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountSelfManaged);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountB0);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountE0);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountE1);
        mTelecomSystem.getPhoneAccountRegistrar().registerPhoneAccount(mPhoneAccountMultiUser);

        mTelecomSystem.getPhoneAccountRegistrar().setUserSelectedOutgoingPhoneAccount(
                mPhoneAccountA0.getAccountHandle(), Process.myUserHandle());
    }

    private void setupInCallServices() throws Exception {
        mComponentContextFixture.putResource(
                com.android.internal.R.string.config_defaultDialer,
                mInCallServiceComponentNameX.getPackageName());
        mComponentContextFixture.putResource(
                com.android.server.telecom.R.string.incall_default_class,
                mInCallServiceComponentNameX.getClassName());

        mInCallServiceFixtureX = new InCallServiceFixture();
        mInCallServiceFixtureY = new InCallServiceFixture();

        mComponentContextFixture.addInCallService(mInCallServiceComponentNameX,
                mInCallServiceFixtureX.getTestDouble(), SERVICE_X_UID);
        mComponentContextFixture.addInCallService(mInCallServiceComponentNameY,
                mInCallServiceFixtureY.getTestDouble(), SERVICE_Y_UID);
    }

    /**
     * Helper method for setting up the fake audio service.
     * Calls to the fake audio service need to toggle the return
     * value of AudioManager#isMicrophoneMute.
     * @return mock of IAudioService
     */
    private IAudioService setupAudioService() {
        IAudioService audioService = mock(IAudioService.class);

        final AudioManager fakeAudioManager =
                (AudioManager) mComponentContextFixture.getTestDouble()
                        .getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        try {
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock i) {
                    Object[] args = i.getArguments();
                    doReturn(args[0]).when(fakeAudioManager).isMicrophoneMute();
                    return null;
                }
            }).when(audioService).setMicrophoneMute(any(Boolean.class), any(String.class),
                    any(Integer.class), nullable(String.class));

        } catch (android.os.RemoteException e) {
            // Do nothing, leave the faked microphone state as-is
        }
        return audioService;
    }

    protected String startOutgoingPhoneCallWithNoPhoneAccount(String number,
            ConnectionServiceFixture connectionServiceFixture)
            throws Exception {

        startOutgoingPhoneCallWaitForBroadcaster(number, null,
                connectionServiceFixture, Process.myUserHandle(), VideoProfile.STATE_AUDIO_ONLY,
                false /*isEmergency*/, null);

        return mInCallServiceFixtureX.mLatestCallId;
    }

    protected IdPair outgoingCallPhoneAccountSelected(PhoneAccountHandle phoneAccountHandle,
            int startingNumConnections, int startingNumCalls,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {

        IdPair ids = outgoingCallCreateConnectionComplete(startingNumConnections, startingNumCalls,
                phoneAccountHandle, connectionServiceFixture);

        connectionServiceFixture.sendSetDialing(ids.mConnectionId);
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        connectionServiceFixture.sendSetVideoState(ids.mConnectionId);

        connectionServiceFixture.sendSetActive(ids.mConnectionId);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        return ids;
    }

    protected IdPair startOutgoingPhoneCall(String number, PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, UserHandle initiatingUser)
            throws Exception {

        return startOutgoingPhoneCall(number, phoneAccountHandle, connectionServiceFixture,
                initiatingUser, VideoProfile.STATE_AUDIO_ONLY, null);
    }

    protected IdPair startOutgoingPhoneCall(String number, PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, UserHandle initiatingUser,
            int videoState, Intent callIntentExtras) throws Exception {
        int startingNumConnections = connectionServiceFixture.mConnectionById.size();
        int startingNumCalls = mInCallServiceFixtureX.mCallById.size();

        startOutgoingPhoneCallPendingCreateConnection(number, phoneAccountHandle,
                connectionServiceFixture, initiatingUser, videoState, callIntentExtras);

        verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                .createConnectionComplete(anyString(), any());

        return outgoingCallCreateConnectionComplete(startingNumConnections, startingNumCalls,
                phoneAccountHandle, connectionServiceFixture);
    }

    protected IdPair triggerEmergencyRedial(PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, IdPair emergencyIds)
            throws Exception {
        int startingNumConnections = connectionServiceFixture.mConnectionById.size();
        int startingNumCalls = mInCallServiceFixtureX.mCallById.size();

        // Send the message to disconnect the Emergency call due to an error.
        // CreateConnectionProcessor should now try the second SIM account
        connectionServiceFixture.sendSetDisconnected(emergencyIds.mConnectionId,
                DisconnectCause.ERROR);
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureX.getCall(
                emergencyIds.mCallId).getState());
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureY.getCall(
                emergencyIds.mCallId).getState());

        return redialingCallCreateConnectionComplete(startingNumConnections, startingNumCalls,
                phoneAccountHandle, connectionServiceFixture);
    }

    protected IdPair startOutgoingEmergencyCall(String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, UserHandle initiatingUser,
            int videoState) throws Exception {
        int startingNumConnections = connectionServiceFixture.mConnectionById.size();
        int startingNumCalls = mInCallServiceFixtureX.mCallById.size();

        doReturn(true).when(mComponentContextFixture.getTelephonyManager())
                .isEmergencyNumber(any());
        doReturn(true).when(mComponentContextFixture.getTelephonyManager())
                .isPotentialEmergencyNumber(any());

        // Call will not use the ordered broadcaster, since it is an Emergency Call
        startOutgoingPhoneCallWaitForBroadcaster(number, phoneAccountHandle,
                connectionServiceFixture, initiatingUser, videoState, true /*isEmergency*/, null);

        return outgoingCallCreateConnectionComplete(startingNumConnections, startingNumCalls,
                phoneAccountHandle, connectionServiceFixture);
    }

    protected void startOutgoingPhoneCallWaitForBroadcaster(String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, UserHandle initiatingUser,
            int videoState, boolean isEmergency, Intent actionCallIntent) throws Exception {
        reset(connectionServiceFixture.getTestDouble(), mInCallServiceFixtureX.getTestDouble(),
                mInCallServiceFixtureY.getTestDouble());

        assertEquals(mInCallServiceFixtureX.mCallById.size(),
                mInCallServiceFixtureY.mCallById.size());
        assertEquals((mInCallServiceFixtureX.mInCallAdapter != null),
                (mInCallServiceFixtureY.mInCallAdapter != null));

        mNumOutgoingCallsMade++;

        boolean hasInCallAdapter = mInCallServiceFixtureX.mInCallAdapter != null;

        if (actionCallIntent == null) {
            actionCallIntent = new Intent();
        }
        actionCallIntent.setData(Uri.parse("tel:" + number));
        actionCallIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        if(isEmergency) {
            actionCallIntent.setAction(Intent.ACTION_CALL_EMERGENCY);
        } else {
            actionCallIntent.setAction(Intent.ACTION_CALL);
        }
        if (phoneAccountHandle != null) {
            actionCallIntent.putExtra(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    phoneAccountHandle);
        }
        if (videoState != VideoProfile.STATE_AUDIO_ONLY) {
            actionCallIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, videoState);
        }

        final UserHandle userHandle = initiatingUser;
        Context localAppContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        new UserCallIntentProcessor(localAppContext, userHandle, mFeatureFlags).processIntent(
                actionCallIntent, null, false, true /* hasCallAppOp*/, false /* isLocal */);
        // Wait for handler to start CallerInfo lookup.
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        // Send the CallerInfo lookup reply.
        mCallerInfoAsyncQueryFactoryFixture.mRequests.forEach(
                CallerInfoAsyncQueryFactoryFixture.Request::reply);
        if (phoneAccountHandle != null) {
            mTelecomSystem.getCallsManager().getLatestPostSelectionProcessingFuture().join();
        }
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        boolean isSelfManaged = phoneAccountHandle == mPhoneAccountSelfManaged.getAccountHandle();
        if (!hasInCallAdapter && !isSelfManaged) {
            verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                    .setInCallAdapter(
                            any(IInCallAdapter.class));
            verify(mInCallServiceFixtureY.getTestDouble(), timeout(TEST_TIMEOUT))
                    .setInCallAdapter(
                            any(IInCallAdapter.class));
        }
    }

    protected String startOutgoingPhoneCallPendingCreateConnection(String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, UserHandle initiatingUser,
            int videoState, Intent callIntentExtras) throws Exception {
        startOutgoingPhoneCallWaitForBroadcaster(number,phoneAccountHandle,
                connectionServiceFixture, initiatingUser,
                videoState, false /*isEmergency*/, callIntentExtras);
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        verifyAndProcessOutgoingCallBroadcast(phoneAccountHandle);
        return mInCallServiceFixtureX.mLatestCallId;
    }

    protected void verifyAndProcessOutgoingCallBroadcast(PhoneAccountHandle phoneAccountHandle) {
        ArgumentCaptor<Intent> newOutgoingCallIntent =
                ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<BroadcastReceiver> newOutgoingCallReceiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            verify(mComponentContextFixture.getTestDouble().getApplicationContext(),
                    times(mNumOutgoingCallsMade))
                    .sendOrderedBroadcastAsUser(
                            newOutgoingCallIntent.capture(),
                            any(UserHandle.class),
                            anyString(),
                            anyInt(),
                            any(Bundle.class),
                            newOutgoingCallReceiver.capture(),
                            nullable(Handler.class),
                            anyInt(),
                            anyString(),
                            nullable(Bundle.class));
            // Pass on the new outgoing call Intent
            // Set a dummy PendingResult so the BroadcastReceiver agrees to accept onReceive()
            newOutgoingCallReceiver.getValue().setPendingResult(
                    new BroadcastReceiver.PendingResult(0, "", null, 0, true, false, null, 0, 0));
            newOutgoingCallReceiver.getValue().setResultData(
                    newOutgoingCallIntent.getValue().getStringExtra(Intent.EXTRA_PHONE_NUMBER));
            newOutgoingCallReceiver.getValue().onReceive(mComponentContextFixture.getTestDouble(),
                    newOutgoingCallIntent.getValue());
        }

    }

    // When Telecom is redialing due to an error, we need to make sure the number of connections
    // increase, but not the number of Calls in the InCallService.
    protected IdPair redialingCallCreateConnectionComplete(int startingNumConnections,
            int startingNumCalls, PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {

        assertEquals(startingNumConnections + 1, connectionServiceFixture.mConnectionById.size());

        verify(connectionServiceFixture.getTestDouble())
                .createConnection(eq(phoneAccountHandle), anyString(), any(ConnectionRequest.class),
                        eq(false)/*isIncoming*/, anyBoolean(), any());
        // Wait for handleCreateConnectionComplete
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        // Make sure the number of registered InCallService Calls stays the same.
        assertEquals(startingNumCalls, mInCallServiceFixtureX.mCallById.size());
        assertEquals(startingNumCalls, mInCallServiceFixtureY.mCallById.size());

        assertEquals(mInCallServiceFixtureX.mLatestCallId, mInCallServiceFixtureY.mLatestCallId);

        return new IdPair(connectionServiceFixture.mLatestConnectionId,
                mInCallServiceFixtureX.mLatestCallId);
    }

    protected IdPair outgoingCallCreateConnectionComplete(int startingNumConnections,
            int startingNumCalls, PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {

        // Wait for the focus tracker.
        waitForHandlerAction(mTelecomSystem.getCallsManager()
                .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

        verify(connectionServiceFixture.getTestDouble())
                .createConnection(eq(phoneAccountHandle), anyString(), any(ConnectionRequest.class),
                        eq(false)/*isIncoming*/, anyBoolean(), any());
        // Wait for handleCreateConnectionComplete
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        assertEquals(startingNumConnections + 1,
                connectionServiceFixture.mConnectionById.size());

        // Wait for the callback in ConnectionService#onAdapterAttached to execute.
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        // Ensure callback to CS on successful creation happened.
        verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                .createConnectionComplete(anyString(), any());

        if (phoneAccountHandle == mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(startingNumCalls, mInCallServiceFixtureX.mCallById.size());
            assertEquals(startingNumCalls, mInCallServiceFixtureY.mCallById.size());
        } else {
            assertEquals(startingNumCalls + 1, mInCallServiceFixtureX.mCallById.size());
            assertEquals(startingNumCalls + 1, mInCallServiceFixtureY.mCallById.size());
        }

        assertEquals(mInCallServiceFixtureX.mLatestCallId, mInCallServiceFixtureY.mLatestCallId);

        return new IdPair(connectionServiceFixture.mLatestConnectionId,
                mInCallServiceFixtureX.mLatestCallId);
    }

    protected IdPair startIncomingPhoneCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            final ConnectionServiceFixture connectionServiceFixture) throws Exception {
        return startIncomingPhoneCall(number, phoneAccountHandle, VideoProfile.STATE_AUDIO_ONLY,
                connectionServiceFixture, null);
    }

    protected IdPair startIncomingPhoneCallWithExtras(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            final ConnectionServiceFixture connectionServiceFixture,
            Bundle extras) throws Exception {
        return startIncomingPhoneCall(number, phoneAccountHandle, VideoProfile.STATE_AUDIO_ONLY,
                connectionServiceFixture, extras);
    }

    protected IdPair startIncomingPhoneCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            int videoState,
            final ConnectionServiceFixture connectionServiceFixture,
            Bundle extras) throws Exception {
        reset(connectionServiceFixture.getTestDouble(), mInCallServiceFixtureX.getTestDouble(),
                mInCallServiceFixtureY.getTestDouble());

        assertEquals(mInCallServiceFixtureX.mCallById.size(),
                mInCallServiceFixtureY.mCallById.size());
        assertEquals((mInCallServiceFixtureX.mInCallAdapter != null),
                (mInCallServiceFixtureY.mInCallAdapter != null));
        final int startingNumConnections = connectionServiceFixture.mConnectionById.size();
        final int startingNumCalls = mInCallServiceFixtureX.mCallById.size();
        boolean hasInCallAdapter = mInCallServiceFixtureX.mInCallAdapter != null;
        connectionServiceFixture.mConnectionServiceDelegate.mVideoState = videoState;
        CountDownLatch incomingCallAddedLatch = new CountDownLatch(1);
        IncomingCallAddedListener callAddedListener =
                new IncomingCallAddedListener(incomingCallAddedLatch);
        mTelecomSystem.getCallsManager().addListener(callAddedListener);

        if (extras == null) {
            extras = new Bundle();
        }
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(phoneAccountHandle, extras, CALLING_PACKAGE);

        verify(connectionServiceFixture.getTestDouble())
                .createConnection(any(PhoneAccountHandle.class), anyString(),
                        any(ConnectionRequest.class), eq(true), eq(false), any());

        // Wait for the handler to start the CallerInfo lookup
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        // Wait a few more times to address flakiness due to timing issues.
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);
        waitForHandlerAction(new Handler(Looper.getMainLooper()), TEST_TIMEOUT);

        // Ensure callback to CS on successful creation happened.

        verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                .createConnectionComplete(anyString(), any());

        // Process the CallerInfo lookup reply
        mCallerInfoAsyncQueryFactoryFixture.mRequests.forEach(
                CallerInfoAsyncQueryFactoryFixture.Request::reply);

        //Wait for/Verify call blocking happened asynchronously
        incomingCallAddedLatch.await(TEST_TIMEOUT, TimeUnit.MILLISECONDS);

        // For the case of incoming calls, Telecom connecting the InCall services and adding the
        // Call is triggered by the async completion of the CallerInfoAsyncQuery. Once the Call
        // is added, future interactions as triggered by the ConnectionService, through the various
        // test fixtures, will be synchronous.

        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            if (!hasInCallAdapter) {
                verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                        .setInCallAdapter(any(IInCallAdapter.class));
                verify(mInCallServiceFixtureY.getTestDouble(), timeout(TEST_TIMEOUT))
                        .setInCallAdapter(any(IInCallAdapter.class));

                // Give the InCallService time to respond
                assertTrueWithTimeout(new Predicate<Void>() {
                    @Override
                    public boolean apply(Void v) {
                        return mInCallServiceFixtureX.mInCallAdapter != null;
                    }
                });

                assertTrueWithTimeout(new Predicate<Void>() {
                    @Override
                    public boolean apply(Void v) {
                        return mInCallServiceFixtureY.mInCallAdapter != null;
                    }
                });

                verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                        .addCall(any(ParcelableCall.class));
                verify(mInCallServiceFixtureY.getTestDouble(), timeout(TEST_TIMEOUT))
                        .addCall(any(ParcelableCall.class));

                // Give the InCallService time to respond
            }

            assertTrueWithTimeout(new Predicate<Void>() {
                @Override
                public boolean apply(Void v) {
                    return startingNumConnections + 1 ==
                            connectionServiceFixture.mConnectionById.size();
                }
            });

            mInCallServiceFixtureX.waitUntilNumCalls(startingNumCalls + 1);
            mInCallServiceFixtureY.waitUntilNumCalls(startingNumCalls + 1);
            assertEquals(startingNumCalls + 1, mInCallServiceFixtureX.mCallById.size());
            assertEquals(startingNumCalls + 1, mInCallServiceFixtureY.mCallById.size());

            assertEquals(mInCallServiceFixtureX.mLatestCallId,
                    mInCallServiceFixtureY.mLatestCallId);
        }

        return new IdPair(connectionServiceFixture.mLatestConnectionId,
                mInCallServiceFixtureX.mLatestCallId);
    }

    protected IdPair startAndMakeActiveOutgoingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        return startAndMakeActiveOutgoingCall(number, phoneAccountHandle, connectionServiceFixture,
                VideoProfile.STATE_AUDIO_ONLY, null);
    }

    protected IdPair startAndMakeActiveOutgoingCallWithExtras(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture,
            Intent callIntentExtras) throws Exception {
        return startAndMakeActiveOutgoingCall(number, phoneAccountHandle, connectionServiceFixture,
                VideoProfile.STATE_AUDIO_ONLY, callIntentExtras);
    }

    // A simple outgoing call, verifying that the appropriate connection service is contacted,
    // the proper lifecycle is followed, and both In-Call Services are updated correctly.
    protected IdPair startAndMakeActiveOutgoingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture, int videoState,
            Intent callIntentExtras) throws Exception {
        IdPair ids = startOutgoingPhoneCall(number, phoneAccountHandle, connectionServiceFixture,
                Process.myUserHandle(), videoState, callIntentExtras);

        connectionServiceFixture.sendSetDialing(ids.mConnectionId);
        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_DIALING,
                    mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_DIALING,
                    mInCallServiceFixtureY.getCall(ids.mCallId).getState());
        }

        connectionServiceFixture.sendSetVideoState(ids.mConnectionId);

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_CONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_CONNECT_ELAPSED_TIME);
        connectionServiceFixture.sendSetActive(ids.mConnectionId);
        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

            if ((mInCallServiceFixtureX.getCall(ids.mCallId).getProperties() &
                    Call.Details.PROPERTY_IS_EXTERNAL_CALL) == 0) {
                // Test the PhoneStateBroadcaster functionality if the call is not external.
                verify(mContext.getSystemService(TelephonyRegistryManager.class),
                        timeout(TEST_TIMEOUT).atLeastOnce())
                        .notifyCallStateChangedForAllSubscriptions(
                                eq(TelephonyManager.CALL_STATE_OFFHOOK),
                                nullable(String.class));
            }
        }
        return ids;
    }

    protected IdPair startAndMakeActiveIncomingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        return startAndMakeActiveIncomingCall(number, phoneAccountHandle, connectionServiceFixture,
                VideoProfile.STATE_AUDIO_ONLY);
    }

    // A simple incoming call, similar in scope to the previous test
    protected IdPair startAndMakeActiveIncomingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture,
            int videoState) throws Exception {
        IdPair ids = startIncomingPhoneCall(number, phoneAccountHandle, connectionServiceFixture);

        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_RINGING,
                    mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_RINGING,
                    mInCallServiceFixtureY.getCall(ids.mCallId).getState());

            mInCallServiceFixtureX.mInCallAdapter
                    .answerCall(ids.mCallId, videoState);
            // Wait on the CS focus manager handler
            waitForHandlerAction(mTelecomSystem.getCallsManager()
                    .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

            if (!VideoProfile.isVideo(videoState)) {
                verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                        .answer(eq(ids.mConnectionId), any());
            } else {
                verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                        .answerVideo(eq(ids.mConnectionId), eq(videoState), any());
            }
        }

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_CONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_CONNECT_ELAPSED_TIME);
        connectionServiceFixture.sendSetActive(ids.mConnectionId);

        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

            if ((mInCallServiceFixtureX.getCall(ids.mCallId).getProperties() &
                    Call.Details.PROPERTY_IS_EXTERNAL_CALL) == 0) {
                // Test the PhoneStateBroadcaster functionality if the call is not external.
                verify(mContext.getSystemService(TelephonyRegistryManager.class),
                        timeout(TEST_TIMEOUT).atLeastOnce())
                        .notifyCallStateChangedForAllSubscriptions(
                                eq(TelephonyManager.CALL_STATE_OFFHOOK),
                                nullable(String.class));
            }
        }
        return ids;
    }

    protected IdPair startAndMakeDialingEmergencyCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        IdPair ids = startOutgoingEmergencyCall(number, phoneAccountHandle,
                connectionServiceFixture, Process.myUserHandle(), VideoProfile.STATE_AUDIO_ONLY);

        connectionServiceFixture.sendSetDialing(ids.mConnectionId);
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        return ids;
    }

    protected IdPair startAndMakeDialingOutgoingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        IdPair ids = startOutgoingPhoneCall(number, phoneAccountHandle, connectionServiceFixture,
                Process.myUserHandle(), VideoProfile.STATE_AUDIO_ONLY, null);

        connectionServiceFixture.sendSetDialing(ids.mConnectionId);
        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_DIALING,
                    mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_DIALING,
                    mInCallServiceFixtureY.getCall(ids.mCallId).getState());
        }

        return ids;
    }

    protected IdPair startAndMakeRingingIncomingCall(
            String number,
            PhoneAccountHandle phoneAccountHandle,
            ConnectionServiceFixture connectionServiceFixture) throws Exception {
        IdPair ids = startIncomingPhoneCall(number, phoneAccountHandle, connectionServiceFixture);

        if (phoneAccountHandle != mPhoneAccountSelfManaged.getAccountHandle()) {
            assertEquals(Call.STATE_RINGING,
                    mInCallServiceFixtureX.getCall(ids.mCallId).getState());
            assertEquals(Call.STATE_RINGING,
                    mInCallServiceFixtureY.getCall(ids.mCallId).getState());

            mInCallServiceFixtureX.mInCallAdapter
                    .answerCall(ids.mCallId, VideoProfile.STATE_AUDIO_ONLY);

            waitForHandlerAction(mTelecomSystem.getCallsManager()
                    .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

            if (!VideoProfile.isVideo(VideoProfile.STATE_AUDIO_ONLY)) {
                verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                        .answer(eq(ids.mConnectionId), any());
            } else {
                verify(connectionServiceFixture.getTestDouble(), timeout(TEST_TIMEOUT))
                        .answerVideo(eq(ids.mConnectionId), eq(VideoProfile.STATE_AUDIO_ONLY),
                                any());
            }
        }
        return ids;
    }

    protected static void assertTrueWithTimeout(Predicate<Void> predicate) {
        int elapsed = 0;
        while (elapsed < TEST_TIMEOUT) {
            if (predicate.apply(null)) {
                return;
            } else {
                try {
                    Thread.sleep(TEST_POLL_INTERVAL);
                    elapsed += TEST_POLL_INTERVAL;
                } catch (InterruptedException e) {
                    fail(e.toString());
                }
            }
        }
        fail("Timeout in assertTrueWithTimeout");
    }
}
