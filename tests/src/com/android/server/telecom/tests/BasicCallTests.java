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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static com.android.server.telecom.callfiltering.BlockCheckerFilter.RES_BLOCK_STATUS;
import static com.android.server.telecom.callfiltering.BlockCheckerFilter.STATUS_BLOCKED_IN_LIST;
import static com.android.server.telecom.tests.ConnectionServiceFixture.STATUS_HINTS_EXTRA;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.BlockedNumberContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallerInfo;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.ParcelableCall;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.internal.telecom.IInCallAdapter;

import com.google.common.base.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Performs various basic call tests in Telecom.
 */
@RunWith(JUnit4.class)
public class BasicCallTests extends TelecomSystemTest {
    private static final String CALLING_PACKAGE = BasicCallTests.class.getPackageName();
    private static final String TEST_BUNDLE_KEY = "android.telecom.extra.TEST";
    private static final String TEST_EVENT = "android.telecom.event.TEST";

    private PackageManager mPackageManager;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        doReturn(mContext).when(mContext).createContextAsUser(any(UserHandle.class), anyInt());
        mPackageManager = mContext.getPackageManager();
        when(mPackageManager.getPackageUid(anyString(), eq(0))).thenReturn(Binder.getCallingUid());
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(false);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @LargeTest
    @Test
    public void testSingleOutgoingCallLocalDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
        assertEquals(TEST_CONNECT_TIME,
                mInCallServiceFixtureX.getCall(ids.mCallId).getConnectTimeMillis());
        assertEquals(TEST_CONNECT_TIME,
                mInCallServiceFixtureY.getCall(ids.mCallId).getConnectTimeMillis());
        assertEquals(TEST_CREATE_TIME,
                mInCallServiceFixtureX.getCall(ids.mCallId).getCreationTimeMillis());
        assertEquals(TEST_CREATE_TIME,
                mInCallServiceFixtureY.getCall(ids.mCallId).getCreationTimeMillis());

