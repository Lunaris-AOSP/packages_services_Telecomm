/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

// TODO: Needed for move to system service: import com.android.internal.R;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.flags.Flags;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class creates connections to place new outgoing calls or to attach to an existing incoming
 * call. In either case, this class cycles through a set of connection services until:
 *   - a connection service returns a newly created connection in which case the call is displayed
 *     to the user
 *   - a connection service cancels the process, in which case the call is aborted
 */
@VisibleForTesting
public class CreateConnectionProcessor implements CreateConnectionResponse {

    // Describes information required to attempt to make a phone call
    private static class CallAttemptRecord {
        // The PhoneAccount describing the target connection service which we will
        // contact in order to process an attempt
        public final PhoneAccountHandle connectionManagerPhoneAccount;
        // The PhoneAccount which we will tell the target connection service to use
        // for attempting to make the actual phone call
        public final PhoneAccountHandle targetPhoneAccount;

        public CallAttemptRecord(
                PhoneAccountHandle connectionManagerPhoneAccount,
                PhoneAccountHandle targetPhoneAccount) {
            this.connectionManagerPhoneAccount = connectionManagerPhoneAccount;
            this.targetPhoneAccount = targetPhoneAccount;
        }

        @Override
        public String toString() {
            return "CallAttemptRecord("
                    + Objects.toString(connectionManagerPhoneAccount) + ","
                    + Objects.toString(targetPhoneAccount) + ")";
        }

        /**
         * Determines if this instance of {@code CallAttemptRecord} has the same underlying
         * {@code PhoneAccountHandle}s as another instance.
         *
         * @param obj The other instance to compare against.
         * @return {@code True} if the {@code CallAttemptRecord}s are equal.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof CallAttemptRecord) {
                CallAttemptRecord other = (CallAttemptRecord) obj;
                return Objects.equals(connectionManagerPhoneAccount,
                        other.connectionManagerPhoneAccount) &&
                        Objects.equals(targetPhoneAccount, other.targetPhoneAccount);
            }
            return false;
        }
    }

    @VisibleForTesting
    public interface ITelephonyManagerAdapter {
        int getSubIdForPhoneAccount(Context context, PhoneAccount account);
        int getSlotIndex(int subId);
    }

    public static class ITelephonyManagerAdapterImpl implements ITelephonyManagerAdapter {
        @Override
        public int getSubIdForPhoneAccount(Context context, PhoneAccount account) {
            TelephonyManager manager = context.getSystemService(TelephonyManager.class);
            if (manager == null) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
            try {
                return manager.getSubscriptionId(account.getAccountHandle());
            } catch (UnsupportedOperationException uoe) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
        }

        @Override
        public int getSlotIndex(int subId) {
            try {
                return SubscriptionManager.getSlotIndex(subId);
            } catch (UnsupportedOperationException uoe) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
        }
    };

    /**
     * Call states which should be prioritized when sorting phone accounts. The ordering is
     * intentional and should NOT be modified. Other call states will not have any priority.
     */
    private static final int[] PRIORITY_CALL_STATES = new int []
            {CallState.ACTIVE, CallState.ON_HOLD, CallState.DIALING, CallState.RINGING};
    private static final int DEFAULT_CALL_STATE_PRIORITY = PRIORITY_CALL_STATES.length;
    private static final Map<Integer, Integer> mCallStatePriorityMap = new HashMap<>();
    static {
        for (int i = 0; i < PRIORITY_CALL_STATES.length; i++) {
            mCallStatePriorityMap.put(PRIORITY_CALL_STATES[i], i);
        }
    }


    private ITelephonyManagerAdapter mTelephonyAdapter = new ITelephonyManagerAdapterImpl();

    private final Call mCall;
    private final ConnectionServiceRepository mRepository;
    private List<CallAttemptRecord> mAttemptRecords;
    private Iterator<CallAttemptRecord> mAttemptRecordIterator;
    private CreateConnectionResponse mCallResponse;
    private DisconnectCause mLastErrorDisconnectCause;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final CallsManager mCallsManager;
    private final Context mContext;
    private final FeatureFlags mFlags;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private CreateConnectionTimeout mTimeout;
    private ConnectionServiceWrapper mService;
    private int mConnectionAttempt;

