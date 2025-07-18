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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionManager;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.flags.Flags;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIdMapper;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ConnectionServiceFocusManager;
import com.android.server.telecom.ConnectionServiceRepository;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.CreateConnectionProcessor;
import com.android.server.telecom.CreateConnectionResponse;
import com.android.server.telecom.CreateConnectionTimeout;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.Timeouts;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Unit testing for CreateConnectionProcessor as well as CreateConnectionTimeout classes.
 */
@RunWith(JUnit4.class)
public class CreateConnectionProcessorTest extends TelecomTestCase {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String TEST_PACKAGE = "com.android.server.telecom.tests";
    private static final String TEST_CLASS =
            "com.android.server.telecom.tests.MockConnectionService";
    private static final String CONNECTION_MANAGER_TEST_CLASS =
            "com.android.server.telecom.tests.ConnectionManagerConnectionService";
    private static final UserHandle USER_HANDLE_10 = new UserHandle(10);

    @Mock
    ConnectionServiceRepository mMockConnectionServiceRepository;
    @Mock
    PhoneAccountRegistrar mMockAccountRegistrar;
    @Mock
    CallsManager mCallsManager;
    @Mock
    CreateConnectionResponse mMockCreateConnectionResponse;
    @Mock
    Call mMockCall;
    @Mock
    ConnectionServiceFocusManager mConnectionServiceFocusManager;
    @Mock Timeouts.Adapter mTimeoutsAdapter;

    CreateConnectionProcessor mTestCreateConnectionProcessor;
    CreateConnectionTimeout mTestCreateConnectionTimeout;

    private ArrayList<PhoneAccount> phoneAccounts;
    private HashMap<Integer, Integer> mSubToSlot;
    private HashMap<PhoneAccount, Integer> mAccountToSub;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();

        when(mMockCall.getConnectionServiceFocusManager()).thenReturn(
                mConnectionServiceFocusManager);
        doAnswer(new Answer<Void>() {
                     @Override
                     public Void answer(InvocationOnMock invocation) {
                         Object[] args = invocation.getArguments();
                         ConnectionServiceFocusManager.CallFocus focus =
                                 (ConnectionServiceFocusManager.CallFocus) args[0];
                         ConnectionServiceFocusManager.RequestFocusCallback callback =
                                 (ConnectionServiceFocusManager.RequestFocusCallback) args[1];
                         callback.onRequestFocusDone(focus);
                         return null;
                     }
                 }
        ).when(mConnectionServiceFocusManager).requestFocus(any(), any());

        mTestCreateConnectionProcessor = new CreateConnectionProcessor(mMockCall,
                mMockConnectionServiceRepository, mMockCreateConnectionResponse,
                mMockAccountRegistrar, mCallsManager, mContext, mFeatureFlags, mTimeoutsAdapter);