        verifyNoBlockChecks();
    }

    @LargeTest
    @Test
    public void testSingleOutgoingCallRemoteDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
        verifyNoBlockChecks();
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * audio-only call.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testTelecomManagerAcceptRingingCall() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall();

        waitForHandlerAction(mTelecomSystem.getCallsManager()
                .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answer(eq(ids.mConnectionId), any());
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * video call, which should be answered as video.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testTelecomManagerAcceptRingingVideoCall() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA, null);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call; the default expected behavior is to
        // answer using whatever video state the ringing call requests.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall();

        // Answer video API should be called
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answerVideo(eq(ids.mConnectionId), eq(VideoProfile.STATE_BIDIRECTIONAL), any());
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall(int)} API.  Tests answering a video call
     * as an audio call.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testTelecomManagerAcceptRingingVideoCallAsAudio() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA, null);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY);

        waitForHandlerAction(mTelecomSystem.getCallsManager()
                .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

        // The generic answer method on the ConnectionService is used to answer audio-only calls.
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answer(eq(ids.mConnectionId), any());
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link TelecomManager#acceptRingingCall()} API.  Tests simple case of an incoming
     * video call, where an attempt is made to answer with an invalid video state.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testTelecomManagerAcceptRingingInvalidVideoState() throws Exception {
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                VideoProfile.STATE_BIDIRECTIONAL, mConnectionServiceFixtureA, null);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        // Use TelecomManager API to answer the ringing call; the default expected behavior is to
        // answer using whatever video state the ringing call requests.
        TelecomManager telecomManager = (TelecomManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        telecomManager.acceptRingingCall(999 /* invalid videostate */);

        waitForHandlerAction(mTelecomSystem.getCallsManager()
                .getConnectionServiceFocusManager().getHandler(), TEST_TIMEOUT);

        // Answer video API should be called
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .answerVideo(eq(ids.mConnectionId), eq(VideoProfile.STATE_BIDIRECTIONAL), any());
        mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    @LargeTest
    @Test
    public void testSingleIncomingCallLocalDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);

        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    @LargeTest
    @Test
    public void testSingleIncomingCallRemoteDisconnect() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    @LargeTest
    @Test
    public void testIncomingEmergencyCallback() throws Exception {
        // Make an outgoing emergency call
        String phoneNumber = "650-555-1212";
        IdPair ids = startAndMakeDialingEmergencyCall(phoneNumber,
                mPhoneAccountE0.getAccountHandle(), mConnectionServiceFixtureA);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);

        // Incoming call should be marked as a potential emergency callback
        Bundle extras = new Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, phoneNumber, null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(mPhoneAccountA0.getAccountHandle(), extras, CALLING_PACKAGE);

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        ArgumentCaptor<ConnectionRequest> connectionRequestCaptor
            = ArgumentCaptor.forClass(ConnectionRequest.class);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .createConnection(any(PhoneAccountHandle.class), anyString(),
                        connectionRequestCaptor.capture(), eq(true), eq(false), any());

        assertTrue(connectionRequestCaptor.getValue().getExtras().containsKey(
            android.telecom.Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS));
        assertTrue(connectionRequestCaptor.getValue().getExtras().getLong(
            android.telecom.Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS, 0) > 0);
        assertTrue(connectionRequestCaptor.getValue().getExtras().containsKey(
            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS));
    }

    @LargeTest
    @Test
    public void testOutgoingCallAndSelectPhoneAccount() throws Exception {
        // Remove default PhoneAccount so that the Call moves into the correct
        // SELECT_PHONE_ACCOUNT state.
        mTelecomSystem.getPhoneAccountRegistrar().setUserSelectedOutgoingPhoneAccount(
                null, Process.myUserHandle());
        int startingNumConnections = mConnectionServiceFixtureA.mConnectionById.size();
        int startingNumCalls = mInCallServiceFixtureX.mCallById.size();
        String callId = startOutgoingPhoneCallWithNoPhoneAccount("650-555-1212",
                mConnectionServiceFixtureA);
        mTelecomSystem.getCallsManager().getLatestPreAccountSelectionFuture().join();
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        assertEquals(Call.STATE_SELECT_PHONE_ACCOUNT,
                mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(Call.STATE_SELECT_PHONE_ACCOUNT,
                mInCallServiceFixtureY.getCall(callId).getState());
        mInCallServiceFixtureX.mInCallAdapter.phoneAccountSelected(callId,
                mPhoneAccountA0.getAccountHandle(), false);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        verifyAndProcessOutgoingCallBroadcast(mPhoneAccountA0.getAccountHandle());

        IdPair ids = outgoingCallPhoneAccountSelected(mPhoneAccountA0.getAccountHandle(),
                startingNumConnections, startingNumCalls, mConnectionServiceFixtureA);

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(ids.mConnectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTED,
                mInCallServiceFixtureY.getCall(ids.mCallId).getState());
    }

    @FlakyTest
    @LargeTest
    @Test
    public void testIncomingCallFromContactWithSendToVoicemailIsRejected() throws Exception {
        Bundle extras = new Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, "650-555-1212", null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(mPhoneAccountA0.getAccountHandle(), extras, CALLING_PACKAGE);

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .createConnection(any(PhoneAccountHandle.class), anyString(),
                        any(ConnectionRequest.class), eq(true), eq(false), any());

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        assertEquals(1, mCallerInfoAsyncQueryFactoryFixture.mRequests.size());

        CallerInfo sendToVoicemailCallerInfo = new CallerInfo();
        sendToVoicemailCallerInfo.shouldSendToVoicemail = true;
        sendToVoicemailCallerInfo.contactExists = true;
        mCallerInfoAsyncQueryFactoryFixture.setResponse(sendToVoicemailCallerInfo);
        for (CallerInfoAsyncQueryFactoryFixture.Request request :
                mCallerInfoAsyncQueryFactoryFixture.mRequests) {
            request.replyWithCallerInfo(sendToVoicemailCallerInfo);
        }

        assertTrueWithTimeout(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                return mConnectionServiceFixtureA.mConnectionService.rejectedCallIds.size() == 1;
            }
        });
        assertTrueWithTimeout(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                return mMissedCallNotifier.missedCallsNotified.size() == 1;
            }
        });

        verify(mInCallServiceFixtureX.getTestDouble(), never())
                .setInCallAdapter(any(IInCallAdapter.class));
        verify(mInCallServiceFixtureY.getTestDouble(), never())
                .setInCallAdapter(any(IInCallAdapter.class));
    }

    @LargeTest
    @Test
    public void testIncomingCallCallerInfoLookupTimesOutIsAllowed() throws Exception {
        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_CREATE_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_CREATE_ELAPSED_TIME);
        Bundle extras = new Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, "650-555-1212", null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(mPhoneAccountA0.getAccountHandle(), extras, CALLING_PACKAGE);

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .createConnection(any(PhoneAccountHandle.class), anyString(),
                        any(ConnectionRequest.class), eq(true), eq(false), any());

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        // Never reply to the caller info lookup.
        assertEquals(1, mCallerInfoAsyncQueryFactoryFixture.mRequests.size());

        verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                .setInCallAdapter(any(IInCallAdapter.class));
        verify(mInCallServiceFixtureY.getTestDouble(), timeout(TEST_TIMEOUT))
                .setInCallAdapter(any(IInCallAdapter.class));

        assertEquals(0, mConnectionServiceFixtureA.mConnectionService.rejectedCallIds.size());
        assertEquals(0, mMissedCallNotifier.missedCallsNotified.size());

        assertTrueWithTimeout(new Predicate<Void>() {
            @Override
            public boolean apply(Void v) {
                return mInCallServiceFixtureX.mInCallAdapter != null;
            }
        });

        verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                .addCall(any(ParcelableCall.class));
        verify(mInCallServiceFixtureY.getTestDouble(), timeout(TEST_TIMEOUT))
                .addCall(any(ParcelableCall.class));

        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_CONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_CONNECT_ELAPSED_TIME);
        disconnectCall(mInCallServiceFixtureX.mLatestCallId,
                mConnectionServiceFixtureA.mLatestConnectionId);
    }

    @LargeTest
    @Test
    @FlakyTest
    @Ignore("b/189904580")
    public void testIncomingCallFromBlockedNumberIsRejected() throws Exception {
        String phoneNumber = "650-555-1212";
        blockNumber(phoneNumber);

        Bundle extras = new Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, phoneNumber, null));
        mTelecomSystem.getTelecomServiceImpl().getBinder()
                .addNewIncomingCall(mPhoneAccountA0.getAccountHandle(), extras, CALLING_PACKAGE);

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .createConnection(any(PhoneAccountHandle.class), anyString(),
                        any(ConnectionRequest.class), eq(true), eq(false), any());

        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);

        assertEquals(1, mCallerInfoAsyncQueryFactoryFixture.mRequests.size());
        for (CallerInfoAsyncQueryFactoryFixture.Request request :
                mCallerInfoAsyncQueryFactoryFixture.mRequests) {
            request.reply();
        }

        assertTrueWithTimeout(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                return mConnectionServiceFixtureA.mConnectionService.rejectedCallIds.size() == 1;
            }
        });
        assertEquals(0, mMissedCallNotifier.missedCallsNotified.size());

        verify(mInCallServiceFixtureX.getTestDouble(), never())
                .setInCallAdapter(any(IInCallAdapter.class));
        verify(mInCallServiceFixtureY.getTestDouble(), never())
                .setInCallAdapter(any(IInCallAdapter.class));
    }

    @LargeTest
    @Test
    public void testIncomingCallBlockCheckTimesoutIsAllowed() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        String phoneNumber = "650-555-1212";
        blockNumberWithAnswer(phoneNumber, new Answer<Bundle>() {
            @Override
            public Bundle answer(InvocationOnMock invocation) throws Throwable {
                latch.await(TEST_TIMEOUT * 2, TimeUnit.MILLISECONDS);
                Bundle bundle = new Bundle();
                bundle.putBoolean(BlockedNumberContract.RES_NUMBER_IS_BLOCKED, true);
                return bundle;
            }
        });

        IdPair ids = startAndMakeActiveIncomingCall(
                phoneNumber, mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        latch.countDown();

        assertEquals(0, mConnectionServiceFixtureA.mConnectionService.rejectedCallIds.size());
        assertEquals(0, mMissedCallNotifier.missedCallsNotified.size());
        disconnectCall(ids.mCallId, ids.mConnectionId);
    }

    public void do_testDeadlockOnOutgoingCall() throws Exception {
        final IdPair ids = startOutgoingPhoneCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA,
                Process.myUserHandle());
        rapidFire(
                new Runnable() {
                    @Override
                    public void run() {
                        while (mCallerInfoAsyncQueryFactoryFixture.mRequests.size() > 0) {
                            mCallerInfoAsyncQueryFactoryFixture.mRequests.remove(0).reply();
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mConnectionServiceFixtureA.sendSetActive(ids.mConnectionId);
                        } catch (Exception e) {
                            Log.e(this, e, "");
                        }
                    }
                });
    }

    @LargeTest
    @Test
    public void testIncomingThenOutgoingCalls() throws Exception {
        // TODO: We have to use the same PhoneAccount for both; see http://b/18461539
        IdPair incoming = startAndMakeActiveIncomingCall("650-555-2323",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(incoming.mCallId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(outgoing.mCallId);
    }

    @LargeTest
    @Test
    public void testOutgoingThenIncomingCalls() throws Exception {
        // TODO: We have to use the same PhoneAccount for both; see http://b/18461539
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        IdPair incoming = startAndMakeActiveIncomingCall("650-555-2323",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble())
                .hold(eq(outgoing.mConnectionId), any());
        mConnectionServiceFixtureA.mConnectionById.get(outgoing.mConnectionId).state =
                Connection.STATE_HOLDING;
        mConnectionServiceFixtureA.sendSetOnHold(outgoing.mConnectionId);
        assertEquals(Call.STATE_HOLDING,
                mInCallServiceFixtureX.getCall(outgoing.mCallId).getState());
        assertEquals(Call.STATE_HOLDING,
                mInCallServiceFixtureY.getCall(outgoing.mCallId).getState());

        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(incoming.mCallId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(outgoing.mCallId);
    }

    @LargeTest
    @Test
    public void testIncomingThenOutgoingCalls_AssociatedUsersNotEqual() throws Exception {
        when(mFeatureFlags.associatedUserRefactorForWorkProfile()).thenReturn(true);
        InCallServiceFixture.setIgnoreOverrideAdapterFlag(true);

        // Receive incoming call via mPhoneAccountMultiUser
        IdPair incoming = startAndMakeActiveIncomingCall("650-555-2323",
                mPhoneAccountMultiUser.getAccountHandle(), mConnectionServiceFixtureA);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        // Make outgoing call on mPhoneAccountMultiUser (unassociated sim to simulate guest/
        // secondary user scenario where both MO/MT calls exist).
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountMultiUser.getAccountHandle(), mConnectionServiceFixtureA);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);

        // Outgoing call should be on hold while incoming call is made active
        mConnectionServiceFixtureA.mConnectionById.get(incoming.mConnectionId).state =
                Connection.STATE_HOLDING;

        // Swap calls and verify that outgoing call is now the active call while the incoming call
        // is the held call.
        mConnectionServiceFixtureA.sendSetOnHold(outgoing.mConnectionId);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);
        assertEquals(Call.STATE_HOLDING,
                mInCallServiceFixtureX.getCall(outgoing.mCallId).getState());
        assertEquals(Call.STATE_ACTIVE,
                mInCallServiceFixtureX.getCall(incoming.mCallId).getState());

        // Ensure no issues with call disconnect.
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(incoming.mCallId);
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(outgoing.mCallId);
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureX.getCall(incoming.mCallId).getState());
        assertEquals(Call.STATE_DISCONNECTING,
                mInCallServiceFixtureX.getCall(outgoing.mCallId).getState());
        InCallServiceFixture.setIgnoreOverrideAdapterFlag(false);
    }

    @LargeTest
    @Test
    public void testAudioManagerOperations() throws Exception {
        AudioManager audioManager = (AudioManager) mComponentContextFixture.getTestDouble()
                .getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        verify(audioManager, timeout(TEST_TIMEOUT)).requestAudioFocusForCall(anyInt(), anyInt());
        verify(audioManager, timeout(TEST_TIMEOUT).atLeastOnce())
                .setMode(AudioManager.MODE_IN_CALL);

        mInCallServiceFixtureX.mInCallAdapter.mute(true);
        verify(mAudioService, timeout(TEST_TIMEOUT))
                .setMicrophoneMute(eq(true), any(String.class), any(Integer.class),
                        nullable(String.class));
        mInCallServiceFixtureX.mInCallAdapter.mute(false);
        verify(mAudioService, timeout(TEST_TIMEOUT))
                .setMicrophoneMute(eq(false), any(String.class), any(Integer.class),
                        nullable(String.class));

        mInCallServiceFixtureX.mInCallAdapter.setAudioRoute(CallAudioState.ROUTE_SPEAKER, null);
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        ArgumentCaptor<AudioDeviceInfo> infoArgumentCaptor =
                ArgumentCaptor.forClass(AudioDeviceInfo.class);
        verify(audioManager, timeout(TEST_TIMEOUT).atLeast(1))
                .setCommunicationDevice(infoArgumentCaptor.capture());
        var deviceType = infoArgumentCaptor.getValue().getType();
        if (deviceType != AudioDeviceInfo.TYPE_BUS) { // on automotive, we expect BUS
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, deviceType);
        }
        mInCallServiceFixtureX.mInCallAdapter.setAudioRoute(CallAudioState.ROUTE_EARPIECE, null);
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        // setSpeakerPhoneOn(false) gets called once during the call initiation phase
        verify(audioManager, timeout(TEST_TIMEOUT).atLeast(1))
                .clearCommunicationDevice();

        mConnectionServiceFixtureA.
                sendSetDisconnected(outgoing.mConnectionId, DisconnectCause.REMOTE);

        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioModeStateMachine().getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        verify(audioManager, timeout(TEST_TIMEOUT))
                .abandonAudioFocusForCall();
        verify(audioManager, timeout(TEST_TIMEOUT).atLeastOnce())
                .setMode(AudioManager.MODE_NORMAL);
    }

    private void rapidFire(Runnable... tasks) {
        final CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        final CountDownLatch latch = new CountDownLatch(tasks.length);
        for (int i = 0; i < tasks.length; i++) {
            final Runnable task = tasks[i];
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        task.run();
                    } catch (InterruptedException | BrokenBarrierException e){
                        Log.e(BasicCallTests.this, e, "Unexpectedly interrupted");
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(BasicCallTests.this, e, "Unexpectedly interrupted");
        }
    }

    @MediumTest
    @Test
    public void testBasicConferenceCall() throws Exception {
        makeConferenceCall(null, null);
    }

    @MediumTest
    @Test
    public void testAddCallToConference1() throws Exception {
        ParcelableCall conferenceCall = makeConferenceCall(null, null);
        IdPair callId3 = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        // testAddCallToConference{1,2} differ in the order of arguments to InCallAdapter#conference
        mInCallServiceFixtureX.getInCallAdapter().conference(
                conferenceCall.getId(), callId3.mCallId);
        Thread.sleep(200);

        ParcelableCall call3 = mInCallServiceFixtureX.getCall(callId3.mCallId);
        ParcelableCall updatedConference = mInCallServiceFixtureX.getCall(conferenceCall.getId());
        assertEquals(conferenceCall.getId(), call3.getParentCallId());
        assertEquals(3, updatedConference.getChildCallIds().size());
        assertTrue(updatedConference.getChildCallIds().contains(callId3.mCallId));
    }

    @MediumTest
    @Test
    public void testAddCallToConference2() throws Exception {
        ParcelableCall conferenceCall = makeConferenceCall(null, null);
        IdPair callId3 = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        mInCallServiceFixtureX.getInCallAdapter()
                .conference(callId3.mCallId, conferenceCall.getId());
        Thread.sleep(200);

        ParcelableCall call3 = mInCallServiceFixtureX.getCall(callId3.mCallId);
        ParcelableCall updatedConference = mInCallServiceFixtureX.getCall(conferenceCall.getId());
        assertEquals(conferenceCall.getId(), call3.getParentCallId());
        assertEquals(3, updatedConference.getChildCallIds().size());
        assertTrue(updatedConference.getChildCallIds().contains(callId3.mCallId));
    }

    /**
     * Tests the {@link Call#pullExternalCall()} API.  Verifies that if a call is not an external
     * call, no pull call request is made to the connection service.
     *
     * @throws Exception
     */
    @MediumTest
    @Test
    public void testPullNonExternalCall() throws Exception {
        // TODO: Revisit this unit test once telecom support for filtering external calls from
        // InCall services is implemented.
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        // Attempt to pull the call and verify the API call makes it through
        mInCallServiceFixtureX.mInCallAdapter.pullExternalCall(ids.mCallId);
        Thread.sleep(TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble(), never())
                .pullExternalCall(eq(ids.mCallId), any());
    }

    /**
     * Tests the {@link Connection#sendConnectionEvent(String, Bundle)} API.
     *
     * @throws Exception
     */
    @MediumTest
    @Test
    public void testSendConnectionEventNull() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        mConnectionServiceFixtureA.sendConnectionEvent(ids.mConnectionId, TEST_EVENT, null);
        verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                .onConnectionEvent(ids.mCallId, TEST_EVENT, null);
    }

    /**
     * Tests the {@link Connection#sendConnectionEvent(String, Bundle)} API.
     *
     * @throws Exception
     */
    @MediumTest
    @Test
    public void testSendConnectionEventNotNull() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        Bundle testBundle = new Bundle();
        testBundle.putString(TEST_BUNDLE_KEY, "TEST");

        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        mConnectionServiceFixtureA.sendConnectionEvent(ids.mConnectionId, TEST_EVENT, testBundle);
        verify(mInCallServiceFixtureX.getTestDouble(), timeout(TEST_TIMEOUT))
                .onConnectionEvent(eq(ids.mCallId), eq(TEST_EVENT), bundleArgumentCaptor.capture());
        assert (bundleArgumentCaptor.getValue().containsKey(TEST_BUNDLE_KEY));
    }

    /**
     * Tests the {@link Call#sendCallEvent(String, Bundle)} API.
     *
     * @throws Exception
     */
    @MediumTest
    @Test
    public void testSendCallEventNull() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        mInCallServiceFixtureX.mInCallAdapter.sendCallEvent(ids.mCallId, TEST_EVENT, 26, null);
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .sendCallEvent(eq(ids.mConnectionId), eq(TEST_EVENT), isNull(), any());
    }

    /**
     * Tests the {@link Call#sendCallEvent(String, Bundle)} API.
     *
     * @throws Exception
     */
    @MediumTest
    @Test
    public void testSendCallEventNonNull() throws Exception {
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        Bundle testBundle = new Bundle();
        testBundle.putString(TEST_BUNDLE_KEY, "TEST");

        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        mInCallServiceFixtureX.mInCallAdapter.sendCallEvent(ids.mCallId, TEST_EVENT, 26,
                testBundle);
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .sendCallEvent(eq(ids.mConnectionId), eq(TEST_EVENT),
                        bundleArgumentCaptor.capture(), any());
        assert (bundleArgumentCaptor.getValue().containsKey(TEST_BUNDLE_KEY));
    }

    private void blockNumber(String phoneNumber) throws Exception {
        blockNumberWithAnswer(phoneNumber, new Answer<Bundle>() {
            @Override
            public Bundle answer(InvocationOnMock invocation) throws Throwable {
                Bundle bundle = new Bundle();
                bundle.putInt(RES_BLOCK_STATUS, STATUS_BLOCKED_IN_LIST);
                return bundle;
            }
        });
    }

    private void blockNumberWithAnswer(String phoneNumber, Answer answer) throws Exception {
        when(getBlockedNumberProvider().call(
                any(),
                anyString(),
                eq(BlockedNumberContract.SystemContract.METHOD_SHOULD_SYSTEM_BLOCK_NUMBER),
                eq(phoneNumber),
                nullable(Bundle.class))).thenAnswer(answer);
    }

    private void verifyNoBlockChecks() {
        verifyNoMoreInteractions(getBlockedNumberProvider());
    }

    private IContentProvider getBlockedNumberProvider() {
        return mSpyContext.getContentResolver().acquireProvider(BlockedNumberContract.AUTHORITY);
    }

    private void disconnectCall(String callId, String connectionId) throws Exception {
        when(mClockProxy.currentTimeMillis()).thenReturn(TEST_DISCONNECT_TIME);
        when(mClockProxy.elapsedRealtime()).thenReturn(TEST_DISCONNECT_ELAPSED_TIME);
        mConnectionServiceFixtureA.sendSetDisconnected(connectionId, DisconnectCause.LOCAL);
        assertEquals(Call.STATE_DISCONNECTED, mInCallServiceFixtureX.getCall(callId).getState());
        assertEquals(Call.STATE_DISCONNECTED, mInCallServiceFixtureY.getCall(callId).getState());
        assertEquals(TEST_CREATE_TIME,
                mInCallServiceFixtureX.getCall(callId).getCreationTimeMillis());
        assertEquals(TEST_CREATE_TIME,
                mInCallServiceFixtureY.getCall(callId).getCreationTimeMillis());
    }

    /**
     * Tests to make sure that the Call.Details.PROPERTY_HAS_CDMA_VOICE_PRIVACY property is set on a
     * Call that is based on a Connection with the Connection.PROPERTY_HAS_CDMA_VOICE_PRIVACY
     * property set.
     */
    @MediumTest
    @Test
    public void testCdmaEnhancedPrivacyVoiceCall() throws Exception {
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mProperties =
                Connection.PROPERTY_HAS_CDMA_VOICE_PRIVACY;

        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        assertTrue(Call.Details.hasProperty(
                mInCallServiceFixtureX.getCall(ids.mCallId).getProperties(),
                Call.Details.PROPERTY_HAS_CDMA_VOICE_PRIVACY));
    }

    /**
     * Tests to make sure that Call.Details.PROPERTY_HAS_CDMA_VOICE_PRIVACY is dropped
     * when the Connection.PROPERTY_HAS_CDMA_VOICE_PRIVACY property is removed from the Connection.
     */
    @MediumTest
    @Test
    public void testDropCdmaEnhancedPrivacyVoiceCall() throws Exception {
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mProperties =
                Connection.PROPERTY_HAS_CDMA_VOICE_PRIVACY;

        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        mConnectionServiceFixtureA.mLatestConnection.setConnectionProperties(0);

        assertFalse(Call.Details.hasProperty(
                mInCallServiceFixtureX.getCall(ids.mCallId).getProperties(),
                Call.Details.PROPERTY_HAS_CDMA_VOICE_PRIVACY));
    }

    /**
     * Tests the {@link Call#pullExternalCall()} API.  Ensures that an external call which is
     * pullable can be pulled.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testPullExternalCall() throws Exception {
        // TODO: Revisit this unit test once telecom support for filtering external calls from
        // InCall services is implemented.
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mCapabilities =
                Connection.CAPABILITY_CAN_PULL_CALL;
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mProperties =
                Connection.PROPERTY_IS_EXTERNAL_CALL;

        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        // Attempt to pull the call and verify the API call makes it through
        mInCallServiceFixtureX.mInCallAdapter.pullExternalCall(ids.mCallId);
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .pullExternalCall(eq(ids.mConnectionId), any());
    }

    /**
     * Tests the {@link Call#pullExternalCall()} API.  Verifies that if an external call is not
     * marked as pullable that the connection service does not get an API call to pull the external
     * call.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testPullNonPullableExternalCall() throws Exception {
        // TODO: Revisit this unit test once telecom support for filtering external calls from
        // InCall services is implemented.
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mProperties =
                Connection.PROPERTY_IS_EXTERNAL_CALL;

        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        // Attempt to pull the call and verify the API call makes it through
        mInCallServiceFixtureX.mInCallAdapter.pullExternalCall(ids.mCallId);
        Thread.sleep(TEST_TIMEOUT);
        verify(mConnectionServiceFixtureA.getTestDouble(), never())
                .pullExternalCall(eq(ids.mConnectionId), any());
    }

    /**
     * Test scenario where the user starts an outgoing video call with no selected PhoneAccount, and
     * then subsequently selects a PhoneAccount which supports video calling.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testOutgoingCallSelectPhoneAccountVideo() throws Exception {
        startOutgoingPhoneCallPendingCreateConnection("650-555-1212",
                null, mConnectionServiceFixtureA,
                Process.myUserHandle(), VideoProfile.STATE_BIDIRECTIONAL, null);
        com.android.server.telecom.Call call = mTelecomSystem.getCallsManager().getCalls()
                .iterator().next();
        assert(call.isVideoCallingSupportedByPhoneAccount());
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());

        // Change the phone account to one which supports video calling.
        call.setTargetPhoneAccount(mPhoneAccountA1.getAccountHandle());
        assert(call.isVideoCallingSupportedByPhoneAccount());
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        call.setIsCreateConnectionComplete(true);
    }

    /**
     * Test scenario where the user starts an outgoing video call with no selected PhoneAccount, and
     * then subsequently selects a PhoneAccount which does not support video calling.
     * @throws Exception
     */
    @FlakyTest
    @LargeTest
    @Test
    public void testOutgoingCallSelectPhoneAccountNoVideo() throws Exception {
        startOutgoingPhoneCallPendingCreateConnection("650-555-1212",
                null, mConnectionServiceFixtureA,
                Process.myUserHandle(), VideoProfile.STATE_BIDIRECTIONAL, null);
        com.android.server.telecom.Call call = mTelecomSystem.getCallsManager().getCalls()
                .iterator().next();
        assert(call.isVideoCallingSupportedByPhoneAccount());
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());

        // Change the phone account to one which does not support video calling.
        call.setTargetPhoneAccount(mPhoneAccountA2.getAccountHandle());
        assert(!call.isVideoCallingSupportedByPhoneAccount());
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());
        call.setIsCreateConnectionComplete(true);
    }

    /**
     * Basic test to ensure that a self-managed ConnectionService can place a call.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testSelfManagedOutgoing() throws Exception {
        PhoneAccountHandle phoneAccountHandle = mPhoneAccountSelfManaged.getAccountHandle();
        IdPair ids = startAndMakeActiveOutgoingCall("650-555-1212", phoneAccountHandle,
                mConnectionServiceFixtureA);

        // The InCallService should not know about the call since its self-managed.
        assertNull(mInCallServiceFixtureX.getCall(ids.mCallId));
    }

    /**
     * Basic test to ensure that a self-managed ConnectionService can add an incoming call.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testSelfManagedIncoming() throws Exception {
        PhoneAccountHandle phoneAccountHandle = mPhoneAccountSelfManaged.getAccountHandle();
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212", phoneAccountHandle,
                mConnectionServiceFixtureA);

        // The InCallService should not know about the call since its self-managed.
        assertNull(mInCallServiceFixtureX.getCall(ids.mCallId));
    }

    /**
     * Basic test to ensure that when there are no calls, we permit outgoing calls by a self managed
     * CS.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testIsOutgoingCallPermitted() throws Exception {
        assertTrue(mTelecomSystem.getTelecomServiceImpl().getBinder()
                .isOutgoingCallPermitted(mPhoneAccountSelfManaged.getAccountHandle(),
                        mPhoneAccountSelfManaged.getAccountHandle().getComponentName()
                                .getPackageName()));
    }

    /**
     * Ensure if there is a holdable call ongoing we'll be able to place another call.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testIsOutgoingCallPermittedOngoingHoldable() throws Exception {
        // Start a regular call; the self-managed CS can make a call now since ongoing call can be
        // held
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        assertTrue(mTelecomSystem.getTelecomServiceImpl().getBinder()
                .isOutgoingCallPermitted(mPhoneAccountSelfManaged.getAccountHandle(),
                        mPhoneAccountSelfManaged.getAccountHandle().getComponentName()
                                .getPackageName()));
    }

    /**
     * Ensure if there is an unholdable call we can't place another call.
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testIsOutgoingCallPermittedOngoingUnHoldable() throws Exception {
        // Start a regular call; the self-managed CS can't make a call now because the ongoing call
        // can't be held.
        mConnectionServiceFixtureA.mConnectionServiceDelegate.mCapabilities = 0;
        IdPair ids = startAndMakeActiveIncomingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(ids.mCallId).getState());

        assertTrue(mTelecomSystem.getTelecomServiceImpl().getBinder()
                .isOutgoingCallPermitted(mPhoneAccountSelfManaged.getAccountHandle(),
                        mPhoneAccountSelfManaged.getAccountHandle().getComponentName()
                                .getPackageName()));
    }

    /**
     * Basic to verify audio route gets reset to baseline when emergency call placed while a
     * self-managed call is underway.
     * @throws Exception
     */
    @LargeTest
    @Test
    @FlakyTest
    public void testDisconnectSelfManaged() throws Exception {
        // Add a self-managed call.
        PhoneAccountHandle phoneAccountHandle = mPhoneAccountSelfManaged.getAccountHandle();
        startAndMakeActiveIncomingCall("650-555-1212", phoneAccountHandle,
                mConnectionServiceFixtureA);
        Connection connection = mConnectionServiceFixtureA.mLatestConnection;

        // Route self-managed call to speaker.
        connection.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);

        // Place an emergency call.
        startAndMakeDialingEmergencyCall("650-555-1212", mPhoneAccountE0.getAccountHandle(),
                mConnectionServiceFixtureA);

        // Should have reverted back to earpiece.
        assertTrueWithTimeout(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                return mInCallServiceFixtureX.mCallAudioState.getRoute()
                        == CallAudioState.ROUTE_EARPIECE;
            }
        });
    }

    /**
     * Tests the {@link Call#deflect} API.  Verifies that if a call is incoming,
     * and deflect API is called, then request is made to the connection service.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testDeflectCallWhenIncoming() throws Exception {
        Uri deflectAddress = Uri.parse("tel:650-555-1214");
        IdPair ids = startIncomingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureX.getCall(ids.mCallId).getState());
        assertEquals(Call.STATE_RINGING, mInCallServiceFixtureY.getCall(ids.mCallId).getState());
        // Attempt to deflect the call and verify the API call makes it through
        mInCallServiceFixtureX.mInCallAdapter.deflectCall(ids.mCallId, deflectAddress);
        verify(mConnectionServiceFixtureA.getTestDouble(), timeout(TEST_TIMEOUT))
                .deflect(eq(ids.mConnectionId), eq(deflectAddress), any());
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Tests the {@link Call#deflect} API.  Verifies that if a call is outgoing,
     * and deflect API is called, then request is not made to the connection service.
     * Ideally, deflect option should be displayed only if call is incoming/waiting.
     *
     * @throws Exception
     */
    @LargeTest
    @Test
    public void testDeflectCallWhenOutgoing() throws Exception {
        Uri deflectAddress = Uri.parse("tel:650-555-1214");
        IdPair ids = startOutgoingPhoneCall("650-555-1212", mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA, Process.myUserHandle());
        // Attempt to deflect the call and verify the API call does not make it through
        mInCallServiceFixtureX.mInCallAdapter.deflectCall(ids.mCallId, deflectAddress);
        verify(mConnectionServiceFixtureA.getTestDouble(), never())
                .deflect(eq(ids.mConnectionId), eq(deflectAddress), any());
        mInCallServiceFixtureX.mInCallAdapter.disconnectCall(ids.mCallId);
    }

    /**
     * Test to make sure to unmute automatically when making an emergency call and keep unmute
     * during the emergency call.
     * @throws Exception
     */
    @LargeTest
    @Test
    @FlakyTest
    public void testUnmuteDuringEmergencyCall() throws Exception {
        // Make an outgoing call and turn ON mute.
        IdPair outgoingCall = startAndMakeActiveOutgoingCall("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_ACTIVE, mInCallServiceFixtureX.getCall(outgoingCall.mCallId)
                .getState());
        mInCallServiceFixtureX.mInCallAdapter.mute(true);
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        assertTrue(mTelecomSystem.getCallsManager().getAudioState().isMuted());

        // Make an emergency call.
        IdPair emergencyCall = startAndMakeDialingEmergencyCall("650-555-1213",
                mPhoneAccountE0.getAccountHandle(), mConnectionServiceFixtureA);
        assertEquals(Call.STATE_DIALING, mInCallServiceFixtureX.getCall(emergencyCall.mCallId)
                .getState());
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        // Should be unmute automatically.
        assertFalse(mTelecomSystem.getCallsManager().getAudioState().isMuted());

        // Toggle mute during an emergency call.
        mTelecomSystem.getCallsManager().getCallAudioManager().toggleMute();
        waitForHandlerAction(mTelecomSystem.getCallsManager().getCallAudioManager()
                .getCallAudioRouteAdapter().getAdapterHandler(), TEST_TIMEOUT);
        // Should keep unmute.
        assertFalse(mTelecomSystem.getCallsManager().getAudioState().isMuted());

        ArgumentCaptor<Boolean> muteValueCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(mAudioService, times(2)).setMicrophoneMute(muteValueCaptor.capture(),
                any(String.class), any(Integer.class), nullable(String.class));
        List<Boolean> muteValues = muteValueCaptor.getAllValues();
        // Check mute status was changed twice with true and false.
        assertTrue(muteValues.get(0));
        assertFalse(muteValues.get(1));
    }

    /**
     * Verifies that StatusHints image is validated in ConnectionServiceWrapper#addConferenceCall
     * when the image doesn't belong to the calling user. Simulates a scenario where an app
     * could manipulate the contents of the bundle and send it via the binder to upload an image
     * from another user.
     *
     * @throws Exception
     */
    @SmallTest
    @Test
    public void testValidateStatusHintsImage_addConferenceCall() throws Exception {
        Intent callIntent1 = new Intent();
        // Stub intent for call2
        Intent callIntent2 = new Intent();
        Bundle callExtras1 = new Bundle();
        Icon icon = Icon.createWithContentUri("content://12@media/external/images/media/");
        // Load StatusHints extra into TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS to be processed
        // as the call extras. This will be leveraged in ConnectionServiceFixture to set the
        // StatusHints for the given connection.
        StatusHints statusHints = new StatusHints(icon);
        assertNotNull(statusHints.getIcon());
        callExtras1.putParcelable(STATUS_HINTS_EXTRA, statusHints);
        callIntent1.putExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras1);

        // Start conference call to invoke ConnectionServiceWrapper#addConferenceCall.
        // Note that the calling user would be User 0.
        ParcelableCall conferenceCall = makeConferenceCall(callIntent1, callIntent2);

        // Ensure that StatusHints was set.
        assertNotNull(mInCallServiceFixtureX.getCall(mInCallServiceFixtureX.mLatestCallId)
                .getStatusHints());
        // Ensure that the StatusHints image icon was disregarded.
        assertNull(mInCallServiceFixtureX.getCall(mInCallServiceFixtureX.mLatestCallId)
                .getStatusHints().getIcon());
    }

    /**
     * Verifies that StatusHints image is validated in
     * ConnectionServiceWrapper#handleCreateConnectionComplete when the image doesn't belong to the
     * calling user. Simulates a scenario where an app could manipulate the contents of the
     * bundle and send it via the binder to upload an image from another user.
     *
     * @throws Exception
     */
    @SmallTest
    @Test
    public void testValidateStatusHintsImage_handleCreateConnectionComplete() throws Exception {
        Bundle extras = new Bundle();
        Icon icon = Icon.createWithContentUri("content://12@media/external/images/media/");
        // Load the bundle with the test extra in order to simulate an app directly invoking the
        // binder on ConnectionServiceWrapper#handleCreateConnectionComplete.
        StatusHints statusHints = new StatusHints(icon);
        assertNotNull(statusHints.getIcon());
        extras.putParcelable(STATUS_HINTS_EXTRA, statusHints);

        // Start incoming call with StatusHints extras
        // Note that the calling user in ConnectionServiceWrapper#handleCreateConnectionComplete
        // would be User 0.
        IdPair ids = startIncomingPhoneCallWithExtras("650-555-1212",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA, extras);

        // Ensure that StatusHints was set.
        assertNotNull(mInCallServiceFixtureX.getCall(ids.mCallId).getStatusHints());
        // Ensure that the StatusHints image icon was disregarded.
        assertNull(mInCallServiceFixtureX.getCall(ids.mCallId).getStatusHints().getIcon());
    }

    /**
     * Verifies that StatusHints image is validated in ConnectionServiceWrapper#setStatusHints
     * when the image doesn't belong to the calling user. Simulates a scenario where an app
     * could manipulate the contents of the bundle and send it via the binder to upload an image
     * from another user.
     *
     * @throws Exception
     */
    @SmallTest
    @Test
    public void testValidateStatusHintsImage_setStatusHints() throws Exception {
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        // Modify existing connection with StatusHints image exploit
        Icon icon = Icon.createWithContentUri("content://12@media/external/images/media/");
        StatusHints statusHints = new StatusHints(icon);
        assertNotNull(statusHints.getIcon());
        ConnectionServiceFixture.ConnectionInfo connectionInfo = mConnectionServiceFixtureA
                .mConnectionById.get(outgoing.mConnectionId);
        connectionInfo.statusHints = statusHints;

        // Invoke ConnectionServiceWrapper#setStatusHints.
        // Note that the calling user would be User 0.
        mConnectionServiceFixtureA.sendSetStatusHints(outgoing.mConnectionId);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);

        // Ensure that StatusHints was set.
        assertNotNull(mInCallServiceFixtureX.getCall(outgoing.mCallId).getStatusHints());
        // Ensure that the StatusHints image icon was disregarded.
        assertNull(mInCallServiceFixtureX.getCall(outgoing.mCallId)
                .getStatusHints().getIcon());
    }

    /**
     * Verifies that StatusHints image is validated in
     * ConnectionServiceWrapper#addExistingConnection when the image doesn't belong to the calling
     * user. Simulates a scenario where an app could manipulate the contents of the bundle and
     * send it via the binder to upload an image from another user.
     *
     * @throws Exception
     */
    @SmallTest
    @Test
    public void testValidateStatusHintsImage_addExistingConnection() throws Exception {
        IdPair outgoing = startAndMakeActiveOutgoingCall("650-555-1214",
                mPhoneAccountA0.getAccountHandle(), mConnectionServiceFixtureA);

        // Modify existing connection with StatusHints image exploit
        Icon icon = Icon.createWithContentUri("content://12@media/external/images/media/");
        StatusHints modifiedStatusHints = new StatusHints(icon);
        assertNotNull(modifiedStatusHints.getIcon());
        ConnectionServiceFixture.ConnectionInfo connectionInfo = mConnectionServiceFixtureA
                .mConnectionById.get(outgoing.mConnectionId);
        connectionInfo.statusHints = modifiedStatusHints;

        // Invoke ConnectionServiceWrapper#addExistingConnection.
        // Note that the calling user would be User 0.
        mConnectionServiceFixtureA.sendAddExistingConnection(outgoing.mConnectionId);
        waitForHandlerAction(mConnectionServiceFixtureA.mConnectionServiceDelegate.getHandler(),
                TEST_TIMEOUT);

        // Ensure that StatusHints was set. Due to test setup, the ParcelableConnection object that
        // is passed into sendAddExistingConnection is instantiated on invocation. The call's
        // StatusHints are not updated at the time of completion, so instead, we can verify that
        // the ParcelableConnection object was modified.
        assertNotNull(mConnectionServiceFixtureA.mLatestParcelableConnection.getStatusHints());
        // Ensure that the StatusHints image icon was disregarded.
        assertNull(mConnectionServiceFixtureA.mLatestParcelableConnection
                .getStatusHints().getIcon());
    }
}