    @VisibleForTesting
    public CreateConnectionProcessor(Call call,
            ConnectionServiceRepository repository,
            CreateConnectionResponse response,
            PhoneAccountRegistrar phoneAccountRegistrar,
            CallsManager callsManager,
            Context context,
            FeatureFlags featureFlags,
            Timeouts.Adapter timeoutsAdapter) {
        Log.v(this, "CreateConnectionProcessor created for Call = %s", call);
        mCall = call;
        mRepository = repository;
        mCallResponse = response;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mCallsManager = callsManager;
        mContext = context;
        mConnectionAttempt = 0;
        mFlags = featureFlags;
        mTimeoutsAdapter = timeoutsAdapter;
    }

    boolean isProcessingComplete() {
        return mCallResponse == null;
    }

    boolean isCallTimedOut() {
        return mTimeout != null && mTimeout.isCallTimedOut();
    }

    public int getConnectionAttempt() {
        return mConnectionAttempt;
    }

    @VisibleForTesting
    public void setTelephonyManagerAdapter(ITelephonyManagerAdapter adapter) {
        mTelephonyAdapter = adapter;
    }

    @VisibleForTesting
    public void process() {
        Log.v(this, "process");
        clearTimeout();
        mAttemptRecords = new ArrayList<>();
        if (mCall.getTargetPhoneAccount() != null) {
            mAttemptRecords.add(new CallAttemptRecord(
                    mCall.getTargetPhoneAccount(), mCall.getTargetPhoneAccount()));
        }
        if (!mCall.isSelfManaged()) {
            adjustAttemptsForConnectionManager();
            adjustAttemptsForEmergency(mCall.getTargetPhoneAccount());
        }
        mAttemptRecordIterator = mAttemptRecords.iterator();
        attemptNextPhoneAccount();
    }

    boolean hasMorePhoneAccounts() {
        return mAttemptRecordIterator.hasNext();
    }

    void continueProcessingIfPossible(CreateConnectionResponse response,
            DisconnectCause disconnectCause) {
        Log.v(this, "continueProcessingIfPossible");
        mCallResponse = response;
        mLastErrorDisconnectCause = disconnectCause;
        attemptNextPhoneAccount();
    }

    void abort() {
        Log.v(this, "abort");

        // Clear the response first to prevent attemptNextConnectionService from attempting any
        // more services.
        CreateConnectionResponse response = mCallResponse;
        mCallResponse = null;
        clearTimeout();

        ConnectionServiceWrapper service = mCall.getConnectionService();
        if (service != null) {
            service.abort(mCall);
            mCall.clearConnectionService();
        }
        if (response != null) {
            response.handleCreateConnectionFailure(new DisconnectCause(DisconnectCause.LOCAL));
        }
    }