        mAccountToSub = new HashMap<>();
        phoneAccounts = new ArrayList<>();
        mSubToSlot = new HashMap<>();
        mTestCreateConnectionProcessor.setTelephonyManagerAdapter(
                new CreateConnectionProcessor.ITelephonyManagerAdapter() {
                    @Override
                    public int getSubIdForPhoneAccount(Context context, PhoneAccount account) {
                        return mAccountToSub.getOrDefault(account,
                                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                    }

                    @Override
                    public int getSlotIndex(int subId) {
                        return mSubToSlot.getOrDefault(subId,
                                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                    }
                });
        when(mMockAccountRegistrar.getAllPhoneAccounts(any(UserHandle.class), anyBoolean()))
                .thenReturn(phoneAccounts);
        when(mMockCall.getAssociatedUser()).
                thenReturn(Binder.getCallingUserHandle());

        mTestCreateConnectionTimeout = new CreateConnectionTimeout(mContext, mMockAccountRegistrar,
                makeConnectionServiceWrapper(), mMockCall, mTimeoutsAdapter);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mTestCreateConnectionProcessor = null;
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testSimPhoneAccountSuccess() throws Exception {
        PhoneAccountHandle pAHandle = getNewTargetPhoneAccountHandle("tel_acct");
        setTargetPhoneAccount(mMockCall, pAHandle);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        // No Connection Manager in this case
        when(mMockAccountRegistrar.getSimCallManagerFromCall(any(Call.class))).thenReturn(null);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(pAHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(pAHandle));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    @SmallTest
    @Test
    public void testBadPhoneAccount() throws Exception {
        PhoneAccountHandle pAHandle = null;
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        when(mMockCall.getTargetPhoneAccount()).thenReturn(pAHandle);
        givePhoneAccountBindPermission(pAHandle);
        // No Connection Manager in this case
        when(mMockAccountRegistrar.getSimCallManagerFromCall(any(Call.class))).thenReturn(null);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();

        mTestCreateConnectionProcessor.process();

        verify(service, never()).createConnection(eq(mMockCall),
                any(CreateConnectionResponse.class));
        verify(mMockCreateConnectionResponse).handleCreateConnectionFailure(
                eq(new DisconnectCause(DisconnectCause.ERROR)));
    }

    @SmallTest
    @Test
    public void testConnectionManagerSuccess() throws Exception {
        PhoneAccountHandle pAHandle = getNewTargetPhoneAccountHandle("tel_acct");
        setTargetPhoneAccount(mMockCall, pAHandle);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        // Include a Connection Manager
        PhoneAccountHandle callManagerPAHandle = getNewConnectionManagerHandleForCall(mMockCall,
                "cm_acct");
        ConnectionServiceWrapper service = makeConnMgrConnectionServiceWrapper();
        // Make sure the target phone account has the correct permissions
        PhoneAccount mFakeTargetPhoneAccount = makeQuickAccount("cm_acct",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, null);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(pAHandle)).thenReturn(
                mFakeTargetPhoneAccount);

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(callManagerPAHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(pAHandle));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall),
                any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    @SmallTest
    @Test
    public void testConnectionManagerConnectionServiceSuccess() throws Exception {
        when(mFeatureFlags.updatedRcsCallCountTracking()).thenReturn(true);

        // Configure the target phone account as the remote connection service:
        PhoneAccountHandle pAHandle = getNewTargetPhoneAccountHandle("tel_acct");
        setTargetPhoneAccount(mMockCall, pAHandle);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        ConnectionServiceWrapper remoteService = makeConnectionServiceWrapper();

        // Configure the connection manager phone account as the primary connection service:
        PhoneAccountHandle callManagerPAHandle = getNewConnectionManagerHandleForCall(mMockCall,
                "cm_acct");
        ConnectionServiceWrapper service = makeConnMgrConnectionServiceWrapper();

        // Make sure the target phone account has the correct permissions
        PhoneAccount mFakeTargetPhoneAccount = makeQuickAccount("cm_acct",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, null);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(pAHandle)).thenReturn(
                mFakeTargetPhoneAccount);

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(callManagerPAHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(pAHandle));
        // Ensure the remote connection service and primary connection service are set properly:
        verify(mMockCall).setConnectionService(eq(service), eq(remoteService));
        verify(service).createConnection(eq(mMockCall),
                any(CreateConnectionResponse.class));
        // Notify successful connection to call:
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    @SmallTest
    @Test
    public void testConnectionManagerFailedFallToSim() throws Exception {
        PhoneAccountHandle pAHandle = getNewTargetPhoneAccountHandle("tel_acct");
        setTargetPhoneAccount(mMockCall, pAHandle);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        ConnectionServiceWrapper remoteService = makeConnectionServiceWrapper();

        // Include a Connection Manager
        PhoneAccountHandle callManagerPAHandle = getNewConnectionManagerHandleForCall(mMockCall,
                "cm_acct");
        ConnectionServiceWrapper service = makeConnMgrConnectionServiceWrapper();
        when(mMockCall.getConnectionManagerPhoneAccount()).thenReturn(callManagerPAHandle);
        PhoneAccount mFakeTargetPhoneAccount = makeQuickAccount("cm_acct",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, null);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(pAHandle)).thenReturn(
                mFakeTargetPhoneAccount);
        when(mMockCall.getConnectionService()).thenReturn(service);
        // Put CreateConnectionProcessor in correct state to fail with ConnectionManager
        mTestCreateConnectionProcessor.process();
        reset(mMockCall);
        reset(service);

        when(mMockCall.getConnectionServiceFocusManager()).thenReturn(
                mConnectionServiceFocusManager);
        // Notify that the ConnectionManager has denied the call.
        when(mMockCall.getConnectionManagerPhoneAccount()).thenReturn(callManagerPAHandle);
        when(mMockCall.getConnectionService()).thenReturn(service);
        mTestCreateConnectionProcessor.handleCreateConnectionFailure(
                new DisconnectCause(DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED));

        // Verify that the Sim Phone Account is used correctly
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(pAHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(pAHandle));
        verify(mMockCall).setConnectionService(eq(remoteService));
        verify(remoteService).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    @SmallTest
    @Test
    public void testConnectionManagerFailedDoNotFallToSim() throws Exception {
        PhoneAccountHandle pAHandle = getNewTargetPhoneAccountHandle("tel_acct");
        setTargetPhoneAccount(mMockCall, pAHandle);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        // Include a Connection Manager
        PhoneAccountHandle callManagerPAHandle = getNewConnectionManagerHandleForCall(mMockCall,
                "cm_acct");
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        when(mMockCall.getConnectionManagerPhoneAccount()).thenReturn(callManagerPAHandle);
        PhoneAccount mFakeTargetPhoneAccount = makeQuickAccount("cm_acct",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, null);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(pAHandle)).thenReturn(
                mFakeTargetPhoneAccount);
        when(mMockCall.getConnectionService()).thenReturn(service);
        // Put CreateConnectionProcessor in correct state to fail with ConnectionManager
        mTestCreateConnectionProcessor.process();
        reset(mMockCall);
        reset(service);

        when(mMockCall.getConnectionServiceFocusManager()).thenReturn(
                mConnectionServiceFocusManager);
        // Notify that the ConnectionManager has rejected the call.
        when(mMockCall.getConnectionManagerPhoneAccount()).thenReturn(callManagerPAHandle);
        when(mMockCall.getConnectionService()).thenReturn(service);
        when(service.isServiceValid("createConnection")).thenReturn(true);
        mTestCreateConnectionProcessor.handleCreateConnectionFailure(
                new DisconnectCause(DisconnectCause.OTHER));

        // Verify call connection rejected
        verify(mMockCreateConnectionResponse).handleCreateConnectionFailure(
                new DisconnectCause(DisconnectCause.OTHER));
    }

    /**
     * Ensure that when a test emergency number is being dialed and we restrict the usable
     * PhoneAccounts using {@link PhoneAccountRegistrar#filterRestrictedPhoneAccounts(List)},
     * the
     * test emergency call is sent on the filtered PhoneAccount.
     */
    @SmallTest
    @Test
    public void testFakeEmergencyNumber() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(true);
        // Put in a regular phone account as the target and make sure it calls that.
        PhoneAccount regularAccount = makeEmergencyTestPhoneAccount("tel_acct1", 0);
        PhoneAccountHandle regularAccountHandle = regularAccount.getAccountHandle();
        List<PhoneAccount> filteredList = new ArrayList<>();
        filteredList.add(regularAccount);
        when(mMockAccountRegistrar.filterRestrictedPhoneAccounts(anyList()))
                .thenReturn(filteredList);
        mapToSubSlot(regularAccount, 1 /*subId*/, 0 /*slotId*/);
        setTargetPhoneAccount(mMockCall, regularAccount.getAccountHandle());
        phoneAccounts.add(regularAccount);
        // Do not use this account, even though it is a SIM subscription and can place emergency
        // calls
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount = makeEmergencyPhoneAccount("tel_emer", 0, null);
        mapToSubSlot(emergencyPhoneAccount, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount);

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(regularAccountHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(regularAccountHandle));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * Ensure that when no phone accounts (visible to the user) are available for the call, we
     * use
     * an available sim from other another user (on the condition that the user has the
     * INTERACT_ACROSS_USERS permission).
     */
    @SmallTest
    @Test
    public void testEmergencyCallAcrossUsers() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        // Add an emergency account associated with a different user and expect this to be called.
        PhoneAccount emergencyPhoneAccount = makeEmergencyPhoneAccount("tel_emer",
                0, USER_HANDLE_10);
        mapToSubSlot(emergencyPhoneAccount, 1 /*subId*/, 0 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount);
        PhoneAccountHandle emergencyPhoneAccountHandle = emergencyPhoneAccount.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * Ensure that the non-emergency capable PhoneAccount and the SIM manager is not chosen to
     * place
     * the emergency call if there is an emergency capable PhoneAccount available as well.
     */
    @SmallTest
    @Test
    public void testEmergencyCall() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        // Put in a regular phone account as the target to be sure it doesn't call that
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        mapToSubSlot(regularAccount, 1 /*subId*/, 0 /*slotId*/);
        setTargetPhoneAccount(mMockCall, regularAccount.getAccountHandle());
        phoneAccounts.add(regularAccount);
        // Include a Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount = makeEmergencyPhoneAccount("tel_emer", 0, null);
        mapToSubSlot(emergencyPhoneAccount, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount);
        PhoneAccountHandle emergencyPhoneAccountHandle = emergencyPhoneAccount.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * 1) Ensure that if there is a non-SIM PhoneAccount, it is not chosen as the Phone Account
     * to
     * dial the emergency call.
     * 2) Register multiple emergency capable PhoneAccounts. Since there is not preference, we
     * default to sending on the lowest slot.
     */
    @SmallTest
    @Test
    public void testEmergencyCallMultiSimNoPreferred() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        // Put in a non-SIM phone account as the target to be sure it doesn't call that.
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1", 0);
        setTargetPhoneAccount(mMockCall, regularAccount.getAccountHandle());
        // Include a Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        phoneAccounts.add(emergencyPhoneAccount1);
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/, 1 /*slotId*/);
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2", 0, null);
        phoneAccounts.add(emergencyPhoneAccount2);
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 0 /*slotId*/);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        // We did not set a preference
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * Ensure that the call goes out on the PhoneAccount that has the
     * CAPABILITY_EMERGENCY_PREFERRED
     * capability, even if the user specifically chose the other emergency capable
     * PhoneAccount.
     */
    @SmallTest
    @Test
    public void testEmergencyCallMultiSimTelephonyPreferred() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/, 0 /*slotId*/);
        setTargetPhoneAccount(mMockCall, emergencyPhoneAccount1.getAccountHandle());
        phoneAccounts.add(emergencyPhoneAccount1);
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2",
                PhoneAccount.CAPABILITY_EMERGENCY_PREFERRED, null);
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount2);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        // Make sure the telephony preferred SIM is the one that is chosen.
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * If there is no phone account with CAPABILITY_EMERGENCY_PREFERRED capability, choose the
     * user
     * chosen target account.
     */
    @SmallTest
    @Test
    public void testEmergencyCallMultiSimUserPreferred() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        // Include a Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/, 0 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount1);
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2", 0, null);
        // Make this the user preferred account
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 1 /*slotId*/);
        setTargetPhoneAccount(mMockCall, emergencyPhoneAccount2.getAccountHandle());
        phoneAccounts.add(emergencyPhoneAccount2);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        // Make sure the user preferred SIM is the one that is chosen.
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * Ensure that the call goes out on the PhoneAccount for the incoming call and not the
     * Telephony preferred emergency account.
     */
    @SmallTest
    @Test
    public void testMTEmergencyCallMultiSimUserPreferred() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        when(mMockCall.isIncoming()).thenReturn(true);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/, 0 /*slotId*/);
        setTargetPhoneAccount(mMockCall, emergencyPhoneAccount1.getAccountHandle());
        phoneAccounts.add(emergencyPhoneAccount1);
        // Make this the user preferred phone account
        setTargetPhoneAccount(mMockCall, emergencyPhoneAccount1.getAccountHandle());
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2",
                PhoneAccount.CAPABILITY_EMERGENCY_PREFERRED, null);
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount2);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        verify(mMockCall).setConnectionManagerPhoneAccount(
                eq(emergencyPhoneAccount1.getAccountHandle()));
        // The account we're using to place the call should be the user preferred account
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccount1.getAccountHandle()));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * If the user preferred PhoneAccount is associated with an invalid slot, place on the
     * other,
     * valid slot.
     */
    @SmallTest
    @Test
    public void testEmergencyCallMultiSimUserPreferredInvalidSlot() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        // Include a Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        // make this the user preferred account
        setTargetPhoneAccount(mMockCall, emergencyPhoneAccount1.getAccountHandle());
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount1);
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2", 0, null);
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount2);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        // Make sure the valid SIM is the one that is chosen.
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * If a PhoneAccount is associated with an invalid slot, place on the other, valid slot.
     */
    @SmallTest
    @Test
    public void testEmergencyCallMultiSimNoPreferenceInvalidSlot() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        // Include a Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount1 = makeEmergencyPhoneAccount("tel_emer1", 0, null);
        mapToSubSlot(emergencyPhoneAccount1, 1 /*subId*/,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount1);
        PhoneAccount emergencyPhoneAccount2 = makeEmergencyPhoneAccount("tel_emer2", 0, null);
        mapToSubSlot(emergencyPhoneAccount2, 2 /*subId*/, 1 /*slotId*/);
        phoneAccounts.add(emergencyPhoneAccount2);
        PhoneAccountHandle emergencyPhoneAccountHandle2 = emergencyPhoneAccount2.getAccountHandle();

        mTestCreateConnectionProcessor.process();

        // Make sure the valid SIM is the one that is chosen.
        verify(mMockCall).setConnectionManagerPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setTargetPhoneAccount(eq(emergencyPhoneAccountHandle2));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
        // Notify successful connection to call
        CallIdMapper mockCallIdMapper = mock(CallIdMapper.class);
        mTestCreateConnectionProcessor.handleCreateConnectionSuccess(mockCallIdMapper, null);
        verify(mMockCreateConnectionResponse).handleCreateConnectionSuccess(mockCallIdMapper, null);
    }

    /**
     * Ensures that a self-managed phone account won't be considered when attempting to place an
     * emergency call.
     */
    @SmallTest
    @Test
    public void testDontAttemptSelfManaged() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        when(mMockCall.getHandle()).thenReturn(Uri.parse(""));

        PhoneAccount selfManagedAcct = makePhoneAccount("sm-acct",
                PhoneAccount.CAPABILITY_SELF_MANAGED
                        | PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
        phoneAccounts.add(selfManagedAcct);

        mTestCreateConnectionProcessor.process();
        verify(mMockCall, never()).setTargetPhoneAccount(any(PhoneAccountHandle.class));
    }

    @SmallTest
    @Test
    public void testEmergencyCallSimFailToConnectionManager() throws Exception {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        when(mMockCall.getHandle()).thenReturn(Uri.parse(""));
        // Put in a regular phone account to be sure it doesn't call that
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        mapToSubSlot(regularAccount, 1 /*subId*/, 0 /*slotId*/);
        setTargetPhoneAccount(mMockCall, regularAccount.getAccountHandle());
        phoneAccounts.add(regularAccount);
        when(mMockAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                nullable(String.class))).thenReturn(regularAccount.getAccountHandle());
        // Include a normal Connection Manager to be sure it doesn't call that
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccounts.add(callManagerPA);
        // Include a connection Manager for the user with the capability to make calls
        PhoneAccount emerCallManagerPA = getNewEmergencyConnectionManagerPhoneAccount("cm_acct",
                PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        PhoneAccount emergencyPhoneAccount = makeEmergencyPhoneAccount("tel_emer", 0, null);
        phoneAccounts.add(emergencyPhoneAccount);
        mapToSubSlot(regularAccount, 2 /*subId*/, 1 /*slotId*/);
        mTestCreateConnectionProcessor.process();
        reset(mMockCall);
        reset(service);

        when(mMockCall.getConnectionServiceFocusManager()).thenReturn(
                mConnectionServiceFocusManager);
        when(mMockCall.isEmergencyCall()).thenReturn(true);

        // When Notify SIM connection fails, fall back to connection manager
        mTestCreateConnectionProcessor.handleCreateConnectionFailure(new DisconnectCause(
                DisconnectCause.REJECTED));

        verify(mMockCall).setConnectionManagerPhoneAccount(
                eq(emerCallManagerPA.getAccountHandle()));
        verify(mMockCall).setTargetPhoneAccount(eq(regularAccount.getAccountHandle()));
        verify(mMockCall).setConnectionService(eq(service));
        verify(service).createConnection(eq(mMockCall), any(CreateConnectionResponse.class));
    }

    /**
     * Tests to verify that the
     * {@link CreateConnectionProcessor#sortSimPhoneAccountsForEmergency(List, PhoneAccount)}
     * can
     * successfully sort without running into sort issues related to the hashcodes of the
     * PhoneAccounts.
     */
    @Test
    public void testSortIntegrity() {
        // Note: 5L was chosen as a random seed on purpose since in combination with a count of
        // 500 accounts it would result in a crash in the sort algorithm.
        ArrayList<PhoneAccount> accounts = generateRandomPhoneAccounts(5L, 500);
        try {
            mTestCreateConnectionProcessor.sortSimPhoneAccountsForEmergency(accounts,
                    null);
        } catch (Exception e) {
            fail("Failed to sort phone accounts");
        }
    }

    @Test
    public void testIsTimeoutNeededForCall_nonEmergencyCall() {
        when(mMockCall.isEmergencyCall()).thenReturn(false);

        assertFalse(mTestCreateConnectionTimeout.isTimeoutNeededForCall(null, null));
    }

    @Test
    public void testIsTimeoutNeededForCall_noConnectionManager() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        List<PhoneAccountHandle> phoneAccountHandles = new ArrayList<>();
        // Put in a regular phone account handle
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        phoneAccountHandles.add(regularAccount.getAccountHandle());
        // Create a connection manager for the call and do not include in phoneAccountHandles
        createNewConnectionManagerPhoneAccountForCall(mMockCall, "cm_acct", 0);

        assertFalse(mTestCreateConnectionTimeout.isTimeoutNeededForCall(phoneAccountHandles, null));
    }

    @Test
    public void testIsTimeoutNeededForCall_usingConnectionManager() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        List<PhoneAccountHandle> phoneAccountHandles = new ArrayList<>();
        // Put in a regular phone account handle
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        phoneAccountHandles.add(regularAccount.getAccountHandle());
        // Create a connection manager for the call and include it in phoneAccountHandles
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccountHandles.add(callManagerPA.getAccountHandle());

        assertFalse(mTestCreateConnectionTimeout.isTimeoutNeededForCall(
                phoneAccountHandles, callManagerPA.getAccountHandle()));
    }

    @Test
    public void testIsTimeoutNeededForCall_NotSystemSimCallManager() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        List<PhoneAccountHandle> phoneAccountHandles = new ArrayList<>();
        // Put in a regular phone account handle
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        phoneAccountHandles.add(regularAccount.getAccountHandle());
        // Create a connection manager for the call and include it in phoneAccountHandles
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", 0);
        phoneAccountHandles.add(callManagerPA.getAccountHandle());

        assertFalse(mTestCreateConnectionTimeout.isTimeoutNeededForCall(phoneAccountHandles, null));
    }

    @Test
    public void testIsTimeoutNeededForCall() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockAccountRegistrar.getSystemSimCallManagerComponent()).thenReturn(
                new ComponentName(TEST_PACKAGE, TEST_CLASS));

        List<PhoneAccountHandle> phoneAccountHandles = new ArrayList<>();
        // Put in a regular phone account handle
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
        phoneAccountHandles.add(regularAccount.getAccountHandle());
        // Create a connection manager for the call and include it in phoneAccountHandles
        int capability = PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
                | PhoneAccount.CAPABILITY_VOICE_CALLING_AVAILABLE;
        PhoneAccount callManagerPA = createNewConnectionManagerPhoneAccountForCall(mMockCall,
                "cm_acct", capability);
        PhoneAccount phoneAccountWithService = makeQuickAccount("cm_acct", capability, null);
        when(mMockAccountRegistrar.getPhoneAccount(callManagerPA.getAccountHandle(),
                callManagerPA.getAccountHandle().getUserHandle()))
                .thenReturn(phoneAccountWithService);
        phoneAccountHandles.add(callManagerPA.getAccountHandle());

        assertTrue(mTestCreateConnectionTimeout.isTimeoutNeededForCall(phoneAccountHandles, null));
    }

    @Test
    public void testConnTimeout_carrierSatelliteEnabled_noInServiceConnManager_callNeverTimesOut() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        when(mMockCall.getHandle()).thenReturn(Uri.parse(""));
        when(mMockCall.getState()).thenReturn(CallState.DIALING);
        // Primary phone account, meant to fail
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                        | PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
        phoneAccounts.add(regularAccount);
        when(mMockAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                nullable(String.class))).thenReturn(regularAccount.getAccountHandle());
        when(mMockAccountRegistrar.getSystemSimCallManagerComponent()).thenReturn(
                new ComponentName(TEST_PACKAGE, TEST_CLASS));
        PhoneAccount callManagerPA = getNewEmergencyConnectionManagerPhoneAccount(
                "cm_acct", PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS
                        | PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS);
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        when(mMockAccountRegistrar.getSimCallManagerFromCall(mMockCall)).thenReturn(
                callManagerPA.getAccountHandle());
        when(mMockAccountRegistrar.getPhoneAccount(eq(callManagerPA.getAccountHandle()),
                any())).thenReturn(callManagerPA);
        Duration timeout = Duration.ofMillis(10);
        when(mTimeoutsAdapter.getEmergencyCallTimeoutMillis(any())).thenReturn(timeout.toMillis());
        when(mTimeoutsAdapter.getEmergencyCallTimeoutRadioOffMillis(any())).thenReturn(
                timeout.toMillis());


        mTestCreateConnectionProcessor.process();

        // Validate the call is not disconnected after the timeout.
        verify(service, after(timeout.toMillis() + 100).never()).disconnect(eq(mMockCall));
    }

    @Test
    public void testConnTimeout_carrierSatelliteEnabled_inServiceConnManager_callTimesOut() {
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        when(mMockCall.isTestEmergencyCall()).thenReturn(false);
        when(mMockCall.getHandle()).thenReturn(Uri.parse(""));
        when(mMockCall.getState()).thenReturn(CallState.DIALING);
        // Primary phone account, meant to fail
        PhoneAccount regularAccount = makePhoneAccount("tel_acct1",
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                        | PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
        phoneAccounts.add(regularAccount);
        when(mMockAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                nullable(String.class))).thenReturn(regularAccount.getAccountHandle());
        when(mMockAccountRegistrar.getSystemSimCallManagerComponent()).thenReturn(
                new ComponentName(TEST_PACKAGE, TEST_CLASS));
        PhoneAccount callManagerPA = getNewEmergencyConnectionManagerPhoneAccount(
                "cm_acct", PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS
                        | PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
                        | PhoneAccount.CAPABILITY_VOICE_CALLING_AVAILABLE
        );
        phoneAccounts.add(callManagerPA);
        ConnectionServiceWrapper service = makeConnectionServiceWrapper();
        when(mMockAccountRegistrar.getSimCallManagerFromCall(mMockCall)).thenReturn(
                callManagerPA.getAccountHandle());
        when(mMockAccountRegistrar.getPhoneAccount(eq(callManagerPA.getAccountHandle()),
                any())).thenReturn(callManagerPA);
        Duration timeout = Duration.ofMillis(10);
        when(mTimeoutsAdapter.getEmergencyCallTimeoutMillis(any())).thenReturn(timeout.toMillis());
        when(mTimeoutsAdapter.getEmergencyCallTimeoutRadioOffMillis(any())).thenReturn(
                timeout.toMillis());

        mTestCreateConnectionProcessor.process();

        // Validate the call was disconnected after the timeout.
        verify(service, after(timeout.toMillis() + 100)).disconnect(eq(mMockCall));
    }

    /**
     * Verifies when telephony is not available that we just get invalid sub id for a phone acct.
     */
    @SmallTest
    @Test
    public void testTelephonyAdapterWhenNoTelephony() {
        PhoneAccount telephonyAcct = makePhoneAccount("test-acct",
                PhoneAccount.CAPABILITY_CALL_PROVIDER
                        | PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);

        CreateConnectionProcessor.ITelephonyManagerAdapterImpl impl
                = new CreateConnectionProcessor.ITelephonyManagerAdapterImpl();
        when(mComponentContextFixture.getTelephonyManager().
                getSubscriptionId(any(PhoneAccountHandle.class)))
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertEquals(-1, impl.getSubIdForPhoneAccount(mContext, telephonyAcct));
    }

    /**
     * Generates random phone accounts.
     * @param seed random seed to use for random UUIDs; passed in for determinism.
     * @param count How many phone accounts to use.
     * @return Random phone accounts.
     */
    private ArrayList<PhoneAccount> generateRandomPhoneAccounts(long seed, int count) {
        Random random = new Random(seed);
        ArrayList<PhoneAccount> accounts = new ArrayList<>();
        for (int ix = 0 ; ix < count; ix++) {
            ArrayList<String> supportedSchemes = new ArrayList<>();
            supportedSchemes.add("tel");
            supportedSchemes.add("sip");
            supportedSchemes.add("custom");

            PhoneAccountHandle handle = new PhoneAccountHandle(
                    ComponentName.unflattenFromString(
                            "com.android.server.telecom.testapps/"
                                    + "com.android.server.telecom.testapps"
                                    + ".SelfManagedConnectionService"),
                    getRandomUuid(random).toString(), new UserHandle(0));
            PhoneAccount acct = new PhoneAccount.Builder(handle, "TelecommTests")
                    .setAddress(Uri.fromParts("tel", "555-1212", null))
                    .setCapabilities(3080)
                    .setHighlightColor(0)
                    .setShortDescription("test_" + ix)
                    .setSupportedUriSchemes(supportedSchemes)
                    .setIsEnabled(true)
                    .setSupportedAudioRoutes(15)
                    .build();
            accounts.add(acct);
        }
        return accounts;
    }

    /**
     * Returns a random UUID based on the passed in Random generator.
     * @param random Random generator.
     * @return The UUID.
     */
    private UUID getRandomUuid(Random random) {
        byte[] array = new byte[16];
        random.nextBytes(array);
        return UUID.nameUUIDFromBytes(array);
    }

    private PhoneAccount makeEmergencyTestPhoneAccount(String id, int capabilities) {
        final PhoneAccount emergencyPhoneAccount = makeQuickAccount(id, capabilities |
                PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS, null);
        PhoneAccountHandle emergencyPhoneAccountHandle = emergencyPhoneAccount.getAccountHandle();
        givePhoneAccountBindPermission(emergencyPhoneAccountHandle);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(emergencyPhoneAccountHandle))
                .thenReturn(emergencyPhoneAccount);
        return emergencyPhoneAccount;
    }

    private PhoneAccount makeEmergencyPhoneAccount(String id, int capabilities,
            UserHandle userHandle) {
        final PhoneAccount emergencyPhoneAccount = makeQuickAccount(id, capabilities |
                PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS |
                        PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, userHandle);
        PhoneAccountHandle emergencyPhoneAccountHandle = emergencyPhoneAccount.getAccountHandle();
        givePhoneAccountBindPermission(emergencyPhoneAccountHandle);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(emergencyPhoneAccountHandle))
                .thenReturn(emergencyPhoneAccount);
        return emergencyPhoneAccount;
    }

    private PhoneAccount makePhoneAccount(String id, int capabilities) {
        final PhoneAccount phoneAccount = makeQuickAccount(id, capabilities, null);
        PhoneAccountHandle phoneAccountHandle = phoneAccount.getAccountHandle();
        givePhoneAccountBindPermission(phoneAccountHandle);
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(
                phoneAccount.getAccountHandle())).thenReturn(phoneAccount);
        return phoneAccount;
    }

    private void mapToSubSlot(PhoneAccount account, int subId, int slotId) {
        mAccountToSub.put(account, subId);
        mSubToSlot.put(subId, slotId);
    }

    private void givePhoneAccountBindPermission(PhoneAccountHandle handle) {
        when(mMockAccountRegistrar.phoneAccountRequiresBindPermission(eq(handle))).thenReturn(true);
    }

    private PhoneAccountHandle getNewConnectionManagerHandleForCall(Call call, String id) {
        PhoneAccountHandle callManagerPAHandle = makeQuickConnMgrAccountHandle(id, null);
        when(mMockAccountRegistrar.getSimCallManagerFromCall(eq(call))).thenReturn(
                callManagerPAHandle);
        givePhoneAccountBindPermission(callManagerPAHandle);
        return callManagerPAHandle;
    }

    private PhoneAccountHandle getNewTargetPhoneAccountHandle(String id) {
        PhoneAccountHandle pAHandle = makeQuickAccountHandle(id, null);
        givePhoneAccountBindPermission(pAHandle);
        return pAHandle;
    }

    private void setTargetPhoneAccount(Call call, PhoneAccountHandle pAHandle) {
        when(call.getTargetPhoneAccount()).thenReturn(pAHandle);
    }

    private PhoneAccount createNewConnectionManagerPhoneAccountForCall(Call call, String id,
            int capability) {
        PhoneAccount callManagerPA = makeQuickAccount(id, capability, null);
        when(mMockAccountRegistrar.getSimCallManagerFromCall(eq(call))).thenReturn(
                callManagerPA.getAccountHandle());
        givePhoneAccountBindPermission(callManagerPA.getAccountHandle());
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(
                callManagerPA.getAccountHandle())).thenReturn(callManagerPA);
        return callManagerPA;
    }

    private PhoneAccount getNewEmergencyConnectionManagerPhoneAccount(String id, int capability) {
        PhoneAccount callManagerPA = makeQuickAccount(id, capability, null);
        when(mMockAccountRegistrar.getSimCallManagerOfCurrentUser()).thenReturn(
                callManagerPA.getAccountHandle());
        givePhoneAccountBindPermission(callManagerPA.getAccountHandle());
        when(mMockAccountRegistrar.getPhoneAccountUnchecked(
                callManagerPA.getAccountHandle())).thenReturn(callManagerPA);
        return callManagerPA;
    }

    private static ComponentName makeQuickConnectionServiceComponentName() {
        return new ComponentName(TEST_PACKAGE, TEST_CLASS);
    }

    private static ComponentName makeQuickConnMgrConnectionServiceComponentName() {
        return new ComponentName(TEST_PACKAGE, CONNECTION_MANAGER_TEST_CLASS);
    }

    private ConnectionServiceWrapper makeConnectionServiceWrapper() {
        ConnectionServiceWrapper wrapper = mock(ConnectionServiceWrapper.class);

        when(mMockConnectionServiceRepository.getService(
                eq(makeQuickConnectionServiceComponentName()), any(UserHandle.class)))
                .thenReturn(wrapper);
        return wrapper;
    }

    private ConnectionServiceWrapper makeConnMgrConnectionServiceWrapper() {
        ConnectionServiceWrapper wrapper = mock(ConnectionServiceWrapper.class);

        when(mMockConnectionServiceRepository.getService(
                eq(makeQuickConnMgrConnectionServiceComponentName()), any(UserHandle.class)))
                .thenReturn(wrapper);
        return wrapper;
    }

    private static PhoneAccountHandle makeQuickConnMgrAccountHandle(String id,
            UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = Binder.getCallingUserHandle();
        }
        return new PhoneAccountHandle(makeQuickConnMgrConnectionServiceComponentName(),
                id, userHandle);
    }

    private static PhoneAccountHandle makeQuickAccountHandle(String id, UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = Binder.getCallingUserHandle();
        }
        return new PhoneAccountHandle(makeQuickConnectionServiceComponentName(), id, userHandle);
    }

    private PhoneAccount.Builder makeQuickAccountBuilder(String id, int idx,
            UserHandle userHandle) {
        return new PhoneAccount.Builder(makeQuickAccountHandle(id, userHandle), "label" + idx);
    }

    private PhoneAccount makeQuickAccount(String id, int idx, UserHandle userHandle) {
        return makeQuickAccountBuilder(id, idx, userHandle)
                .setAddress(Uri.parse("http://foo.com/" + idx))
                .setSubscriptionAddress(Uri.parse("tel:555-000" + idx))
                .setCapabilities(idx)
                .setIcon(Icon.createWithResource(
                        "com.android.server.telecom.tests", R.drawable.stat_sys_phone_call))
                .setShortDescription("desc" + idx)
                .setIsEnabled(true)
                .build();
    }
}
