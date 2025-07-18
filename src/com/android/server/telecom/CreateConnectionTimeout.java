/*
 * Copyright 2015, The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Log;
import android.telecom.Logging.Runnable;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collection;
import java.util.Objects;

/**
 * Registers a timeout for a call and disconnects the call when the timeout expires.
 */
@VisibleForTesting
public final class CreateConnectionTimeout extends Runnable {
    private final Context mContext;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final ConnectionServiceWrapper mConnectionService;
    private final Call mCall;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsRegistered;
    private boolean mIsCallTimedOut;
    private final Timeouts.Adapter mTimeoutsAdapter;

    @VisibleForTesting
    public CreateConnectionTimeout(Context context, PhoneAccountRegistrar phoneAccountRegistrar,
            ConnectionServiceWrapper service, Call call, Timeouts.Adapter timeoutsAdapter) {
        super("CCT", null /*lock*/);
        mContext = context;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mConnectionService = service;
        mCall = call;
        mTimeoutsAdapter = timeoutsAdapter;
    }

    @VisibleForTesting
    public boolean isTimeoutNeededForCall(Collection<PhoneAccountHandle> accounts,
            PhoneAccountHandle currentAccount) {
        // Non-emergency calls timeout automatically at the radio layer. No need for a timeout here.
        if (!mCall.isEmergencyCall()) {
            Log.d(this, "isTimeoutNeededForCall, not an emergency call");
            return false;
        }

        // If there's no connection manager to fallback on then there's no point in having a
        // timeout.
        PhoneAccountHandle connectionManager =
                mPhoneAccountRegistrar.getSimCallManagerFromCall(mCall);
        if (!accounts.contains(connectionManager)) {
            Log.d(this, "isTimeoutNeededForCall, no connection manager");
            return false;
        }

        // No need to add a timeout if the current attempt is over the connection manager.
        if (Objects.equals(connectionManager, currentAccount)) {
            Log.d(this, "isTimeoutNeededForCall, already attempting over connection manager");
            return false;
        }

        // Timeout is only supported for SIM call managers that are set by the carrier.
        if (connectionManager != null && !Objects.equals(connectionManager.getComponentName(),
                mPhoneAccountRegistrar.getSystemSimCallManagerComponent())) {
            Log.d(this, "isTimeoutNeededForCall, not a system sim call manager");
            return false;
        }

        Log.i(this, "isTimeoutNeededForCall, returning true");
        return true;
    }

    void registerTimeout() {
        Log.d(this, "registerTimeout");
        mIsRegistered = true;

        long timeoutLengthMillis = getTimeoutLengthMillis();
        if (timeoutLengthMillis <= 0) {
            Log.d(this, "registerTimeout, timeout set to %d, skipping", timeoutLengthMillis);
        } else {
            mHandler.postDelayed(prepare(), timeoutLengthMillis);
        }
    }

    void unregisterTimeout() {
        Log.d(this, "unregisterTimeout");
        mIsRegistered = false;
        mHandler.removeCallbacksAndMessages(null);
        cancel();
    }

    boolean isCallTimedOut() {
        return mIsCallTimedOut;
    }

    @Override
    public void loggedRun() {
        PhoneAccountHandle connectionManager =
                mPhoneAccountRegistrar.getSimCallManagerFromCall(mCall);
        if (connectionManager != null) {
            PhoneAccount account = mPhoneAccountRegistrar.getPhoneAccount(connectionManager,
                    connectionManager.getUserHandle());
            if (account != null && account.hasCapabilities(
                    (PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
                            | PhoneAccount.CAPABILITY_VOICE_CALLING_AVAILABLE))) {
                // If we have encountered the timeout and there is an in service
                // ConnectionManager, disconnect the call so that it can be attempted over
                // the ConnectionManager.
                timeoutCallIfNeeded();
                return;
            }
            Log.i(
                this,
               "loggedRun, no PhoneAccount with voice calling capabilities, not timing out call");
        }
    }

    private void timeoutCallIfNeeded() {
        if (mIsRegistered && isCallBeingPlaced(mCall)) {
            Log.i(this, "timeoutCallIfNeeded, call timed out, calling disconnect");
            mIsCallTimedOut = true;
            mConnectionService.disconnect(mCall);
        }
    }

    static boolean isCallBeingPlaced(Call call) {
        int state = call.getState();
        return state == CallState.NEW
            || state == CallState.CONNECTING
            || state == CallState.DIALING
            || state == CallState.PULLING;
    }

    private long getTimeoutLengthMillis() {
        // If the radio is off then use a longer timeout. This gives us more time to power on the
        // radio.
        try {
            TelephonyManager telephonyManager =
                    (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager.isRadioOn()) {
                return mTimeoutsAdapter.getEmergencyCallTimeoutMillis(
                        mContext.getContentResolver());
            } else {
                return mTimeoutsAdapter.getEmergencyCallTimeoutRadioOffMillis(
                        mContext.getContentResolver());
            }
        } catch (UnsupportedOperationException uoe) {
            Log.e(this, uoe, "getTimeoutLengthMillis - telephony is not supported");
            return mTimeoutsAdapter.getEmergencyCallTimeoutMillis(mContext.getContentResolver());
        }
    }
}