    private void attemptNextPhoneAccount() {
        Log.v(this, "attemptNextPhoneAccount");
        CallAttemptRecord attempt = null;
        if (mAttemptRecordIterator.hasNext()) {
            attempt = mAttemptRecordIterator.next();

            if (!mPhoneAccountRegistrar.phoneAccountRequiresBindPermission(
                    attempt.connectionManagerPhoneAccount)) {
                Log.w(this,
                        "Connection mgr does not have BIND_TELECOM_CONNECTION_SERVICE for "
                                + "attempt: %s", attempt);
                attemptNextPhoneAccount();
                return;
            }

            // If the target PhoneAccount differs from the ConnectionManager phone acount, ensure it
            // also requires the BIND_TELECOM_CONNECTION_SERVICE permission.
            if (!attempt.connectionManagerPhoneAccount.equals(attempt.targetPhoneAccount) &&
                    !mPhoneAccountRegistrar.phoneAccountRequiresBindPermission(
                            attempt.targetPhoneAccount)) {
                Log.w(this,
                        "Target PhoneAccount does not have BIND_TELECOM_CONNECTION_SERVICE for "
                                + "attempt: %s", attempt);
                attemptNextPhoneAccount();
                return;
            }
        }

        if (mCallResponse != null && attempt != null) {
            Log.i(this, "Trying attempt %s", attempt);
            PhoneAccountHandle phoneAccount = attempt.connectionManagerPhoneAccount;
            mService = mRepository.getService(phoneAccount.getComponentName(),
                    phoneAccount.getUserHandle());
            if (mService == null) {
                Log.i(this, "Found no connection service for attempt %s", attempt);
                attemptNextPhoneAccount();
            } else {
                mConnectionAttempt++;
                mCall.setConnectionManagerPhoneAccount(attempt.connectionManagerPhoneAccount);
                mCall.setTargetPhoneAccount(attempt.targetPhoneAccount);
                if (mFlags.updatedRcsCallCountTracking()) {
                    if (Objects.equals(attempt.connectionManagerPhoneAccount,
                            attempt.targetPhoneAccount)) {
                        mCall.setConnectionService(mService);
                    } else {
                        PhoneAccountHandle remotePhoneAccount = attempt.targetPhoneAccount;
                        ConnectionServiceWrapper mRemoteService =
                                mRepository.getService(remotePhoneAccount.getComponentName(),
                                remotePhoneAccount.getUserHandle());
                        if (mRemoteService == null) {
                            mCall.setConnectionService(mService);
                        } else {
                            Log.v(this, "attemptNextPhoneAccount Setting RCS = %s", mRemoteService);
                            mCall.setConnectionService(mService, mRemoteService);
                        }
                    }
                } else {
                    mCall.setConnectionService(mService);
                }
                setTimeoutIfNeeded(mService, attempt);
                if (mCall.isIncoming()) {
                    if (mCall.isAdhocConferenceCall()) {
                        mService.createConference(mCall, CreateConnectionProcessor.this);
                    } else {
                        mService.createConnection(mCall, CreateConnectionProcessor.this);
                    }
                } else {
                    // Start to create the connection for outgoing call after the ConnectionService
                    // of the call has gained the focus.
                    mCall.getConnectionServiceFocusManager().requestFocus(
                            mCall,
                            new CallsManager.RequestCallback(new CallsManager.PendingAction() {
                                @Override
                                public void performAction() {
                                    if (mCall.isAdhocConferenceCall()) {
                                        Log.d(this, "perform create conference");
                                        mService.createConference(mCall,
                                                CreateConnectionProcessor.this);
                                    } else {
                                        Log.d(this, "perform create connection");
                                        mService.createConnection(
                                                mCall,
                                                CreateConnectionProcessor.this);
                                    }
                                }
                            }));

                }
            }
        } else {
            Log.v(this, "attemptNextPhoneAccount, no more accounts, failing");
            DisconnectCause disconnectCause = mLastErrorDisconnectCause != null ?
                    mLastErrorDisconnectCause : new DisconnectCause(DisconnectCause.ERROR);
            if (mCall.isAdhocConferenceCall()) {
                notifyConferenceCallFailure(disconnectCause);
            } else {
                notifyCallConnectionFailure(disconnectCause);
            }
        }
    }

    private void setTimeoutIfNeeded(ConnectionServiceWrapper service, CallAttemptRecord attempt) {
        clearTimeout();

        CreateConnectionTimeout timeout = new CreateConnectionTimeout(
                mContext, mPhoneAccountRegistrar, service, mCall, mTimeoutsAdapter);
        if (timeout.isTimeoutNeededForCall(getConnectionServices(mAttemptRecords),
                attempt.connectionManagerPhoneAccount)) {
            mTimeout = timeout;
            timeout.registerTimeout();
        }
    }

    private void clearTimeout() {
        if (mTimeout != null) {
            mTimeout.unregisterTimeout();
            mTimeout = null;
        }
    }

    private boolean shouldSetConnectionManager() {
        if (mAttemptRecords.size() == 0) {
            return false;
        }

        if (mAttemptRecords.size() > 1) {
            Log.d(this, "shouldSetConnectionManager, error, mAttemptRecords should not have more "
                    + "than 1 record");
            return false;
        }

        PhoneAccountHandle connectionManager =
                mPhoneAccountRegistrar.getSimCallManagerFromCall(mCall);
        if (connectionManager == null) {
            return false;
        }

        PhoneAccountHandle targetPhoneAccountHandle = mAttemptRecords.get(0).targetPhoneAccount;
        if (Objects.equals(connectionManager, targetPhoneAccountHandle)) {
            return false;
        }

        // Connection managers are only allowed to manage SIM subscriptions.
        // TODO: Should this really be checking the "calling user" test for phone account?
        PhoneAccount targetPhoneAccount = mPhoneAccountRegistrar
                .getPhoneAccountUnchecked(targetPhoneAccountHandle);
        if (targetPhoneAccount == null) {
            Log.d(this, "shouldSetConnectionManager, phone account not found");
            return false;
        }
        boolean isSimSubscription = (targetPhoneAccount.getCapabilities() &
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0;
        if (!isSimSubscription) {
            return false;
        }

        return true;
    }

    // If there exists a registered connection manager then use it.
    private void adjustAttemptsForConnectionManager() {
        if (shouldSetConnectionManager()) {
            CallAttemptRecord record = new CallAttemptRecord(
                    mPhoneAccountRegistrar.getSimCallManagerFromCall(mCall),
                    mAttemptRecords.get(0).targetPhoneAccount);
            Log.v(this, "setConnectionManager, changing %s -> %s", mAttemptRecords.get(0), record);
            mAttemptRecords.add(0, record);
        } else {
            Log.v(this, "setConnectionManager, not changing");
        }
    }

    // This function is used after previous attempts to find emergency PSTN connections
    // do not find any SIM phone accounts with emergency capability.
    // It attempts to add any accounts with CAPABILITY_PLACE_EMERGENCY_CALLS even if
    // accounts are not SIM accounts.
    private void adjustAttemptsForEmergencyNoSimRequired(List<PhoneAccount> allAccounts) {
        // Add all phone accounts which can place emergency calls.
        if (mAttemptRecords.isEmpty()) {
            for (PhoneAccount phoneAccount : allAccounts) {
                if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)) {
                    PhoneAccountHandle phoneAccountHandle = phoneAccount.getAccountHandle();
                    Log.i(this, "Will try account %s for emergency", phoneAccountHandle);
                    mAttemptRecords.add(
                            new CallAttemptRecord(phoneAccountHandle, phoneAccountHandle));
                    // Add only one emergency PhoneAccount to the attempt list.
                    break;
                }
            }
        }
    }

    // If we are possibly attempting to call a local emergency number, ensure that the
    // plain PSTN connection services are listed, and nothing else.
    private void adjustAttemptsForEmergency(PhoneAccountHandle preferredPAH) {
        if (mCall.isEmergencyCall()) {
            Log.i(this, "Emergency number detected");
            mAttemptRecords.clear();
            // Phone accounts in profile do not handle emergency call, use phone accounts in
            // current user.
            // ONLY include phone accounts which are NOT self-managed; we will never consider a self
            // managed phone account for placing an emergency call.
            UserHandle userFromCall = mCall.getAssociatedUser();
            List<PhoneAccount> allAccounts = mPhoneAccountRegistrar
                    .getAllPhoneAccounts(userFromCall, false)
                    .stream()
                    .filter(act -> !act.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED))
                    .collect(Collectors.toList());

            if (allAccounts.isEmpty()) {
                // Try using phone accounts from other users to place the call (i.e. using an
                // available work sim) given that the current user has the INTERACT_ACROSS_USERS
                // permission.
                allAccounts = mPhoneAccountRegistrar.getAllPhoneAccounts(userFromCall, true)
                        .stream()
                        .filter(act -> !act.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED))
                        .collect(Collectors.toList());
            }

            if (allAccounts.isEmpty() && mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY)) {
                // If the list of phone accounts is empty at this point, it means Telephony hasn't
                // registered any phone accounts yet. Add a fallback emergency phone account so
                // that emergency calls can still go through. We create a new ArrayLists here just
                // in case the implementation of PhoneAccountRegistrar ever returns an unmodifiable
                // list.
                allAccounts = new ArrayList<PhoneAccount>();
                allAccounts.add(TelephonyUtil.getDefaultEmergencyPhoneAccount());
            }

            // When testing emergency calls, we want the calls to go through to the test connection
            // service, not the telephony ConnectionService.
            if (mCall.isTestEmergencyCall()) {
                Log.i(this, "Processing test emergency call -- special rules");
                allAccounts = mPhoneAccountRegistrar.filterRestrictedPhoneAccounts(allAccounts);
            }

            // Get user preferred PA if it exists.
            PhoneAccount preferredPA = mPhoneAccountRegistrar.getPhoneAccountUnchecked(
                    preferredPAH);
            if (mCall.isIncoming() && preferredPA != null) {
                // The phone account for the incoming call should be used.
                mAttemptRecords.add(new CallAttemptRecord(preferredPA.getAccountHandle(),
                        preferredPA.getAccountHandle()));
            } else {
                // Next, add all SIM phone accounts which can place emergency calls.
                sortSimPhoneAccountsForEmergency(allAccounts, preferredPA);
                Log.i(this, "The preferred PA is: %s", preferredPA);
                // and pick the first one that can place emergency calls.
                for (PhoneAccount phoneAccount : allAccounts) {
                    if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)
                            && phoneAccount.hasCapabilities(
                            PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                        PhoneAccountHandle phoneAccountHandle = phoneAccount.getAccountHandle();
                        Log.i(this, "Will try PSTN account %s for emergency",
                                phoneAccountHandle);
                        mAttemptRecords.add(new CallAttemptRecord(phoneAccountHandle,
                                phoneAccountHandle));
                        // Add only one emergency SIM PhoneAccount to the attempt list, telephony
                        // will perform retries if the call fails.
                        break;
                    }
                }
            }

            // Next, add the connection manager account as a backup if it can place emergency calls.
            PhoneAccountHandle callManagerHandle =
                    mPhoneAccountRegistrar.getSimCallManagerOfCurrentUser();
            if (callManagerHandle != null) {
                // TODO: Should this really be checking the "calling user" test for phone account?
                PhoneAccount callManager = mPhoneAccountRegistrar
                        .getPhoneAccountUnchecked(callManagerHandle);
                if (callManager != null && callManager.hasCapabilities(
                        PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)) {
                    CallAttemptRecord callAttemptRecord = new CallAttemptRecord(callManagerHandle,
                            mPhoneAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                                    mCall.getHandle() == null
                                            ? null : mCall.getHandle().getScheme()));
                    // If the target phone account is null, we'll run into a NPE during the retry
                    // process, so skip it now if it's null.
                    if (callAttemptRecord.targetPhoneAccount != null
                            && !mAttemptRecords.contains(callAttemptRecord)) {
                        Log.i(this, "Will try Connection Manager account %s for emergency",
                                callManager);
                        mAttemptRecords.add(callAttemptRecord);
                    }
                }
            }

            if (mAttemptRecords.isEmpty()) {
                // Last best-effort attempt: choose any account with emergency capability even
                // without SIM capability.
                adjustAttemptsForEmergencyNoSimRequired(allAccounts);
            }
        }
    }

    /** Returns all connection services used by the call attempt records. */
    private static Collection<PhoneAccountHandle> getConnectionServices(
            List<CallAttemptRecord> records) {
        HashSet<PhoneAccountHandle> result = new HashSet<>();
        for (CallAttemptRecord record : records) {
            result.add(record.connectionManagerPhoneAccount);
        }
        return result;
    }


    private void notifyCallConnectionFailure(DisconnectCause errorDisconnectCause) {
        if (mCallResponse != null) {
            clearTimeout();
            mCallResponse.handleCreateConnectionFailure(errorDisconnectCause);
            mCallResponse = null;
            mCall.clearConnectionService();
        }
    }

    private void notifyConferenceCallFailure(DisconnectCause errorDisconnectCause) {
        if (mCallResponse != null) {
            clearTimeout();
            mCallResponse.handleCreateConferenceFailure(errorDisconnectCause);
            mCallResponse = null;
            mCall.clearConnectionService();
        }
    }


    @Override
    public void handleCreateConnectionSuccess(
            CallIdMapper idMapper,
            ParcelableConnection connection) {
        if (mCallResponse == null) {
            // Nobody is listening for this connection attempt any longer; ask the responsible
            // ConnectionService to tear down any resources associated with the call
            mService.abort(mCall);
        } else {
            // Success -- share the good news and remember that we are no longer interested
            // in hearing about any more attempts
            mCallResponse.handleCreateConnectionSuccess(idMapper, connection);
            mCallResponse = null;
            // If there's a timeout running then don't clear it. The timeout can be triggered
            // after the call has successfully been created but before it has become active.
        }
    }

    @Override
    public void handleCreateConferenceSuccess(
            CallIdMapper idMapper,
            ParcelableConference conference) {
        if (mCallResponse == null) {
            // Nobody is listening for this conference attempt any longer; ask the responsible
            // ConnectionService to tear down any resources associated with the call
            mService.abort(mCall);
        } else {
            // Success -- share the good news and remember that we are no longer interested
            // in hearing about any more attempts
            mCallResponse.handleCreateConferenceSuccess(idMapper, conference);
            mCallResponse = null;
            // If there's a timeout running then don't clear it. The timeout can be triggered
            // after the call has successfully been created but before it has become active.
        }
    }


    private boolean shouldFailCallIfConnectionManagerFails(DisconnectCause cause) {
        // Connection Manager does not exist or does not match registered Connection Manager
        // Since Connection manager is a proxy for SIM, fall back to SIM
        PhoneAccountHandle handle = mCall.getConnectionManagerPhoneAccount();
        if (handle == null || !handle.equals(mPhoneAccountRegistrar.getSimCallManagerFromCall(
                mCall))) {
            return false;
        }

        // The Call's Connection Service does not exist
        ConnectionServiceWrapper connectionManager = mCall.getConnectionService();
        if (connectionManager == null) {
            return true;
        }

        // In this case, fall back to a sim because connection manager declined
        if (cause.getCode() == DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED) {
            Log.d(CreateConnectionProcessor.this, "Connection manager declined to handle the "
                    + "call, falling back to not using a connection manager");
            return false;
        }

        if (!connectionManager.isServiceValid("createConnection")) {
            Log.d(CreateConnectionProcessor.this, "Connection manager unbound while trying "
                    + "create a connection, falling back to not using a connection manager");
            return false;
        }

        // Do not fall back from connection manager and simply fail call if the failure reason is
        // other
        Log.d(CreateConnectionProcessor.this, "Connection Manager denied call with the following " +
                "error: " + cause.getReason() + ". Not falling back to SIM.");
        return true;
    }

    @Override
    public void handleCreateConnectionFailure(DisconnectCause errorDisconnectCause) {
        // Failure of some sort; record the reasons for failure and try again if possible
        Log.d(CreateConnectionProcessor.this, "Connection failed: (%s)", errorDisconnectCause);
        if (shouldFailCallIfConnectionManagerFails(errorDisconnectCause)) {
            notifyCallConnectionFailure(errorDisconnectCause);
            return;
        }
        mLastErrorDisconnectCause = errorDisconnectCause;
        attemptNextPhoneAccount();
    }

    @Override
    public void handleCreateConferenceFailure(DisconnectCause errorDisconnectCause) {
        // Failure of some sort; record the reasons for failure and try again if possible
        Log.d(CreateConnectionProcessor.this, "Conference failed: (%s)", errorDisconnectCause);
        if (shouldFailCallIfConnectionManagerFails(errorDisconnectCause)) {
            notifyConferenceCallFailure(errorDisconnectCause);
            return;
        }
        mLastErrorDisconnectCause = errorDisconnectCause;
        attemptNextPhoneAccount();
    }

    public void sortSimPhoneAccountsForEmergency(List<PhoneAccount> accounts,
            PhoneAccount userPreferredAccount) {
        // Sort the accounts according to how we want to display them (ascending order).
        accounts.sort((account1, account2) -> {
            int retval = 0;

            // SIM accounts go first
            boolean isSim1 = account1.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
            boolean isSim2 = account2.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
            if (isSim1 ^ isSim2) {
                return isSim1 ? -1 : 1;
            }

            // Start with the account that Telephony considers as the "emergency preferred"
            // account, which overrides the user's choice.
            boolean isSim1Preferred = account1.hasCapabilities(
                    PhoneAccount.CAPABILITY_EMERGENCY_PREFERRED);
            boolean isSim2Preferred = account2.hasCapabilities(
                    PhoneAccount.CAPABILITY_EMERGENCY_PREFERRED);
            // Perform XOR, we only sort if one is considered emergency preferred (should
            // always be the case).
            if (isSim1Preferred ^ isSim2Preferred) {
                return isSim1Preferred ? -1 : 1;
            }

            // Return the PhoneAccount associated with a valid logical slot.
            int subId1 = mTelephonyAdapter.getSubIdForPhoneAccount(mContext, account1);
            int subId2 = mTelephonyAdapter.getSubIdForPhoneAccount(mContext, account2);
            int slotId1 = (subId1 != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    ? mTelephonyAdapter.getSlotIndex(subId1)
                    : SubscriptionManager.INVALID_SIM_SLOT_INDEX;
            int slotId2 = (subId2 != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    ? mTelephonyAdapter.getSlotIndex(subId2)
                    : SubscriptionManager.INVALID_SIM_SLOT_INDEX;
            // Make sure both slots are valid, if one is not, prefer the one that is valid.
            if ((slotId1 == SubscriptionManager.INVALID_SIM_SLOT_INDEX) ^
                    (slotId2 == SubscriptionManager.INVALID_SIM_SLOT_INDEX)) {
                retval = (slotId1 != SubscriptionManager.INVALID_SIM_SLOT_INDEX) ? -1 : 1;
            }
            if (retval != 0) {
                return retval;
            }

            // Sort accounts by ongoing call states
            Set<Integer> callStatesAccount1 = mCallsManager.getCalls().stream()
                    .filter(c -> Objects.equals(account1.getAccountHandle(),
                            c.getTargetPhoneAccount()))
                    .map(Call::getState).collect(Collectors.toSet());
            Set<Integer> callStatesAccount2 = mCallsManager.getCalls().stream()
                    .filter(c -> Objects.equals(account2.getAccountHandle(),
                            c.getTargetPhoneAccount()))
                    .map(Call::getState).collect(Collectors.toSet());
            int account1Priority = computeCallStatePriority(callStatesAccount1);
            int account2Priority = computeCallStatePriority(callStatesAccount2);
            Log.d(this, "account1: %s, call state priority: %s", account1, account1Priority);
            Log.d(this, "account2: %s, call state priority: %s", account2, account2Priority);
            if (account1Priority != account2Priority) {
                return account1Priority < account2Priority ? -1 : 1;
            }

            // Prefer the user's choice if all PhoneAccounts are associated with valid logical
            // slots.
            if (userPreferredAccount != null) {
                if (account1.equals(userPreferredAccount)) {
                    return -1;
                } else if (account2.equals(userPreferredAccount)) {
                    return 1;
                }
            }

            // because of the xor above, slotId1 and slotId2 are either both invalid or valid at
            // this point. If valid, prefer the lower slot index.
            if (slotId1 != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                // Assuming the slots are different, we should not have slotId1 == slotId2.
                return (slotId1 < slotId2) ? -1 : 1;
            }

            // Then order by package
            String pkg1 = account1.getAccountHandle().getComponentName().getPackageName();
            String pkg2 = account2.getAccountHandle().getComponentName().getPackageName();
            retval = pkg1.compareTo(pkg2);
            if (retval != 0) {
                return retval;
            }

            // then order by label
            String label1 = nullToEmpty(account1.getLabel().toString());
            String label2 = nullToEmpty(account2.getLabel().toString());
            retval = label1.compareTo(label2);
            if (retval != 0) {
                return retval;
            }

            // then by hashcode
            return Integer.compare(account1.hashCode(), account2.hashCode());
        });
    }

    /**
     * Computes the call state priority based on the passed in call states associated with the
     * calls present on the phone account. The lower the value, the higher the priority (i.e.
     * ACTIVE (0) < HOLDING (1) < DIALING (2) < RINGING (3) equates to ACTIVE holding the highest
     * priority).
     */
    private int computeCallStatePriority(Set<Integer> callStates) {
        int priority = DEFAULT_CALL_STATE_PRIORITY;
        for (int state: callStates) {
            if (priority == mCallStatePriorityMap.get(CallState.ACTIVE)) {
                return priority;
            } else if (mCallStatePriorityMap.containsKey(state)
                    && priority > mCallStatePriorityMap.get(state)) {
                priority = mCallStatePriorityMap.get(state);
            }
        }
        return priority;
    }

    private static String nullToEmpty(String str) {
        return str == null ? "" : str;
    }
}
