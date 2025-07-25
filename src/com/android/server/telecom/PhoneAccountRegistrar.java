/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.AsyncTask;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telecom.CallAudioState;
import android.telecom.ConnectionService;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.EventLog;
import android.util.Xml;

// TODO: Needed for move to system service: import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.ModifiedUtf8;
import com.android.server.telecom.flags.Flags;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Integer;
import java.lang.SecurityException;
import java.lang.String;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Handles writing and reading PhoneAccountHandle registration entries. This is a simple verbatim
 * delegate for all the account handling methods on {@link android.telecom.TelecomManager} as
 * implemented in {@link TelecomServiceImpl}, with the notable exception that
 * {@link TelecomServiceImpl} is responsible for security checking to make sure that the caller has
 * proper authority over the {@code ComponentName}s they are declaring in their
 * {@code PhoneAccountHandle}s.
 *
 *
 *  -- About Users and Phone Accounts --
 *
 * We store all phone accounts for all users in a single place, which means that there are three
 * users that we have to deal with in code:
 * 1) The Android User that is currently active on the device.
 * 2) The user which owns/registers the phone account.
 * 3) The user running the app that is requesting the phone account information.
 *
 * For example, I have a device with 2 users, primary (A) and secondary (B), and the secondary user
 * has a work profile running as another user (B2). Each user/profile only have the visibility of
 * phone accounts owned by them. Lets say, user B (settings) is requesting a list of phone accounts,
 * and the list only contains phone accounts owned by user B and accounts with
 * {@link PhoneAccount#CAPABILITY_MULTI_USER}.
 *
 * In practice, (2) is stored with the phone account handle and is part of the handle's ID. (1) is
 * saved in {@link #mCurrentUserHandle} and (3) we get from Binder.getCallingUser(). We check these
 * users for visibility before returning any phone accounts.
 */
public class PhoneAccountRegistrar {

    public static final PhoneAccountHandle NO_ACCOUNT_SELECTED =
            new PhoneAccountHandle(new ComponentName("null", "null"), "NO_ACCOUNT_SELECTED");

    public abstract static class Listener {
        public void onAccountsChanged(PhoneAccountRegistrar registrar) {}
        public void onDefaultOutgoingChanged(PhoneAccountRegistrar registrar) {}
        public void onSimCallManagerChanged(PhoneAccountRegistrar registrar) {}
        public void onPhoneAccountRegistered(PhoneAccountRegistrar registrar,
                                             PhoneAccountHandle handle) {}
        public void onPhoneAccountUnRegistered(PhoneAccountRegistrar registrar,
                                             PhoneAccountHandle handle) {}
        public void onPhoneAccountChanged(PhoneAccountRegistrar registrar,
                PhoneAccount phoneAccount) {}
    }

    /**
     * Receiver for detecting when a managed profile has been removed so that PhoneAccountRegistrar
     * can clean up orphan {@link PhoneAccount}s
     */
    private final BroadcastReceiver mManagedProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("PARbR.oR");
            try {
                synchronized (mLock) {
                    if (intent.getAction().equals(Intent.ACTION_MANAGED_PROFILE_REMOVED)) {
                        cleanupOrphanedPhoneAccounts();
                    }
                }
            } finally {
                Log.endSession();
            }
        }
    };

    public static final String FILE_NAME = "phone-account-registrar-state.xml";
    public static final String ICON_ERROR_MSG =
            "Icon cannot be written to memory. Try compressing or downsizing";
    @VisibleForTesting
    public static final int EXPECTED_STATE_VERSION = 9;
    public static final int MAX_PHONE_ACCOUNT_REGISTRATIONS = 10;
    public static final int MAX_PHONE_ACCOUNT_EXTRAS_KEY_PAIR_LIMIT = 100;
    public static final int MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT = 256;
    public static final int MAX_SCHEMES_PER_ACCOUNT = 10;

    /** Keep in sync with the same in SipSettings.java */
    private static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";

    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final AtomicFile mAtomicFile;
    private final Context mContext;
    private final UserManager mUserManager;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private final DefaultDialerCache mDefaultDialerCache;
    private final AppLabelProxy mAppLabelProxy;
    private final TelecomSystem.SyncRoot mLock;
    private State mState;
    private UserHandle mCurrentUserHandle;
    private final Set<String> mTestPhoneAccountPackageNameFilters;
    private interface PhoneAccountRegistrarWriteLock {}
    private final PhoneAccountRegistrarWriteLock mWriteLock =
            new PhoneAccountRegistrarWriteLock() {};
    private final FeatureFlags mTelephonyFeatureFlags;
    private final com.android.server.telecom.flags.FeatureFlags mTelecomFeatureFlags;

    @VisibleForTesting
    public PhoneAccountRegistrar(Context context, TelecomSystem.SyncRoot lock,
            DefaultDialerCache defaultDialerCache, AppLabelProxy appLabelProxy,
            FeatureFlags telephonyFeatureFlags,
            com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags) {
        this(context, lock, FILE_NAME, defaultDialerCache, appLabelProxy,
                telephonyFeatureFlags, telecomFeatureFlags);
    }

    @VisibleForTesting
    public PhoneAccountRegistrar(Context context, TelecomSystem.SyncRoot lock, String fileName,
            DefaultDialerCache defaultDialerCache, AppLabelProxy appLabelProxy,
            FeatureFlags telephonyFeatureFlags,
            com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags) {

        mAtomicFile = new AtomicFile(new File(context.getFilesDir(), fileName));

        mState = new State();
        mContext = context;
        mLock = lock;
        mUserManager = context.getSystemService(UserManager.class);
        mDefaultDialerCache = defaultDialerCache;
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAppLabelProxy = appLabelProxy;
        mCurrentUserHandle = Process.myUserHandle();
        mTelecomFeatureFlags = telecomFeatureFlags;
        mTestPhoneAccountPackageNameFilters = new HashSet<>();

        if (telephonyFeatureFlags != null) {
            mTelephonyFeatureFlags = telephonyFeatureFlags;
        } else {
            mTelephonyFeatureFlags =
                    new com.android.internal.telephony.flags.FeatureFlagsImpl();
        }

        // register context based receiver to clean up orphan phone accounts
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mManagedProfileReceiver, intentFilter);

        read();
    }

    /**
     * Retrieves the subscription id for a given phone account if it exists. Subscription ids
     * apply only to PSTN/SIM card phone accounts so all other accounts should not have a
     * subscription id.
     * @param accountHandle The handle for the phone account for which to retrieve the
     * subscription id.
     * @return The value of the subscription id or -1 if it does not exist or is not valid.
     */
    public int getSubscriptionIdForPhoneAccount(PhoneAccountHandle accountHandle) {
        PhoneAccount account = getPhoneAccountUnchecked(accountHandle);

        if (account != null && account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
            try {
                return mTelephonyManager.getSubscriptionId(accountHandle);
            } catch (UnsupportedOperationException ignored) {
                // Ignore; fall back to invalid below.
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Retrieves the default outgoing phone account supporting the specified uriScheme. Note that if
     * {@link #mCurrentUserHandle} does not have visibility into the current default, {@code null}
     * will be returned.
     *
     * @param uriScheme The URI scheme for the outgoing call.
     * @return The {@link PhoneAccountHandle} to use.
     */
    public PhoneAccountHandle getOutgoingPhoneAccountForScheme(String uriScheme,
            UserHandle userHandle) {
        final PhoneAccountHandle userSelected = getUserSelectedOutgoingPhoneAccount(userHandle);

        if (userSelected != null) {
            // If there is a default PhoneAccount, ensure it supports calls to handles with the
            // specified uriScheme.
            final PhoneAccount userSelectedAccount = getPhoneAccountUnchecked(userSelected);
            if (userSelectedAccount.supportsUriScheme(uriScheme)) {
                return userSelected;
            }
        }

        List<PhoneAccountHandle> outgoing = getCallCapablePhoneAccounts(uriScheme, false,
                userHandle, false);
        switch (outgoing.size()) {
            case 0:
                // There are no accounts, so there can be no default
                return null;
            case 1:
                // There is only one account, which is by definition the default.
                return outgoing.get(0);
            default:
                // There are multiple accounts with no selected default
                return null;
        }
    }

    public PhoneAccountHandle getOutgoingPhoneAccountForSchemeOfCurrentUser(String uriScheme) {
        return getOutgoingPhoneAccountForScheme(uriScheme, mCurrentUserHandle);
    }

    /**
     * @return The user-selected outgoing {@link PhoneAccount}, or null if it hasn't been set (or
     *      if it was set by another user).
     */
    @VisibleForTesting
    public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount(UserHandle userHandle) {
        if (userHandle == null) {
            return null;
        }
        DefaultPhoneAccountHandle defaultPhoneAccountHandle = mState.defaultOutgoingAccountHandles
                .get(userHandle);
        if (defaultPhoneAccountHandle == null) {
            return null;
        }
        // Make sure the account is still registered and owned by the user.
        PhoneAccount account = getPhoneAccount(defaultPhoneAccountHandle.phoneAccountHandle,
                userHandle);

        if (account != null) {
            return defaultPhoneAccountHandle.phoneAccountHandle;
        }

        Log.v(this,
                "getUserSelectedOutgoingPhoneAccount: defaultPhoneAccountHandle"
                        + ".phoneAccountHandle=[%s] is not registered or owned by %s"
                , defaultPhoneAccountHandle.phoneAccountHandle, userHandle);

        return null;
    }

    /**
     * @return The {@link DefaultPhoneAccountHandle} containing the user-selected default calling
     * account and group Id for the {@link UserHandle} specified.
     */
    private DefaultPhoneAccountHandle getUserSelectedDefaultPhoneAccount(UserHandle userHandle) {
        if (userHandle == null) {
            return null;
        }
        DefaultPhoneAccountHandle defaultPhoneAccountHandle = mState.defaultOutgoingAccountHandles
                .get(userHandle);
        if (defaultPhoneAccountHandle == null) {
            return null;
        }

        return defaultPhoneAccountHandle;
    }

    /**
     * @return The currently registered PhoneAccount in Telecom that has the same group Id.
     */
    private PhoneAccount getPhoneAccountByGroupId(String groupId, ComponentName groupComponentName,
            UserHandle userHandle, PhoneAccountHandle excludePhoneAccountHandle) {
        if (groupId == null || groupId.isEmpty() || userHandle == null) {
            return null;
        }
        // Get the PhoneAccount with the same group Id (and same ComponentName) that is not the
        // newAccount that was just added
        List<PhoneAccount> accounts = getAllPhoneAccounts(userHandle, false).stream()
                .filter(account -> groupId.equals(account.getGroupId()) &&
                        !account.getAccountHandle().equals(excludePhoneAccountHandle) &&
                        Objects.equals(account.getAccountHandle().getComponentName(),
                                groupComponentName))
                .collect(Collectors.toList());
        // There should be one or no PhoneAccounts with the same group Id
        if (accounts.size() > 1) {
            Log.w(this, "Found multiple PhoneAccounts registered to the same Group Id!");
        }
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    /**
     * Sets the phone account with which to place all calls by default. Set by the user
     * within phone settings.
     */
    public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle,
            UserHandle userHandle) {
        if (userHandle == null) {
            return;
        }
        DefaultPhoneAccountHandle currentDefaultInfo =
                mState.defaultOutgoingAccountHandles.get(userHandle);
        PhoneAccountHandle currentDefaultPhoneAccount = currentDefaultInfo == null ? null :
                currentDefaultInfo.phoneAccountHandle;

        Log.i(this, "setUserSelectedOutgoingPhoneAccount: %s", accountHandle);

        if (Objects.equals(currentDefaultPhoneAccount, accountHandle)) {
            Log.i(this, "setUserSelectedOutgoingPhoneAccount: "
                    + "no change in default phoneAccountHandle.  current is same as new.");
            return;
        }

        boolean isSimAccount = false;
        if (accountHandle == null) {
            // Asking to clear the default outgoing is a valid request
            mState.defaultOutgoingAccountHandles.remove(userHandle);
        } else {
            PhoneAccount account = getPhoneAccount(accountHandle, userHandle);
            if (account == null) {
                Log.w(this, "Trying to set nonexistent default outgoing %s",
                        accountHandle);
                return;
            }

            if (!account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)) {
                Log.w(this, "Trying to set non-call-provider default outgoing %s",
                        accountHandle);
                return;
            }

            if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                // If the account selected is a SIM account, propagate down to the subscription
                // record.
                isSimAccount = true;
            }

            mState.defaultOutgoingAccountHandles
                    .put(userHandle, new DefaultPhoneAccountHandle(userHandle, accountHandle,
                            account.getGroupId()));
        }

        // Potentially update the default voice subid in SubscriptionManager so that Telephony and
        // Telecom are in sync.
        int newSubId = accountHandle == null ? SubscriptionManager.INVALID_SUBSCRIPTION_ID :
                getSubscriptionIdForPhoneAccount(accountHandle);
        if (Flags.onlyUpdateTelephonyOnValidSubIds()) {
            if (shouldUpdateTelephonyDefaultVoiceSubId(accountHandle, isSimAccount, newSubId)) {
                updateDefaultVoiceSubId(newSubId, accountHandle);
            } else {
                Log.i(this, "setUserSelectedOutgoingPhoneAccount: %s is not a sub", accountHandle);
            }
        } else {
            if (isSimAccount || accountHandle == null) {
                updateDefaultVoiceSubId(newSubId, accountHandle);
            } else {
                Log.i(this, "setUserSelectedOutgoingPhoneAccount: %s is not a sub", accountHandle);
            }
        }

        write();
        fireDefaultOutgoingChanged();
    }

    private void updateDefaultVoiceSubId(int newSubId, PhoneAccountHandle accountHandle){
        try {
            int currentVoiceSubId = mSubscriptionManager.getDefaultVoiceSubscriptionId();
            if (newSubId != currentVoiceSubId) {
                Log.i(this, "setUserSelectedOutgoingPhoneAccount: update voice sub; "
                        + "account=%s, subId=%d", accountHandle, newSubId);
                mSubscriptionManager.setDefaultVoiceSubscriptionId(newSubId);
            } else {
                Log.i(this, "setUserSelectedOutgoingPhoneAccount: no change to voice sub");
            }
        } catch (UnsupportedOperationException uoe) {
            Log.w(this, "setUserSelectedOutgoingPhoneAccount: no telephony");
        }
    }

    // This helper is important for CTS testing.  [PhoneAccount]s created by Telecom in CTS are
    // assigned a  subId value of INVALID_SUBSCRIPTION_ID (-1) by Telephony.  However, when
    // Telephony has a default outgoing calling voice account of -1, that translates to no default
    // account (user should be prompted to select an acct when making MOs).  In order to avoid
    // Telephony clearing out the newly changed default [PhoneAccount] in Telecom, Telephony should
    // not be updated. This situation will never occur in production since [PhoneAccount]s in
    // production are assigned non-negative subId values.
    private boolean shouldUpdateTelephonyDefaultVoiceSubId(PhoneAccountHandle phoneAccountHandle,
            boolean isSimAccount, int newSubId) {
        // user requests no call preference
        if (phoneAccountHandle == null) {
            return true;
        }
        // do not update Telephony if the newSubId is invalid
        if (newSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.w(this, "shouldUpdateTelephonyDefaultVoiceSubId: "
                            + "invalid subId scenario, not updating Telephony. "
                            + "phoneAccountHandle=[%s], isSimAccount=[%b], newSubId=[%s]",
                    phoneAccountHandle, isSimAccount, newSubId);
            return false;
        }
        return isSimAccount;
    }

    boolean isUserSelectedSmsPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            return getSubscriptionIdForPhoneAccount(accountHandle) ==
                    SubscriptionManager.getDefaultSmsSubscriptionId();
        } catch (UnsupportedOperationException uoe) {
            Log.w(this, "isUserSelectedSmsPhoneAccount: no telephony");
            return false;
        }
    }

    public ComponentName getSystemSimCallManagerComponent() {
        return getSystemSimCallManagerComponent(SubscriptionManager.getDefaultSubscriptionId());
    }

    public ComponentName getSystemSimCallManagerComponent(int subId) {
        String defaultSimCallManager = null;
        try {
            CarrierConfigManager configManager = (CarrierConfigManager) mContext.getSystemService(
                    Context.CARRIER_CONFIG_SERVICE);
            if (configManager == null) return null;
            PersistableBundle configBundle = configManager.getConfigForSubId(subId);
            if (configBundle != null) {
                defaultSimCallManager = configBundle.getString(
                        CarrierConfigManager.KEY_DEFAULT_SIM_CALL_MANAGER_STRING);
            }
        } catch (UnsupportedOperationException ignored) {
            Log.w(this, "getSystemSimCallManagerComponent: no telephony");
            // Fall through to empty below.
        }
        return TextUtils.isEmpty(defaultSimCallManager)
                ?  null : ComponentName.unflattenFromString(defaultSimCallManager);
    }

    public PhoneAccountHandle getSimCallManagerOfCurrentUser() {
        return getSimCallManager(mCurrentUserHandle);
    }

    /**
     * Returns the {@link PhoneAccountHandle} corresponding to the SIM Call Manager associated with
     * the default Telephony Subscription ID (see
     * {@link SubscriptionManager#getDefaultSubscriptionId()}). SIM Call Manager returned
     * corresponds to the following priority order:
     * 1. If a SIM Call Manager {@link PhoneAccount} is registered for the same package as the
     * default dialer, then that one is returned.
     * 2. If there is a SIM Call Manager {@link PhoneAccount} registered which matches the
     * carrier configuration's default, then that one is returned.
     * 3. Otherwise, we return null.
     */
    public PhoneAccountHandle getSimCallManager(UserHandle userHandle) {
        return getSimCallManager(SubscriptionManager.getDefaultSubscriptionId(), userHandle);
    }

    /**
     * Queries the SIM call manager associated with a specific subscription ID.
     *
     * @see #getSimCallManager(UserHandle) for more information.
     */
    public PhoneAccountHandle getSimCallManager(int subId, UserHandle userHandle) {

        // Get the default dialer in case it has a connection manager associated with it.
        String dialerPackage = mDefaultDialerCache
                .getDefaultDialerApplication(userHandle.getIdentifier());

        // Check carrier config.
        ComponentName systemSimCallManagerComponent = getSystemSimCallManagerComponent(subId);

        PhoneAccountHandle dialerSimCallManager = null;
        PhoneAccountHandle systemSimCallManager = null;

        if (!TextUtils.isEmpty(dialerPackage) || systemSimCallManagerComponent != null) {
            // loop through and look for any connection manager in the same package.
            List<PhoneAccountHandle> allSimCallManagers = getPhoneAccountHandles(
                    PhoneAccount.CAPABILITY_CONNECTION_MANAGER, null, null,
                    true /* includeDisabledAccounts */, userHandle, false);
            for (PhoneAccountHandle accountHandle : allSimCallManagers) {
                ComponentName component = accountHandle.getComponentName();

                // Store the system connection manager if found
                if (systemSimCallManager == null
                        && Objects.equals(component, systemSimCallManagerComponent)
                        && !resolveComponent(accountHandle).isEmpty()) {
                    systemSimCallManager = accountHandle;

                // Store the dialer connection manager if found
                } else if (dialerSimCallManager == null
                        && Objects.equals(component.getPackageName(), dialerPackage)
                        && !resolveComponent(accountHandle).isEmpty()) {
                    dialerSimCallManager = accountHandle;
                }
            }
        }

        PhoneAccountHandle retval = dialerSimCallManager != null ?
                dialerSimCallManager : systemSimCallManager;
        Log.i(this, "getSimCallManager: SimCallManager for subId %d queried, returning: %s",
                subId, retval);

        return retval;
    }

    /**
     * Loops through all SIM accounts ({@link #getSimPhoneAccounts}) and returns those with SIM call
     * manager components specified in carrier config that match {@code simCallManagerHandle}.
     *
     * <p>Note that this will return handles even when {@code simCallManagerHandle} has not yet been
     * registered or was recently unregistered.
     *
     * <p>If the given {@code simCallManagerHandle} is not the SIM call manager for any active SIMs,
     * returns an empty list.
     */
    public @NonNull List<PhoneAccountHandle> getSimPhoneAccountsFromSimCallManager(
            @NonNull PhoneAccountHandle simCallManagerHandle) {
        List<PhoneAccountHandle> matchingSimHandles = new ArrayList<>();
        for (PhoneAccountHandle simHandle :
                getSimPhoneAccounts(simCallManagerHandle.getUserHandle())) {
            ComponentName simCallManager =
                    getSystemSimCallManagerComponent(getSubscriptionIdForPhoneAccount(simHandle));
            if (simCallManager == null) continue;
            if (simCallManager.equals(simCallManagerHandle.getComponentName())) {
                matchingSimHandles.add(simHandle);
            }
        }
        return matchingSimHandles;
    }

    /**
     * Sets a filter for which {@link PhoneAccount}s will be returned from
     * {@link #filterRestrictedPhoneAccounts(List)}. If non-null, only {@link PhoneAccount}s
     * with the package name packageNameFilter will be returned. If null, no filter is set.
     * @param packageNameFilter The package name that will be used to filter only
     * {@link PhoneAccount}s with the same package name.
     */
    public void setTestPhoneAccountPackageNameFilter(String packageNameFilter) {
        mTestPhoneAccountPackageNameFilters.clear();
        if (packageNameFilter == null) {
            return;
        }
        String [] pkgNamesFilter = packageNameFilter.split(",");
        mTestPhoneAccountPackageNameFilters.addAll(Arrays.asList(pkgNamesFilter));
        StringBuilder pkgNames = new StringBuilder();
        for (int i = 0; i < pkgNamesFilter.length; i++) {
            pkgNames.append(pkgNamesFilter[i])
                    .append(i != pkgNamesFilter.length - 1 ? ", " : ".");
        }
        Log.i(this, "filter set for PhoneAccounts, packageNames: %s", pkgNames.toString());
    }

    /**
     * Filter the given {@link List<PhoneAccount>} and keep only {@link PhoneAccount}s that have the
     * #mTestPhoneAccountPackageNameFilters.
     * @param accounts List of {@link PhoneAccount}s to filter.
     * @return new list of filtered {@link PhoneAccount}s.
     */
    public List<PhoneAccount> filterRestrictedPhoneAccounts(List<PhoneAccount> accounts) {
        if (mTestPhoneAccountPackageNameFilters.isEmpty()) {
            return new ArrayList<>(accounts);
        }
        // Remove all PhoneAccounts that do not have the same package name (prefix) as the filter.
        return accounts.stream().filter(account -> mTestPhoneAccountPackageNameFilters
                .contains(account.getAccountHandle().getComponentName().getPackageName()))
                .collect(Collectors.toList());
    }

    /**
     * If it is a outgoing call, sim call manager associated with the target phone account of the
     * call is returned (if one exists).
     * Otherwise, we return the sim call manager of the user associated with the
     * target phone account.
     * @return phone account handle of sim call manager based on the ongoing call.
     */
    @Nullable
    public PhoneAccountHandle getSimCallManagerFromCall(Call call) {
        if (call == null) {
            return null;
        }
        UserHandle userHandle = call.getAssociatedUser();
        PhoneAccountHandle targetPhoneAccount = call.getTargetPhoneAccount();
        Log.d(this, "getSimCallManagerFromCall: callId=%s, targetPhac=%s",
                call.getId(), targetPhoneAccount);
        return getSimCallManagerFromHandle(targetPhoneAccount,userHandle);
    }

    /**
     * Given a target phone account and user, determines the sim call manager (if any) which is
     * associated with that {@link PhoneAccountHandle}.
     * @param targetPhoneAccount The target phone account to check.
     * @param userHandle The user handle.
     * @return The {@link PhoneAccountHandle} of the connection manager.
     */
    public PhoneAccountHandle getSimCallManagerFromHandle(PhoneAccountHandle targetPhoneAccount,
            UserHandle userHandle) {
        // First, check if the specified target phone account handle is a connection manager; if
        // it is, then just return it.
        PhoneAccount phoneAccount = getPhoneAccountUnchecked(targetPhoneAccount);
        if (phoneAccount != null
                && phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)) {
            return targetPhoneAccount;
        }

        int subId = getSubscriptionIdForPhoneAccount(targetPhoneAccount);
        if (SubscriptionManager.isValidSubscriptionId(subId)
                 && subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            PhoneAccountHandle callManagerHandle = getSimCallManager(subId, userHandle);
            Log.d(this, "getSimCallManagerFromHandle: targetPhac=%s, subId=%d, scm=%s",
                    targetPhoneAccount, subId, callManagerHandle);
            return callManagerHandle;
        } else {
            PhoneAccountHandle callManagerHandle = getSimCallManager(userHandle);
            Log.d(this, "getSimCallManagerFromHandle: targetPhac=%s, subId(d)=%d, scm=%s",
                    targetPhoneAccount, subId, callManagerHandle);
            return callManagerHandle;
        }
    }

    /**
     * Update the current UserHandle to track when users are switched. This will allow the
     * PhoneAccountRegistar to self-filter the PhoneAccounts to make sure we don't leak anything
     * across users.
     * We cannot simply check the calling user because that would always return the primary user for
     * all invocations originating with the system process.
     *
     * @param userHandle The {@link UserHandle}, as delivered by
     *          {@link Intent#ACTION_USER_SWITCHED}.
     */
    public void setCurrentUserHandle(UserHandle userHandle) {
        if (userHandle == null) {
            Log.d(this, "setCurrentUserHandle, userHandle = null");
            userHandle = Process.myUserHandle();
        }
        Log.d(this, "setCurrentUserHandle, %s", userHandle);
        mCurrentUserHandle = userHandle;
    }

    /**
     * @return {@code true} if the phone account was successfully enabled/disabled, {@code false}
     *         otherwise.
     */
    public boolean enablePhoneAccount(PhoneAccountHandle accountHandle, boolean isEnabled) {
        PhoneAccount account = getPhoneAccountUnchecked(accountHandle);
        Log.i(this, "Phone account %s %s.", accountHandle, isEnabled ? "enabled" : "disabled");
        if (account == null) {
            Log.w(this, "Could not find account to enable: " + accountHandle);
            return false;
        } else if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
            // We never change the enabled state of SIM-based accounts.
            Log.w(this, "Could not change enable state of SIM account: " + accountHandle);
            return false;
        }

        if (account.isEnabled() != isEnabled) {
            account.setIsEnabled(isEnabled);
            if (!isEnabled) {
                // If the disabled account is the default, remove it.
                removeDefaultPhoneAccountHandle(accountHandle);
            }
            write();
            fireAccountsChanged();
        }
        return true;
    }

    private void removeDefaultPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        Iterator<Map.Entry<UserHandle, DefaultPhoneAccountHandle>> iterator =
                mState.defaultOutgoingAccountHandles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UserHandle, DefaultPhoneAccountHandle> entry = iterator.next();
            if (phoneAccountHandle.equals(entry.getValue().phoneAccountHandle)) {
                iterator.remove();
            }
        }
    }

    private boolean isMatchedUser(PhoneAccount account, UserHandle userHandle) {
        if (account == null) {
            return false;
        }

        if (userHandle == null) {
            Log.w(this, "userHandle is null in isVisibleForUser");
            return false;
        }

        UserHandle phoneAccountUserHandle = account.getAccountHandle().getUserHandle();
        if (phoneAccountUserHandle == null) {
            return false;
        }

        return phoneAccountUserHandle.equals(userHandle);
    }

    private boolean isVisibleForUser(PhoneAccount account, UserHandle userHandle,
            boolean acrossProfiles) {
        if (account == null) {
            return false;
        }

        if (userHandle == null) {
            Log.w(this, "userHandle is null in isVisibleForUser");
            return false;
        }

        // If this PhoneAccount has CAPABILITY_MULTI_USER, it should be visible to all users and
        // all profiles. Only Telephony and SIP accounts should have this capability.
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_MULTI_USER)) {
            return true;
        }

        UserHandle phoneAccountUserHandle = account.getAccountHandle().getUserHandle();
        if (phoneAccountUserHandle == null) {
            return false;
        }

        if (mCurrentUserHandle == null) {
            // In case we need to have emergency phone calls from the lock screen.
            Log.d(this, "Current user is null; assuming true");
            return true;
        }

        if (acrossProfiles) {
            UserManager um = mContext.getSystemService(UserManager.class);
            return mTelecomFeatureFlags.telecomResolveHiddenDependencies()
                    ? um.isSameProfileGroup(userHandle, phoneAccountUserHandle)
                    : um.isSameProfileGroup(userHandle.getIdentifier(),
                            phoneAccountUserHandle.getIdentifier());
        } else {
            return phoneAccountUserHandle.equals(userHandle);
        }
    }

    private List<ResolveInfo> resolveComponent(PhoneAccountHandle phoneAccountHandle) {
        return resolveComponent(phoneAccountHandle.getComponentName(),
                phoneAccountHandle.getUserHandle());
    }

    private List<ResolveInfo> resolveComponent(ComponentName componentName,
            UserHandle userHandle) {
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(ConnectionService.SERVICE_INTERFACE);
        intent.setComponent(componentName);
        try {
            if (userHandle != null) {
                return pm.queryIntentServicesAsUser(intent, 0, userHandle.getIdentifier());
            } else {
                return pm.queryIntentServices(intent, 0);
            }
        } catch (SecurityException e) {
            Log.e(this, e, "%s is not visible for the calling user", componentName);
            return Collections.EMPTY_LIST;
        }
    }

    /**
     * Retrieves a list of all {@link PhoneAccountHandle}s registered.
     * Only returns accounts which are enabled.
     *
     * @return The list of {@link PhoneAccountHandle}s.
     */
    public List<PhoneAccountHandle> getAllPhoneAccountHandles(UserHandle userHandle,
            boolean crossUserAccess) {
        return getPhoneAccountHandles(0, null, null, false, userHandle, crossUserAccess, true);
    }

    public List<PhoneAccount> getAllPhoneAccounts(UserHandle userHandle, boolean crossUserAccess) {
        return getPhoneAccounts(0, null, null, false, mCurrentUserHandle, crossUserAccess, true);
    }

    /**
     * Retrieves a list of all phone account call provider phone accounts supporting the
     * specified URI scheme.
     *
     * @param uriScheme The URI scheme.
     * @param includeDisabledAccounts {@code} if disabled {@link PhoneAccount}s should be included
     *      in the results.
     * @param userHandle The {@link UserHandle} to retrieve the {@link PhoneAccount}s for.
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts(
            String uriScheme, boolean includeDisabledAccounts,
            UserHandle userHandle, boolean crossUserAccess) {
        return getCallCapablePhoneAccounts(uriScheme, includeDisabledAccounts, userHandle,
                0 /* capabilities */, PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY,
                crossUserAccess);
    }

    /**
     * Retrieves a list of all phone account call provider phone accounts supporting the
     * specified URI scheme.
     *
     * @param uriScheme The URI scheme.
     * @param includeDisabledAccounts {@code} if disabled {@link PhoneAccount}s should be included
     *      in the results.
     * @param userHandle The {@link UserHandle} to retrieve the {@link PhoneAccount}s for.
     * @param capabilities Extra {@link PhoneAccount} capabilities which matching
     *      {@link PhoneAccount}s must have.
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts(
            String uriScheme, boolean includeDisabledAccounts, UserHandle userHandle,
            int capabilities, int excludedCapabilities, boolean crossUserAccess) {
        return getPhoneAccountHandles(
                PhoneAccount.CAPABILITY_CALL_PROVIDER | capabilities,
                excludedCapabilities /*excludedCapabilities*/,
                uriScheme, null, includeDisabledAccounts, userHandle, crossUserAccess);
    }

    /**
     * Retrieves a list of all phone accounts which have
     * {@link PhoneAccount#CAPABILITY_SELF_MANAGED}.
     * <p>
     * Returns only the {@link PhoneAccount}s which are enabled as self-managed accounts are
     * automatically enabled by default (see {@link #registerPhoneAccount(PhoneAccount)}).
     *
     * @param userHandle User handle of phone account owner.
     * @return The phone account handles.
     */
    public List<PhoneAccountHandle> getSelfManagedPhoneAccounts(UserHandle userHandle) {
        return getPhoneAccountHandles(
                PhoneAccount.CAPABILITY_SELF_MANAGED,
                PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY /* excludedCapabilities */,
                null /* uriScheme */, null /* packageName */, false /* includeDisabledAccounts */,
                userHandle, false);
    }

    /**
     * Retrieves a list of all the SIM-based phone accounts.
     */
    public List<PhoneAccountHandle> getSimPhoneAccounts(UserHandle userHandle) {
        return getPhoneAccountHandles(
                PhoneAccount.CAPABILITY_CALL_PROVIDER | PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION,
                null, null, false, userHandle, false);
    }

    public List<PhoneAccountHandle> getSimPhoneAccountsOfCurrentUser() {
        return getSimPhoneAccounts(mCurrentUserHandle);
    }

        /**
         * Retrieves a list of all phone accounts registered by a specified package.
         *
         * @param packageName The name of the package that registered the phone accounts.
         * @return The phone account handles.
         */
    public List<PhoneAccountHandle> getPhoneAccountsForPackage(String packageName,
            UserHandle userHandle) {
        return getPhoneAccountHandles(0, null, packageName, false, userHandle, false);
    }


    /**
     * includes disabled, includes crossUserAccess
     */
    public List<PhoneAccountHandle> getAllPhoneAccountHandlesForPackage(UserHandle userHandle,
            String packageName) {
        return getPhoneAccountHandles(0, null, packageName, true /* includeDisabled */, userHandle,
                true /* crossUserAccess */, true);
    }

    /**
     * Retrieves a list of all {@link PhoneAccount#CAPABILITY_SELF_MANAGED} phone accounts
     * registered by a specified package.
     *
     * @param packageName The name of the package that registered the phone accounts.
     * @return The self-managed phone account handles for the given package.
     */
    public List<PhoneAccountHandle> getSelfManagedPhoneAccountsForPackage(String packageName,
            UserHandle userHandle) {
        List<PhoneAccountHandle> phoneAccountsHandles = new ArrayList<>();
        for (PhoneAccountHandle pah : getPhoneAccountsForPackage(packageName,
                userHandle)) {
            if (isSelfManagedPhoneAccount(pah)) {
                phoneAccountsHandles.add(pah);
            }
        }
        return phoneAccountsHandles;
    }

    /**
     * Determines if a {@link PhoneAccountHandle} is for a self-managed {@link ConnectionService}.
     * @param handle The handle.
     * @return {@code true} if for a self-managed {@link ConnectionService}, {@code false}
     * otherwise.
     */
    public boolean isSelfManagedPhoneAccount(@NonNull PhoneAccountHandle handle) {
        PhoneAccount account = getPhoneAccountUnchecked(handle);
        if (account == null) {
            return false;
        }

        return account.isSelfManaged();
    }

    /**
     * Performs checks before calling addOrReplacePhoneAccount(PhoneAccount)
     *
     * @param account The {@code PhoneAccount} to add or replace.
     * @throws SecurityException        if package does not have BIND_TELECOM_CONNECTION_SERVICE
     *                                  permission
     * @throws IllegalArgumentException if MAX_PHONE_ACCOUNT_REGISTRATIONS are reached
     * @throws IllegalArgumentException if MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT is reached
     * @throws IllegalArgumentException if writing the Icon to memory will cause an Exception
     */
    public void registerPhoneAccount(PhoneAccount account) {
        // Enforce the requirement that a connection service for a phone account has the correct
        // permission.
        if (!hasTransactionalCallCapabilities(account) &&
                !phoneAccountRequiresBindPermission(account.getAccountHandle())) {
            Log.w(this,
                    "Phone account %s does not have BIND_TELECOM_CONNECTION_SERVICE permission.",
                    account.getAccountHandle());
            throw new SecurityException("Registering a PhoneAccount requires either: "
                    + "(1) The Service definition requires that the ConnectionService is guarded"
                    + " with the BIND_TELECOM_CONNECTION_SERVICE, which can be defined using the"
                    + " android:permission tag as part of the Service definition. "
                    + "(2) The PhoneAccount capability called"
                    + " CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS.");
        }
        enforceCharacterLimit(account);
        enforceIconSizeLimit(account);
        if (mTelecomFeatureFlags.unregisterUnresolvableAccounts()) {
            enforcePhoneAccountTargetService(account);
        }
        enforceMaxPhoneAccountLimit(account);
        if (mTelephonyFeatureFlags.simultaneousCallingIndications()) {
            enforceSimultaneousCallingRestrictionLimit(account);
        }
        addOrReplacePhoneAccount(account);
    }

    /**
     * This method ensures that {@link PhoneAccount}s that have the {@link
     * PhoneAccount#CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS} capability are not
     * backed by a {@link ConnectionService}
     *
     * @param account enforce the check on
     */
    private void enforcePhoneAccountTargetService(PhoneAccount account) {
        if (phoneAccountRequiresBindPermission(account.getAccountHandle()) &&
                hasTransactionalCallCapabilities(account)) {
            throw new IllegalArgumentException(
                    "Error, the PhoneAccount you are registering has"
                            + " CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS and the"
                            + " PhoneAccountHandle's ComponentName#ClassName points to a"
                            + " ConnectionService class.  Either remove the capability or use a"
                            + " different ClassName in the PhoneAccountHandle.");
        }
    }

    /**
     * Enforce an upper bound on the number of PhoneAccount's a package can register.
     * Most apps should only require 1-2.  * Include disabled accounts.
     *
     * @param account to enforce check on
     * @throws IllegalArgumentException if MAX_PHONE_ACCOUNT_REGISTRATIONS are reached
     */
    private void enforceMaxPhoneAccountLimit(@NonNull PhoneAccount account) {
        int numOfAcctsRegisteredForPackage = mTelecomFeatureFlags.unregisterUnresolvableAccounts()
                ? cleanupAndGetVerifiedAccounts(account).size()
                : getPhoneAccountHandles(
                        0/* capabilities */,
                        null /* uriScheme */,
                        account.getAccountHandle().getComponentName().getPackageName(),
                        true /* includeDisabled */,
                        account.getAccountHandle().getUserHandle(),
                        false /* crossUserAccess */).size();
        // enforce the max phone account limit for the application registering accounts
        if (numOfAcctsRegisteredForPackage >= MAX_PHONE_ACCOUNT_REGISTRATIONS) {
            EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                    "enforceMaxPhoneAccountLimit");
            throw new IllegalArgumentException(
                    "Error, cannot register phone account " + account.getAccountHandle()
                            + " because the limit, " + MAX_PHONE_ACCOUNT_REGISTRATIONS
                            + ", has been reached");
        }
    }

    @VisibleForTesting
    public List<PhoneAccount> getRegisteredAccountsForPackageName(String packageName,
            UserHandle userHandle) {
        if (packageName == null) {
            return new ArrayList<>();
        }
        List<PhoneAccount> accounts = new ArrayList<>(mState.accounts.size());
        for (PhoneAccount m : mState.accounts) {
            PhoneAccountHandle handle = m.getAccountHandle();
            if (!packageName.equals(handle.getComponentName().getPackageName())) {
                // Not the right package name; skip this one.
                continue;
            }
            // Do not count accounts registered under different users on the device. Otherwise, an
            // application can only have MAX_PHONE_ACCOUNT_REGISTRATIONS across all users. If the
            // DUT has multiple users, they should each get to register 10 accounts. Also, 3rd
            // party applications cannot create new UserHandles without highly privileged
            // permissions.
            if (!isVisibleForUser(m, userHandle, false)) {
                // Account is not visible for the current user; skip this one.
                continue;
            }
            accounts.add(m);
        }
        return accounts;
    }

    /**
     * Unregister {@link ConnectionService} accounts that no longer have a resolvable Service. This
     * means the Service has been disabled or died.  Skip the verification for transactional
     * accounts.
     *
     * @param newAccount being registered
     * @return all the verified accounts. These accounts are now guaranteed to be backed by a
     * {@link ConnectionService} or do not need one (transactional accounts).
     */
    @VisibleForTesting
    public List<PhoneAccount> cleanupAndGetVerifiedAccounts(PhoneAccount newAccount) {
        ArrayList<PhoneAccount> verifiedAccounts = new ArrayList<>();
        List<PhoneAccount> unverifiedAccounts = getRegisteredAccountsForPackageName(
                newAccount.getAccountHandle().getComponentName().getPackageName(),
                newAccount.getAccountHandle().getUserHandle());
        for (PhoneAccount account : unverifiedAccounts) {
            PhoneAccountHandle handle = account.getAccountHandle();
            if (/* skip for transactional accounts since they don't require a ConnectionService */
                    !hasTransactionalCallCapabilities(account) &&
                    /* check if the {@link ConnectionService} has been disabled or can longer be
                       found */ resolveComponent(handle).isEmpty()) {
                Log.i(this, " cAGVA: Cannot resolve the ConnectionService for"
                        + " handle=[%s]; unregistering account", handle);
                unregisterPhoneAccount(handle);
            } else {
                verifiedAccounts.add(account);
            }
        }
        return verifiedAccounts;
    }

    /**
     * determine if there will be an issue writing the icon to memory
     *
     * @param account to enforce check on
     * @throws IllegalArgumentException if writing the Icon to memory will cause an Exception
     */
    @VisibleForTesting
    public void enforceIconSizeLimit(PhoneAccount account) {
        if (account.getIcon() == null) {
            return;
        }
        String text = "";
        // convert the icon into a Base64 String
        try {
            text = XmlSerialization.writeIconToBase64String(account.getIcon());
        } catch (IOException e) {
            EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                    "enforceIconSizeLimit");
            throw new IllegalArgumentException(ICON_ERROR_MSG);
        }
        // enforce the max bytes check in com.android.modules.utils.FastDataOutput#writeUTF(string)
        try {
            final int len = (int) ModifiedUtf8.countBytes(text, false);
            if (len > 65_535 /* MAX_UNSIGNED_SHORT */) {
                EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                        "enforceIconSizeLimit");
                throw new IllegalArgumentException(ICON_ERROR_MSG);
            }
        } catch (IOException e) {
            EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                    "enforceIconSizeLimit");
            throw new IllegalArgumentException(ICON_ERROR_MSG);
        }
    }

    /**
     * All {@link PhoneAccount} and{@link PhoneAccountHandle} String and Char-Sequence fields
     * should be restricted to character limit of MAX_PHONE_ACCOUNT_CHAR_LIMIT to prevent exceptions
     * when writing large character streams to XML-Serializer.
     *
     * @param account to enforce character limit checks on
     * @throws IllegalArgumentException if MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT reached
     */
    public void enforceCharacterLimit(PhoneAccount account) {
        if (account == null) {
            return;
        }
        PhoneAccountHandle handle = account.getAccountHandle();

        String[] fields =
                {"Package Name", "Class Name", "PhoneAccountHandle Id", "Label", "ShortDescription",
                        "GroupId", "Address", "SubscriptionAddress"};
        CharSequence[] args = {handle.getComponentName().getPackageName(),
                handle.getComponentName().getClassName(), handle.getId(), account.getLabel(),
                account.getShortDescription(), account.getGroupId(),
                (account.getAddress() != null ? account.getAddress().toString() : ""),
                (account.getSubscriptionAddress() != null ?
                        account.getSubscriptionAddress().toString() : "")};

        for (int i = 0; i < fields.length; i++) {
            if (args[i] != null && args[i].length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) {
                EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                        "enforceCharacterLimit");
                throw new IllegalArgumentException("The PhoneAccount or PhoneAccountHandle ["
                        + fields[i] + "] field has an invalid character count. PhoneAccount and "
                        + "PhoneAccountHandle String and Char-Sequence fields are limited to "
                        + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT + " characters.");
            }
        }

        // Enforce limits on the URI Schemes provided
        enforceLimitsOnSchemes(account);

        // Enforce limit on the PhoneAccount#mExtras
        Bundle extras = account.getExtras();
        if (extras != null) {
            if (extras.keySet().size() > MAX_PHONE_ACCOUNT_EXTRAS_KEY_PAIR_LIMIT) {
                EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                        "enforceCharacterLimit");
                throw new IllegalArgumentException("The PhoneAccount#mExtras is limited to " +
                        MAX_PHONE_ACCOUNT_EXTRAS_KEY_PAIR_LIMIT + " (key,value) pairs.");
            }

            for (String key : extras.keySet()) {
                Object value = extras.get(key);

                if ((key != null && key.length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) ||
                        (value instanceof String &&
                                ((String) value).length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT)) {
                    EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                            "enforceCharacterLimit");
                    throw new IllegalArgumentException("The PhoneAccount#mExtras contains a String"
                            + " key or value that has an invalid character count. PhoneAccount and "
                            + "PhoneAccountHandle String and Char-Sequence fields are limited to "
                            + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT + " characters.");
                }
            }
        }
    }

    /**
     * Enforce size limits on the simultaneous calling restriction of a PhoneAccount.
     * If a PhoneAccount has a simultaneous calling restriction on it, enforce the following: the
     * number of PhoneAccountHandles in the Set can not exceed the per app restriction on
     * PhoneAccounts registered and each PhoneAccountHandle's fields must not exceed the per field
     * character limit.
     * @param account The PhoneAccount to enforce simultaneous calling restrictions on.
     * @throws IllegalArgumentException if the PhoneAccount exceeds size limits.
     */
    public void enforceSimultaneousCallingRestrictionLimit(@NonNull PhoneAccount account) {
        if (!account.hasSimultaneousCallingRestriction()) return;
        Set<PhoneAccountHandle> restrictions = account.getSimultaneousCallingRestriction();
        if (restrictions.size() > MAX_PHONE_ACCOUNT_REGISTRATIONS) {
            throw new IllegalArgumentException("Can not register a PhoneAccount with a number"
                    + "of simultaneous calling restrictions that is greater than "
                    + MAX_PHONE_ACCOUNT_REGISTRATIONS);
        }
        for (PhoneAccountHandle handle : restrictions) {
            ComponentName component = handle.getComponentName();
            if (component.getPackageName().length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) {
                throw new IllegalArgumentException("A PhoneAccountHandle added as part of "
                        + "a simultaneous calling restriction has a package name that has exceeded "
                        + "the character limit of " + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT);
            }
            if (component.getClassName().length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) {
                throw new IllegalArgumentException("A PhoneAccountHandle added as part of "
                        + "a simultaneous calling restriction has a class name that has exceeded "
                        + "the character limit of " + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT);
            }
            if (handle.getId().length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) {
                throw new IllegalArgumentException("A PhoneAccountHandle added as part of "
                        + "a simultaneous calling restriction has an ID that has exceeded "
                        + "the character limit of " + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT);
            }
        }
    }

    /**
     * Enforce a character limit on all PA and PAH string or char-sequence fields.
     *
     * @param account to enforce check on
     * @throws IllegalArgumentException if MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT reached
     */
    @VisibleForTesting
    public void enforceLimitsOnSchemes(@NonNull PhoneAccount account) {
        List<String> schemes = account.getSupportedUriSchemes();

        if (schemes == null) {
            return;
        }

        if (schemes.size() > MAX_SCHEMES_PER_ACCOUNT) {
            EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                    "enforceLimitsOnSchemes");
            throw new IllegalArgumentException(
                    "Error, cannot register phone account " + account.getAccountHandle()
                            + " because the URI scheme limit of "
                            + MAX_SCHEMES_PER_ACCOUNT + " has been reached");
        }

        for (String scheme : schemes) {
            if (scheme.length() > MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT) {
                EventLog.writeEvent(0x534e4554, "259064622", Binder.getCallingUid(),
                        "enforceLimitsOnSchemes");
                throw new IllegalArgumentException(
                        "Error, cannot register phone account " + account.getAccountHandle()
                                + " because the max scheme limit of "
                                + MAX_PHONE_ACCOUNT_FIELD_CHAR_LIMIT + " has been reached");
            }
        }
    }

    /**
     * Adds a {@code PhoneAccount}, replacing an existing one if found.
     *
     * @param account The {@code PhoneAccount} to add or replace.
     */
    private void addOrReplacePhoneAccount(PhoneAccount account) {
        Log.d(this, "addOrReplacePhoneAccount(%s -> %s)",
                account.getAccountHandle(), account);

        // Start _enabled_ property as false.
        // !!! IMPORTANT !!! It is important that we do not read the enabled state that the
        // source app provides or else an third party app could enable itself.
        boolean isEnabled = false;
        boolean isNewAccount;

        // add self-managed capability for transactional accounts that are missing it
        if (hasTransactionalCallCapabilities(account)
                && !account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) {
            account = account.toBuilder()
                    .setCapabilities(account.getCapabilities()
                            | PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build();
            // Note: below we will automatically remove CAPABILITY_CONNECTION_MANAGER,
            // CAPABILITY_CALL_PROVIDER, and CAPABILITY_SIM_SUBSCRIPTION if this magically becomes
            // a self-managed phone account here.
        }

        PhoneAccount oldAccount = getPhoneAccountUnchecked(account.getAccountHandle());
        if (oldAccount != null) {
            enforceSelfManagedAccountUnmodified(account, oldAccount);
            mState.accounts.remove(oldAccount);
            isEnabled = oldAccount.isEnabled();
            Log.i(this, "Modify account: %s", getAccountDiffString(account, oldAccount));
            isNewAccount = false;
        } else {
            Log.i(this, "New phone account registered: " + account);
            isNewAccount = true;
        }

        // When registering a self-managed PhoneAccount we enforce the rule that the label that the
        // app uses is also its phone account label.  Also ensure it does not attempt to declare
        // itself as a sim acct, call manager or call provider.
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) {
            // Turn off bits we don't want to be able to set (TelecomServiceImpl protects against
            // this but we'll also prevent it from happening here, just to be safe).
            if ((account.getCapabilities() & (PhoneAccount.CAPABILITY_CALL_PROVIDER
                    | PhoneAccount.CAPABILITY_CONNECTION_MANAGER
                    | PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) > 0) {
                Log.w(this, "addOrReplacePhoneAccount: attempt to register a "
                        + "VoIP phone account with call provider/cm/sim sub capabilities.");
            }
            int newCapabilities = account.getCapabilities() &
                    ~(PhoneAccount.CAPABILITY_CALL_PROVIDER |
                        PhoneAccount.CAPABILITY_CONNECTION_MANAGER |
                        PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);

            // Ensure name is correct.
            CharSequence newLabel = mAppLabelProxy.getAppLabel(
                    account.getAccountHandle().getComponentName().getPackageName(),
                    UserUtil.getAssociatedUserForCall(
                            mTelecomFeatureFlags.associatedUserRefactorForWorkProfile(),
                            this, UserHandle.CURRENT, account.getAccountHandle()));

            account = account.toBuilder()
                    .setLabel(newLabel)
                    .setCapabilities(newCapabilities)
                    .build();
        }

        mState.accounts.add(account);
        // Set defaults and replace based on the group Id.
        maybeReplaceOldAccount(account);
        // Reset enabled state to whatever the value was if the account was already registered,
        // or _true_ if this is a SIM-based account.  All SIM-based accounts are always enabled,
        // as are all self-managed phone accounts.
        account.setIsEnabled(
                isEnabled || account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                || account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED));

        write();
        fireAccountsChanged();
        if (isNewAccount) {
            fireAccountRegistered(account.getAccountHandle());
        } else {
            fireAccountChanged(account);
        }
        // If this is the SIM call manager, tell telephony when the voice ServiceState override
        // needs to be updated.
        maybeNotifyTelephonyForVoiceServiceState(account, /* registered= */ true);
    }

    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        PhoneAccount account = getPhoneAccountUnchecked(accountHandle);
        if (account != null) {
            if (mState.accounts.remove(account)) {
                write();
                fireAccountsChanged();
                fireAccountUnRegistered(accountHandle);
                // If this is the SIM call manager, tell telephony when the voice ServiceState
                // override needs to be updated.
                maybeNotifyTelephonyForVoiceServiceState(account, /* registered= */ false);
            }
        }
    }

    private void enforceSelfManagedAccountUnmodified(PhoneAccount newAccount,
            PhoneAccount oldAccount) {
        if (oldAccount.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED) &&
                (!newAccount.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED))) {
            EventLog.writeEvent(0x534e4554, "246930197");
            Log.w(this, "Self-managed phone account %s replaced by a non self-managed one",
                    newAccount.getAccountHandle());
            throw new IllegalArgumentException("Error, cannot change a self-managed "
                    + "phone account " + newAccount.getAccountHandle()
                    + " to other kinds of phone account");
        }
    }

    /**
     * Un-registers all phone accounts associated with a specified package.
     *
     * @param packageName The package for which phone accounts will be removed.
     * @param userHandle The {@link UserHandle} the package is running under.
     */
    public void clearAccounts(String packageName, UserHandle userHandle) {
        boolean accountsRemoved = false;
        Iterator<PhoneAccount> it = mState.accounts.iterator();
        while (it.hasNext()) {
            PhoneAccount phoneAccount = it.next();
            PhoneAccountHandle handle = phoneAccount.getAccountHandle();
            if (Objects.equals(packageName, handle.getComponentName().getPackageName())
                    && Objects.equals(userHandle, handle.getUserHandle())) {
                Log.i(this, "Removing phone account " + phoneAccount.getLabel());
                mState.accounts.remove(phoneAccount);
                accountsRemoved = true;
            }
        }

        if (accountsRemoved) {
            write();
            fireAccountsChanged();
        }
    }

    public boolean isVoiceMailNumber(PhoneAccountHandle accountHandle, String number) {
        int subId = getSubscriptionIdForPhoneAccount(accountHandle);
        return PhoneNumberUtils.isVoiceMailNumber(mContext, subId, number);
    }

    public void addListener(Listener l) {
        mListeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) {
            mListeners.remove(l);
        }
    }

    private void fireAccountRegistered(PhoneAccountHandle handle) {
        for (Listener l : mListeners) {
            l.onPhoneAccountRegistered(this, handle);
        }
    }

    private void fireAccountChanged(PhoneAccount account) {
        for (Listener l : mListeners) {
            l.onPhoneAccountChanged(this, account);
        }
    }

    private void fireAccountUnRegistered(PhoneAccountHandle handle) {
        for (Listener l : mListeners) {
            l.onPhoneAccountUnRegistered(this, handle);
        }
    }

    private void fireAccountsChanged() {
        for (Listener l : mListeners) {
            l.onAccountsChanged(this);
        }
    }

    private void fireDefaultOutgoingChanged() {
        for (Listener l : mListeners) {
            l.onDefaultOutgoingChanged(this);
        }
    }

    private String getAccountDiffString(PhoneAccount account1, PhoneAccount account2) {
        if (account1 == null || account2 == null) {
            return "Diff: " + account1 + ", " + account2;
        }

        StringBuffer sb = new StringBuffer();
        sb.append("[").append(account1.getAccountHandle());
        appendDiff(sb, "addr", Log.piiHandle(account1.getAddress()),
                Log.piiHandle(account2.getAddress()));
        appendDiff(sb, "cap", account1.capabilitiesToString(), account2.capabilitiesToString());
        appendDiff(sb, "hl", account1.getHighlightColor(), account2.getHighlightColor());
        appendDiff(sb, "lbl", account1.getLabel(), account2.getLabel());
        appendDiff(sb, "desc", account1.getShortDescription(), account2.getShortDescription());
        appendDiff(sb, "subAddr", Log.piiHandle(account1.getSubscriptionAddress()),
                Log.piiHandle(account2.getSubscriptionAddress()));
        appendDiff(sb, "uris", account1.getSupportedUriSchemes(),
                account2.getSupportedUriSchemes());
        sb.append("]");
        return sb.toString();
    }

    private void appendDiff(StringBuffer sb, String attrName, Object obj1, Object obj2) {
        if (!Objects.equals(obj1, obj2)) {
            sb.append("(")
                .append(attrName)
                .append(": ")
                .append(obj1)
                .append(" -> ")
                .append(obj2)
                .append(")");
        }
    }

    private void maybeReplaceOldAccount(PhoneAccount newAccount) {
        UserHandle newAccountUserHandle = newAccount.getAccountHandle().getUserHandle();
        DefaultPhoneAccountHandle defaultHandle =
                getUserSelectedDefaultPhoneAccount(newAccountUserHandle);
        if (defaultHandle == null || defaultHandle.groupId.isEmpty()) {
            Log.v(this, "maybeReplaceOldAccount: Not replacing PhoneAccount, no group Id or " +
                    "default.");
            return;
        }
        if (!defaultHandle.groupId.equals(newAccount.getGroupId())) {
            Log.v(this, "maybeReplaceOldAccount: group Ids are not equal.");
            return;
        }
        if (Objects.equals(newAccount.getAccountHandle().getComponentName(),
                defaultHandle.phoneAccountHandle.getComponentName())) {
            // Move default calling account over to new user, since the ComponentNames and Group Ids
            // are the same.
            setUserSelectedOutgoingPhoneAccount(newAccount.getAccountHandle(),
                    newAccountUserHandle);
        } else {
            Log.v(this, "maybeReplaceOldAccount: group Ids are equal, but ComponentName is not" +
                    " the same as the default. Not replacing default PhoneAccount.");
        }
        PhoneAccount replacementAccount = getPhoneAccountByGroupId(newAccount.getGroupId(),
                newAccount.getAccountHandle().getComponentName(), newAccountUserHandle,
                newAccount.getAccountHandle());
        if (replacementAccount != null) {
            // Unregister the old PhoneAccount.
            Log.v(this, "maybeReplaceOldAccount: Unregistering old PhoneAccount: " +
                    replacementAccount.getAccountHandle());
            unregisterPhoneAccount(replacementAccount.getAccountHandle());
        }
    }

    private void maybeNotifyTelephonyForVoiceServiceState(
            @NonNull PhoneAccount account, boolean registered) {
        // TODO(b/215419665) what about SIM_SUBSCRIPTION accounts? They could theoretically also use
        // these capabilities, but don't today. If they do start using them, then there will need to
        // be a kind of "or" logic between SIM_SUBSCRIPTION and CONNECTION_MANAGER accounts to get
        // the correct value of hasService for a given SIM.
        boolean hasService = false;
        List<PhoneAccountHandle> simHandlesToNotify;
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)) {
            // When we unregister the SIM call manager account, we always set hasService back to
            // false since it is no longer providing OTT calling capability once unregistered.
            if (registered) {
                // Note: we do *not* early return when the SUPPORTS capability is not present
                // because it's possible the SIM call manager could remove either capability at
                // runtime and re-register. However, it is an error to use the AVAILABLE capability
                // without also setting SUPPORTS.
                hasService =
                        account.hasCapabilities(
                                PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
                                        | PhoneAccount.CAPABILITY_VOICE_CALLING_AVAILABLE);
            }
            // Notify for all SIMs that named this component as their SIM call manager in carrier
            // config, since there may be more than one impacted SIM here.
            simHandlesToNotify = getSimPhoneAccountsFromSimCallManager(account.getAccountHandle());
        } else if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
            // When new SIMs get registered, we notify them of their current voice status override.
            // If there is no SIM call manager for this SIM, we treat that as hasService = false and
            // still notify to ensure consistency.
            if (!registered) {
                // We don't do anything when SIMs are unregistered because we won't have an active
                // subId to map back to phoneId and tell telephony about; that case is handled by
                // telephony internally.
                return;
            }
            PhoneAccountHandle simCallManagerHandle =
                    getSimCallManagerFromHandle(
                            account.getAccountHandle(), account.getAccountHandle().getUserHandle());
            if (simCallManagerHandle != null) {
                PhoneAccount simCallManager = getPhoneAccountUnchecked(simCallManagerHandle);
                hasService =
                        simCallManager != null
                                && simCallManager.hasCapabilities(
                                        PhoneAccount.CAPABILITY_SUPPORTS_VOICE_CALLING_INDICATIONS
                                                | PhoneAccount.CAPABILITY_VOICE_CALLING_AVAILABLE);
            }
            simHandlesToNotify = Collections.singletonList(account.getAccountHandle());
        } else {
            // Not a relevant account - we only care about CONNECTION_MANAGER and SIM_SUBSCRIPTION.
            return;
        }
        if (simHandlesToNotify.isEmpty()) return;
        Log.i(
                this,
                "Notifying telephony of voice service override change for %d SIMs, hasService = %b",
                simHandlesToNotify.size(),
                hasService);
        try {
            for (PhoneAccountHandle simHandle : simHandlesToNotify) {
                // This may be null if there are no active SIMs but the device is still camped for
                // emergency calls and registered a SIM_SUBSCRIPTION for that purpose.
                TelephonyManager simTm = mTelephonyManager.createForPhoneAccountHandle(simHandle);
                if (simTm == null) {
                    Log.i(this, "maybeNotifyTelephonyForVoiceServiceState: "
                            + "simTm is null.");
                    continue;
                }
                simTm.setVoiceServiceStateOverride(hasService);
            }
        } catch (UnsupportedOperationException ignored) {
            // No telephony, so we can't override the sim service state.
            // Realistically we shouldn't get here because there should be no sim subs in this case.
            Log.w(this, "maybeNotifyTelephonyForVoiceServiceState: no telephony");
        }
    }

    /**
     * Determines if the connection service specified by a {@link PhoneAccountHandle} requires the
     * {@link Manifest.permission#BIND_TELECOM_CONNECTION_SERVICE} permission.
     *
     * @param phoneAccountHandle The phone account to check.
     * @return {@code True} if the phone account has permission.
     */
    public boolean phoneAccountRequiresBindPermission(PhoneAccountHandle phoneAccountHandle) {
        List<ResolveInfo> resolveInfos = resolveComponent(phoneAccountHandle);
        if (resolveInfos.isEmpty()) {
            Log.w(this, "phoneAccount %s not found", phoneAccountHandle.getComponentName());
            return false;
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo == null) {
                return false;
            }

            if (!Manifest.permission.BIND_CONNECTION_SERVICE.equals(serviceInfo.permission) &&
                    !Manifest.permission.BIND_TELECOM_CONNECTION_SERVICE.equals(
                            serviceInfo.permission)) {
                // The ConnectionService must require either the deprecated BIND_CONNECTION_SERVICE,
                // or the public BIND_TELECOM_CONNECTION_SERVICE permissions, both of which are
                // system/signature only.
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    public boolean hasTransactionalCallCapabilities(PhoneAccount phoneAccount) {
        if (phoneAccount == null) {
            return false;
        }
        return phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS);
    }

    //
    // Methods for retrieving PhoneAccounts and PhoneAccountHandles
    //

    /**
     * Returns the PhoneAccount for the specified handle.  Does no user checking.
     *
     * @param handle
     * @return The corresponding phone account if one exists.
     */
    public PhoneAccount getPhoneAccountUnchecked(PhoneAccountHandle handle) {
        for (PhoneAccount m : mState.accounts) {
            if (Objects.equals(handle, m.getAccountHandle())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Like getPhoneAccount, but checks to see if the current user is allowed to see the phone
     * account before returning it. The current user is the active user on the actual android
     * device.
     */
    public PhoneAccount getPhoneAccount(PhoneAccountHandle handle, UserHandle userHandle) {
        return getPhoneAccount(handle, userHandle, /* acrossProfiles */ false);
    }

    public PhoneAccount getPhoneAccount(PhoneAccountHandle handle,
            UserHandle userHandle, boolean acrossProfiles) {
        PhoneAccount account = getPhoneAccountUnchecked(handle);
        if (account != null && (isVisibleForUser(account, userHandle, acrossProfiles))) {
            return account;
        }
        return null;
    }

    public PhoneAccount getPhoneAccountOfCurrentUser(PhoneAccountHandle handle) {
        return getPhoneAccount(handle, mCurrentUserHandle);
    }

    private List<PhoneAccountHandle> getPhoneAccountHandles(
            int capabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess) {
        return getPhoneAccountHandles(capabilities, 0 /*excludedCapabilities*/, uriScheme,
                packageName, includeDisabledAccounts, userHandle, crossUserAccess, false);
    }

    private List<PhoneAccountHandle> getPhoneAccountHandles(
            int capabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess,
            boolean includeAll) {
        return getPhoneAccountHandles(capabilities, 0 /*excludedCapabilities*/, uriScheme,
                packageName, includeDisabledAccounts, userHandle, crossUserAccess, includeAll);
    }

    /**
     * Returns a list of phone account handles with the specified capabilities, uri scheme,
     * and package name.
     */
    private List<PhoneAccountHandle> getPhoneAccountHandles(
            int capabilities,
            int excludedCapabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess) {
        return getPhoneAccountHandles(capabilities, excludedCapabilities, uriScheme, packageName,
                includeDisabledAccounts, userHandle, crossUserAccess, false);
    }

    private List<PhoneAccountHandle> getPhoneAccountHandles(
            int capabilities,
            int excludedCapabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess,
            boolean includeAll) {
        List<PhoneAccountHandle> handles = new ArrayList<>();

        for (PhoneAccount account : getPhoneAccounts(
                capabilities, excludedCapabilities, uriScheme, packageName,
                includeDisabledAccounts, userHandle, crossUserAccess, includeAll)) {
            handles.add(account.getAccountHandle());
        }
        return handles;
    }

    private List<PhoneAccount> getPhoneAccounts(
            int capabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess) {
        return getPhoneAccounts(capabilities, 0 /*excludedCapabilities*/, uriScheme, packageName,
                includeDisabledAccounts, userHandle, crossUserAccess, false);
    }

    private List<PhoneAccount> getPhoneAccounts(
            int capabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess,
            boolean includeAll) {
        return getPhoneAccounts(capabilities, 0 /*excludedCapabilities*/, uriScheme, packageName,
                includeDisabledAccounts, userHandle, crossUserAccess, includeAll);
    }

    /**
     * Returns a list of phone account handles with the specified flag, supporting the specified
     * URI scheme, within the specified package name.
     *
     * @param capabilities Capabilities which the {@code PhoneAccount} must have. Ignored if 0.
     * @param excludedCapabilities Capabilities which the {@code PhoneAccount} must not have.
     *                             Ignored if 0.
     * @param uriScheme URI schemes the PhoneAccount must handle.  {@code null} bypasses the
     *                  URI scheme check.
     * @param packageName Package name of the PhoneAccount. {@code null} bypasses packageName check.
     */
    private List<PhoneAccount> getPhoneAccounts(
            int capabilities,
            int excludedCapabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess) {
        return getPhoneAccounts(capabilities, excludedCapabilities, uriScheme, packageName,
                includeDisabledAccounts, userHandle, crossUserAccess, false);
    }

    @VisibleForTesting
    public List<PhoneAccount> getPhoneAccounts(
            int capabilities,
            int excludedCapabilities,
            String uriScheme,
            String packageName,
            boolean includeDisabledAccounts,
            UserHandle userHandle,
            boolean crossUserAccess,
            boolean includeAll) {
        List<PhoneAccount> accounts = new ArrayList<>(mState.accounts.size());
        List<PhoneAccount> matchedAccounts = new ArrayList<>(mState.accounts.size());
        for (PhoneAccount m : mState.accounts) {
            if (!(m.isEnabled() || includeDisabledAccounts)) {
                // Do not include disabled accounts.
                continue;
            }

            if ((m.getCapabilities() & excludedCapabilities) != 0) {
                // If an excluded capability is present, skip.
                continue;
            }

            if (capabilities != 0 && !m.hasCapabilities(capabilities)) {
                // Account doesn't have the right capabilities; skip this one.
                continue;
            }
            if (uriScheme != null && !m.supportsUriScheme(uriScheme)) {
                // Account doesn't support this URI scheme; skip this one.
                continue;
            }
            PhoneAccountHandle handle = m.getAccountHandle();

            // PhoneAccounts with CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS do not require a
            // ConnectionService and will fail [resolveComponent(PhoneAccountHandle)]. Bypass
            // the [resolveComponent(PhoneAccountHandle)] for transactional accounts.
            if (!hasTransactionalCallCapabilities(m) && resolveComponent(handle).isEmpty()) {
                // This component cannot be resolved anymore; skip this one.
                continue;
            }
            if (packageName != null &&
                    !packageName.equals(handle.getComponentName().getPackageName())) {
                // Not the right package name; skip this one.
                continue;
            }
            if (isMatchedUser(m, userHandle)) {
                matchedAccounts.add(m);
            }
            if (!crossUserAccess && !isVisibleForUser(m, userHandle, false)) {
                // Account is not visible for the current user; skip this one.
                continue;
            }
            accounts.add(m);
        }

        // Return the account if it exactly matches. Otherwise, return any account that's visible
        if (mTelephonyFeatureFlags.workProfileApiSplit() && !crossUserAccess && !includeAll
                && !matchedAccounts.isEmpty()) {
            return matchedAccounts;
        }

        return accounts;
    }

    /**
     * Clean up the orphan {@code PhoneAccount}. An orphan {@code PhoneAccount} is a phone
     * account that does not have a {@code UserHandle} or belongs to a deleted package.
     *
     * @return the number of orphan {@code PhoneAccount} deleted.
     */
    public int cleanupOrphanedPhoneAccounts() {
        ArrayList<PhoneAccount> badAccountsList = new ArrayList<>();
        HashMap<String, Boolean> packageLookup = new HashMap<>();
        HashMap<PhoneAccount, Boolean> userHandleLookup = new HashMap<>();

        // iterate over all accounts in registrar
        for (PhoneAccount pa : mState.accounts) {
            String packageName = pa.getAccountHandle().getComponentName().getPackageName();

            // check if the package for the PhoneAccount is uninstalled
            if (packageLookup.computeIfAbsent(packageName,
                    pn -> isPackageUninstalled(pn))) {
                badAccountsList.add(pa);
            }
            // check if PhoneAccount does not have a valid UserHandle (user was deleted)
            else if (userHandleLookup.computeIfAbsent(pa,
                    a -> isUserHandleDeletedForPhoneAccount(a))) {
                badAccountsList.add(pa);
            }
        }

        mState.accounts.removeAll(badAccountsList);

        return badAccountsList.size();
    }

    public Boolean isPackageUninstalled(String packageName) {
        try {
            mContext.getPackageManager().getPackageInfo(packageName, 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private Boolean isUserHandleDeletedForPhoneAccount(PhoneAccount phoneAccount) {
        UserHandle userHandle = phoneAccount.getAccountHandle().getUserHandle();
        return (userHandle == null) ||
                (mUserManager.getSerialNumberForUser(userHandle) == -1L);
    }

    //
    // State Implementation for PhoneAccountRegistrar
    //

    /**
     * The state of this {@code PhoneAccountRegistrar}.
     */
    @VisibleForTesting
    public static class State {
        /**
         * Store the default phone account handle of users. If no record of a user can be found in
         * the map, it means that no default phone account handle is set in that user.
         */
        public final Map<UserHandle, DefaultPhoneAccountHandle> defaultOutgoingAccountHandles
                = new ConcurrentHashMap<>();

        /**
         * The complete list of {@code PhoneAccount}s known to the Telecom subsystem.
         */
        public final List<PhoneAccount> accounts = new CopyOnWriteArrayList<>();

        /**
         * The version number of the State data.
         */
        public int versionNumber;
    }

    /**
     * The default {@link PhoneAccountHandle} of a user.
     */
    public static class DefaultPhoneAccountHandle {

        public final UserHandle userHandle;

        public PhoneAccountHandle phoneAccountHandle;

        public final String groupId;

        public DefaultPhoneAccountHandle(UserHandle userHandle,
                PhoneAccountHandle phoneAccountHandle, String groupId) {
            this.userHandle = userHandle;
            this.phoneAccountHandle = phoneAccountHandle;
            this.groupId = groupId;
        }
    }

    /**
     * Dumps the state of the {@link CallsManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        if (mState != null) {
            pw.println("xmlVersion: " + mState.versionNumber);
            DefaultPhoneAccountHandle defaultPhoneAccountHandle
                    = mState.defaultOutgoingAccountHandles.get(Process.myUserHandle());
            pw.println("defaultOutgoing: " + (defaultPhoneAccountHandle == null ? "none" :
                    defaultPhoneAccountHandle.phoneAccountHandle));
            PhoneAccountHandle defaultOutgoing =
                    getOutgoingPhoneAccountForScheme(PhoneAccount.SCHEME_TEL, mCurrentUserHandle);
            pw.print("outgoingPhoneAccountForTelScheme: ");
            if (defaultOutgoing == null) {
                pw.println("none");
            } else {
                pw.println(defaultOutgoing);
            }
            // SubscriptionManager will throw if FEATURE_TELEPHONY_SUBSCRIPTION is not present.
            if (mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) {
                pw.println("defaultVoiceSubId: "
                        + SubscriptionManager.getDefaultVoiceSubscriptionId());
            }
            pw.println("simCallManager: " + getSimCallManager(mCurrentUserHandle));
            pw.println("phoneAccounts:");
            pw.increaseIndent();
            for (PhoneAccount phoneAccount : mState.accounts) {
                pw.println(phoneAccount);
            }
            pw.decreaseIndent();
            pw.increaseIndent();
            pw.println("test emergency PhoneAccount filter: " + mTestPhoneAccountPackageNameFilters);
            pw.decreaseIndent();
        }
    }

    private void sortPhoneAccounts() {
        if (mState.accounts.size() > 1) {
            // Sort the phone accounts using sort order:
            // 1) SIM accounts first, followed by non-sim accounts
            // 2) Sort order, with those specifying no sort order last.
            // 3) Label

            // Comparator to sort SIM subscriptions before non-sim subscriptions.
            Comparator<PhoneAccount> bySimCapability = (p1, p2) -> {
                if (p1.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                        && !p2.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                    return -1;
                } else if (!p1.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                        && p2.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                    return 1;
                } else {
                    return 0;
                }
            };

            // Create a string comparator which will sort strings, placing nulls last.
            Comparator<String> nullSafeStringComparator = Comparator.nullsLast(
                    String::compareTo);

            // Comparator which places PhoneAccounts with a specified sort order first, followed by
            // those with no sort order.
            Comparator<PhoneAccount> bySortOrder = (p1, p2) -> {
                int sort1 = p1.getExtras() == null ? Integer.MAX_VALUE:
                        p1.getExtras().getInt(PhoneAccount.EXTRA_SORT_ORDER, Integer.MAX_VALUE);
                int sort2 = p2.getExtras() == null ? Integer.MAX_VALUE:
                        p2.getExtras().getInt(PhoneAccount.EXTRA_SORT_ORDER, Integer.MAX_VALUE);
                return Integer.compare(sort1, sort2);
            };

            // Comparator which sorts PhoneAccounts by label.
            Comparator<PhoneAccount> byLabel = (p1, p2) -> {
                String s1 = p1.getLabel() == null ? null : p1.getLabel().toString();
                String s2 = p2.getLabel() == null ? null : p2.getLabel().toString();
                return nullSafeStringComparator.compare(s1, s2);
            };

            // Sort the phone accounts.
            mState.accounts.sort(bySimCapability.thenComparing(bySortOrder.thenComparing(byLabel)));
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // State management
    //

    private class AsyncXmlWriter extends AsyncTask<ByteArrayOutputStream, Void, Void> {
        @Override
        public Void doInBackground(ByteArrayOutputStream... args) {
            final ByteArrayOutputStream buffer = args[0];
            FileOutputStream fileOutput = null;
            try {
                synchronized (mWriteLock) {
                    fileOutput = mAtomicFile.startWrite();
                    buffer.writeTo(fileOutput);
                    mAtomicFile.finishWrite(fileOutput);
                }
            } catch (IOException e) {
                Log.e(this, e, "Writing state to XML file");
                mAtomicFile.failWrite(fileOutput);
            }
            return null;
        }
    }

    private void write() {
        try {
            sortPhoneAccounts();
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            XmlSerializer serializer = Xml.resolveSerializer(os);
            writeToXml(mState, serializer, mContext, mTelephonyFeatureFlags);
            serializer.flush();
            new AsyncXmlWriter().execute(os);
        } catch (IOException e) {
            Log.e(this, e, "Writing state to XML buffer");
        }
    }

    private void read() {
        final InputStream is;
        try {
            is = mAtomicFile.openRead();
        } catch (FileNotFoundException ex) {
            return;
        }

        boolean versionChanged = false;

        try {
            XmlPullParser parser = Xml.resolvePullParser(is);
            parser.nextTag();
            mState = readFromXml(parser, mContext, mTelephonyFeatureFlags, mTelecomFeatureFlags);
            migratePhoneAccountHandle(mState);
            versionChanged = mState.versionNumber < EXPECTED_STATE_VERSION;

        } catch (IOException | XmlPullParserException e) {
            Log.e(this, e, "Reading state from XML file");
            mState = new State();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(this, e, "Closing InputStream");
            }
        }

        // Verify all of the UserHandles.
        List<PhoneAccount> badAccounts = new ArrayList<>();
        for (PhoneAccount phoneAccount : mState.accounts) {
            UserHandle userHandle = phoneAccount.getAccountHandle().getUserHandle();
            if (userHandle == null) {
                Log.w(this, "Missing UserHandle for %s", phoneAccount);
                badAccounts.add(phoneAccount);
            } else if (mUserManager.getSerialNumberForUser(userHandle) == -1) {
                Log.w(this, "User does not exist for %s", phoneAccount);
                badAccounts.add(phoneAccount);
            }
        }
        mState.accounts.removeAll(badAccounts);

        // If an upgrade occurred, write out the changed data.
        if (versionChanged || !badAccounts.isEmpty()) {
            write();
        }
    }

    private static void writeToXml(State state, XmlSerializer serializer, Context context,
            FeatureFlags telephonyFeatureFlags) throws IOException {
        sStateXml.writeToXml(state, serializer, context, telephonyFeatureFlags);
    }

    private static State readFromXml(XmlPullParser parser, Context context,
            FeatureFlags telephonyFeatureFlags,
            com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags)
            throws IOException, XmlPullParserException {
        State s = sStateXml.readFromXml(parser, 0, context,
                telephonyFeatureFlags, telecomFeatureFlags);
        return s != null ? s : new State();
    }

    /**
     * Try to migrate the ID of default phone account handle from IccId to SubId.
     */
    @VisibleForTesting
    public void migratePhoneAccountHandle(State state) {
        if (mSubscriptionManager == null) {
            return;
        }
        // Use getAllSubscirptionInfoList() to get the mapping between iccId and subId
        // from the subscription database
        List<SubscriptionInfo> subscriptionInfos = mSubscriptionManager
                .getAllSubscriptionInfoList();
        Map<UserHandle, DefaultPhoneAccountHandle> defaultPhoneAccountHandles
                = state.defaultOutgoingAccountHandles;
        for (Map.Entry<UserHandle, DefaultPhoneAccountHandle> entry
                : defaultPhoneAccountHandles.entrySet()) {
            DefaultPhoneAccountHandle defaultPhoneAccountHandle = entry.getValue();

            // Migrate Telephony PhoneAccountHandle only
            String telephonyComponentName =
                    "com.android.phone/com.android.services.telephony.TelephonyConnectionService";
            if (!defaultPhoneAccountHandle.phoneAccountHandle.getComponentName()
                    .flattenToString().equals(telephonyComponentName)) {
                continue;
            }
            // Migrate from IccId to SubId
            for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
                String phoneAccountHandleId = defaultPhoneAccountHandle.phoneAccountHandle.getId();
                // Some phone account handle would store phone account handle id with the IccId
                // string plus "F", and the getIccId() returns IccId string itself without "F",
                // so here need to use "startsWith" to match.
                if (phoneAccountHandleId != null && phoneAccountHandleId.startsWith(
                        subscriptionInfo.getIccId())) {
                    Log.i(this, "Found subscription ID to migrate: "
                            + subscriptionInfo.getSubscriptionId());
                    defaultPhoneAccountHandle.phoneAccountHandle = new PhoneAccountHandle(
                            defaultPhoneAccountHandle.phoneAccountHandle.getComponentName(),
                                    Integer.toString(subscriptionInfo.getSubscriptionId()));
                    break;
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // XML serialization
    //

    @VisibleForTesting
    public abstract static class XmlSerialization<T> {
        private static final String TAG_VALUE = "value";
        private static final String ATTRIBUTE_LENGTH = "length";
        private static final String ATTRIBUTE_KEY = "key";
        private static final String ATTRIBUTE_VALUE_TYPE = "type";
        private static final String VALUE_TYPE_STRING = "string";
        private static final String VALUE_TYPE_INTEGER = "integer";
        private static final String VALUE_TYPE_BOOLEAN = "boolean";

        /**
         * Write the supplied object to XML
         */
        public abstract void writeToXml(T o, XmlSerializer serializer, Context context,
                FeatureFlags telephonyFeatureFlags) throws IOException;

        /**
         * Read from the supplied XML into a new object, returning null in case of an
         * unrecoverable schema mismatch or other data error. 'parser' must be already
         * positioned at the first tag that is expected to have been emitted by this
         * object's writeToXml(). This object tries to fail early without modifying
         * 'parser' if it does not recognize the data it sees.
         */
        public abstract T readFromXml(XmlPullParser parser, int version, Context context,
                FeatureFlags telephonyFeatureFlags,
                com.android.server.telecom.flags.FeatureFlags featureFlags)
                throws IOException, XmlPullParserException;

        protected void writeTextIfNonNull(String tagName, Object value, XmlSerializer serializer)
                throws IOException {
            if (value != null) {
                serializer.startTag(null, tagName);
                serializer.text(Objects.toString(value));
                serializer.endTag(null, tagName);
            }
        }

        /**
         * Serializes a List of PhoneAccountHandles.
         * @param tagName The tag for the List
         * @param handles The List of PhoneAccountHandles to serialize
         * @param serializer The serializer
         * @throws IOException if serialization fails.
         */
        protected void writePhoneAccountHandleSet(String tagName, Set<PhoneAccountHandle> handles,
                XmlSerializer serializer, Context context, FeatureFlags telephonyFeatureFlags)
                throws IOException {
            serializer.startTag(null, tagName);
            if (handles != null) {
                serializer.attribute(null, ATTRIBUTE_LENGTH, Objects.toString(handles.size()));
                for (PhoneAccountHandle handle : handles) {
                    sPhoneAccountHandleXml.writeToXml(handle, serializer, context,
                            telephonyFeatureFlags);
                }
            } else {
                serializer.attribute(null, ATTRIBUTE_LENGTH, "0");
            }
            serializer.endTag(null, tagName);
        }

        /**
         * Serializes a string array.
         *
         * @param tagName The tag name for the string array.
         * @param values The string values to serialize.
         * @param serializer The serializer.
         * @throws IOException
         */
        protected void writeStringList(String tagName, List<String> values,
                XmlSerializer serializer)
                throws IOException {

            serializer.startTag(null, tagName);
            if (values != null) {
                serializer.attribute(null, ATTRIBUTE_LENGTH, Objects.toString(values.size()));
                for (String toSerialize : values) {
                    serializer.startTag(null, TAG_VALUE);
                    if (toSerialize != null ){
                        serializer.text(toSerialize);
                    }
                    serializer.endTag(null, TAG_VALUE);
                }
            } else {
                serializer.attribute(null, ATTRIBUTE_LENGTH, "0");
            }
            serializer.endTag(null, tagName);
        }

        protected void writeBundle(String tagName, Bundle values, XmlSerializer serializer)
            throws IOException {

            serializer.startTag(null, tagName);
            if (values != null) {
                for (String key : values.keySet()) {
                    Object value = values.get(key);

                    if (value == null) {
                        continue;
                    }

                    String valueType;
                    if (value instanceof String) {
                        valueType = VALUE_TYPE_STRING;
                    } else if (value instanceof Integer) {
                        valueType = VALUE_TYPE_INTEGER;
                    } else if (value instanceof Boolean) {
                        valueType = VALUE_TYPE_BOOLEAN;
                    } else {
                        Log.w(this,
                                "PhoneAccounts support only string, integer and boolean extras TY.");
                        continue;
                    }

                    serializer.startTag(null, TAG_VALUE);
                    serializer.attribute(null, ATTRIBUTE_KEY, key);
                    serializer.attribute(null, ATTRIBUTE_VALUE_TYPE, valueType);
                    serializer.text(Objects.toString(value));
                    serializer.endTag(null, TAG_VALUE);
                }
            }
            serializer.endTag(null, tagName);
        }

        protected void writeIconIfNonNull(String tagName, Icon value, XmlSerializer serializer)
                throws IOException {
            if (value != null) {
                String text = writeIconToBase64String(value);
                serializer.startTag(null, tagName);
                serializer.text(text);
                serializer.endTag(null, tagName);
            }
        }

        public static String writeIconToBase64String(Icon icon) throws IOException {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            icon.writeToStream(stream);
            byte[] iconByteArray = stream.toByteArray();
            return Base64.encodeToString(iconByteArray, 0, iconByteArray.length, 0);
        }

        protected void writeLong(String tagName, long value, XmlSerializer serializer)
                throws IOException {
            serializer.startTag(null, tagName);
            serializer.text(Long.valueOf(value).toString());
            serializer.endTag(null, tagName);
        }

        protected void writeNonNullString(String tagName, String value, XmlSerializer serializer)
                throws IOException {
            serializer.startTag(null, tagName);
            serializer.text(value != null ? value : "");
            serializer.endTag(null, tagName);
        }

        protected Set<PhoneAccountHandle> readPhoneAccountHandleSet(XmlPullParser parser,
                int version, Context context, FeatureFlags telephonyFeatureFlags,
                com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags)
                throws IOException, XmlPullParserException {
            int length = Integer.parseInt(parser.getAttributeValue(null, ATTRIBUTE_LENGTH));
            Set<PhoneAccountHandle> handles = new HashSet<>(length);
            if (length == 0) return handles;

            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                handles.add(sPhoneAccountHandleXml.readFromXml(parser, version, context,
                        telephonyFeatureFlags, telecomFeatureFlags));
            }
            return handles;
        }

        /**
         * Reads a string array from the XML parser.
         *
         * @param parser The XML parser.
         * @return String array containing the parsed values.
         * @throws IOException Exception related to IO.
         * @throws XmlPullParserException Exception related to parsing.
         */
        protected List<String> readStringList(XmlPullParser parser)
                throws IOException, XmlPullParserException {

            int length = Integer.parseInt(parser.getAttributeValue(null, ATTRIBUTE_LENGTH));
            List<String> arrayEntries = new ArrayList<String>(length);
            String value = null;

            if (length == 0) {
                return arrayEntries;
            }

            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals(TAG_VALUE)) {
                    parser.next();
                    value = parser.getText();
                    arrayEntries.add(value);
                }
            }

            return arrayEntries;
        }

        /**
         * Reads a bundle from the XML parser.
         *
         * @param parser The XML parser.
         * @return Bundle containing the parsed values.
         * @throws IOException Exception related to IO.
         * @throws XmlPullParserException Exception related to parsing.
         */
        protected Bundle readBundle(XmlPullParser parser)
                throws IOException, XmlPullParserException {

            Bundle bundle = null;
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals(TAG_VALUE)) {
                    String valueType = parser.getAttributeValue(null, ATTRIBUTE_VALUE_TYPE);
                    String key = parser.getAttributeValue(null, ATTRIBUTE_KEY);
                    parser.next();
                    String value = parser.getText();

                    if (bundle == null) {
                        bundle = new Bundle();
                    }

                    // Do not write null values to the bundle.
                    if (value == null) {
                        continue;
                    }

                    if (VALUE_TYPE_STRING.equals(valueType)) {
                        bundle.putString(key, value);
                    } else if (VALUE_TYPE_INTEGER.equals(valueType)) {
                        try {
                            int intValue = Integer.parseInt(value);
                            bundle.putInt(key, intValue);
                        } catch (NumberFormatException nfe) {
                            Log.w(this, "Invalid integer PhoneAccount extra.");
                        }
                    } else if (VALUE_TYPE_BOOLEAN.equals(valueType)) {
                        boolean boolValue = Boolean.parseBoolean(value);
                        bundle.putBoolean(key, boolValue);
                    } else {
                        Log.w(this, "Invalid type " + valueType + " for PhoneAccount bundle.");
                    }
                }
            }
            return bundle;
        }

        protected Bitmap readBitmap(XmlPullParser parser) {
            byte[] imageByteArray = Base64.decode(parser.getText(), 0);
            return BitmapFactory.decodeByteArray(imageByteArray, 0, imageByteArray.length);
        }

        @Nullable
        protected Icon readIcon(XmlPullParser parser) throws IOException {
            try {
                byte[] iconByteArray = Base64.decode(parser.getText(), 0);
                ByteArrayInputStream stream = new ByteArrayInputStream(iconByteArray);
                return Icon.createFromStream(stream);
            } catch (IllegalArgumentException e) {
                Log.e(this, e, "Bitmap must not be null.");
                return null;
            }
        }
    }

    @VisibleForTesting
    public static final XmlSerialization<State> sStateXml =
            new XmlSerialization<State>() {
        private static final String CLASS_STATE = "phone_account_registrar_state";
        private static final String DEFAULT_OUTGOING = "default_outgoing";
        private static final String ACCOUNTS = "accounts";
        private static final String VERSION = "version";

        @Override
        public void writeToXml(State o, XmlSerializer serializer, Context context,
                FeatureFlags telephonyFeatureFlags) throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_STATE);
                serializer.attribute(null, VERSION, Objects.toString(EXPECTED_STATE_VERSION));

                serializer.startTag(null, DEFAULT_OUTGOING);
                for (DefaultPhoneAccountHandle defaultPhoneAccountHandle : o
                        .defaultOutgoingAccountHandles.values()) {
                    sDefaultPhoneAccountHandleXml
                            .writeToXml(defaultPhoneAccountHandle, serializer, context,
                                    telephonyFeatureFlags);
                }
                serializer.endTag(null, DEFAULT_OUTGOING);

                serializer.startTag(null, ACCOUNTS);
                for (PhoneAccount m : o.accounts) {
                    sPhoneAccountXml.writeToXml(m, serializer, context, telephonyFeatureFlags);
                }
                serializer.endTag(null, ACCOUNTS);

                serializer.endTag(null, CLASS_STATE);
            }
        }

        @Override
        public State readFromXml(XmlPullParser parser, int version, Context context,
                FeatureFlags telephonyFeatureFlags,
                com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags)
                throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_STATE)) {
                State s = new State();

                String rawVersion = parser.getAttributeValue(null, VERSION);
                s.versionNumber = TextUtils.isEmpty(rawVersion) ? 1 : Integer.parseInt(rawVersion);

                int outerDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(DEFAULT_OUTGOING)) {
                        if (s.versionNumber < 9) {
                            // Migrate old default phone account handle here by assuming the
                            // default phone account handle belongs to the primary user. Also,
                            // assume there are no groups.
                            parser.nextTag();
                            PhoneAccountHandle phoneAccountHandle = sPhoneAccountHandleXml
                                    .readFromXml(parser, s.versionNumber, context,
                                            telephonyFeatureFlags, telecomFeatureFlags);
                            UserManager userManager = context.getSystemService(UserManager.class);
                            // UserManager#getMainUser requires either the MANAGE_USERS,
                            // CREATE_USERS, or QUERY_USERS permission.
                            UserHandle primaryUser = userManager.getMainUser();
                            UserInfo primaryUserInfo = userManager.getPrimaryUser();
                            if (!telecomFeatureFlags.telecomResolveHiddenDependencies()) {
                                primaryUser = primaryUserInfo != null
                                        ? primaryUserInfo.getUserHandle()
                                        : null;
                            }
                            if (primaryUser != null) {
                                DefaultPhoneAccountHandle defaultPhoneAccountHandle
                                        = new DefaultPhoneAccountHandle(primaryUser,
                                        phoneAccountHandle, "" /* groupId */);
                                s.defaultOutgoingAccountHandles
                                        .put(primaryUser, defaultPhoneAccountHandle);
                            }
                        } else {
                            int defaultAccountHandlesDepth = parser.getDepth();
                            while (XmlUtils.nextElementWithin(parser, defaultAccountHandlesDepth)) {
                                DefaultPhoneAccountHandle accountHandle
                                        = sDefaultPhoneAccountHandleXml
                                        .readFromXml(parser, s.versionNumber, context,
                                                telephonyFeatureFlags, telecomFeatureFlags);
                                if (accountHandle != null && s.accounts != null) {
                                    s.defaultOutgoingAccountHandles
                                            .put(accountHandle.userHandle, accountHandle);
                                }
                            }
                        }
                    } else if (parser.getName().equals(ACCOUNTS)) {
                        int accountsDepth = parser.getDepth();
                        while (XmlUtils.nextElementWithin(parser, accountsDepth)) {
                            PhoneAccount account = sPhoneAccountXml.readFromXml(parser,
                                    s.versionNumber, context, telephonyFeatureFlags,
                                    telecomFeatureFlags);

                            if (account != null && s.accounts != null) {
                                s.accounts.add(account);
                            }
                        }
                    }
                }
                return s;
            }
            return null;
        }
    };

    @VisibleForTesting
    public static final XmlSerialization<DefaultPhoneAccountHandle> sDefaultPhoneAccountHandleXml =
            new XmlSerialization<DefaultPhoneAccountHandle>() {
                private static final String CLASS_DEFAULT_OUTGOING_PHONE_ACCOUNT_HANDLE
                        = "default_outgoing_phone_account_handle";
                private static final String USER_SERIAL_NUMBER = "user_serial_number";
                private static final String GROUP_ID = "group_id";
                private static final String ACCOUNT_HANDLE = "account_handle";

                @Override
                public void writeToXml(DefaultPhoneAccountHandle o, XmlSerializer serializer,
                        Context context, FeatureFlags telephonyFeatureFlags) throws IOException {
                    if (o != null) {
                        final UserManager userManager = context.getSystemService(UserManager.class);
                        final long serialNumber = userManager.getSerialNumberForUser(o.userHandle);
                        if (serialNumber != -1) {
                            serializer.startTag(null, CLASS_DEFAULT_OUTGOING_PHONE_ACCOUNT_HANDLE);
                            writeLong(USER_SERIAL_NUMBER, serialNumber, serializer);
                            writeNonNullString(GROUP_ID, o.groupId, serializer);
                            serializer.startTag(null, ACCOUNT_HANDLE);
                            sPhoneAccountHandleXml.writeToXml(o.phoneAccountHandle, serializer,
                                    context, telephonyFeatureFlags);
                            serializer.endTag(null, ACCOUNT_HANDLE);
                            serializer.endTag(null, CLASS_DEFAULT_OUTGOING_PHONE_ACCOUNT_HANDLE);
                        }
                    }
                }

                @Override
                public DefaultPhoneAccountHandle readFromXml(XmlPullParser parser, int version,
                        Context context, FeatureFlags telephonyFeatureFlags,
                        com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags)
                        throws IOException, XmlPullParserException {
                    if (parser.getName().equals(CLASS_DEFAULT_OUTGOING_PHONE_ACCOUNT_HANDLE)) {
                        int outerDepth = parser.getDepth();
                        PhoneAccountHandle accountHandle = null;
                        String userSerialNumberString = null;
                        String groupId = "";
                        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                            if (parser.getName().equals(ACCOUNT_HANDLE)) {
                                parser.nextTag();
                                accountHandle = sPhoneAccountHandleXml.readFromXml(parser, version,
                                        context, telephonyFeatureFlags, telecomFeatureFlags);
                            } else if (parser.getName().equals(USER_SERIAL_NUMBER)) {
                                parser.next();
                                userSerialNumberString = parser.getText();
                            } else if (parser.getName().equals(GROUP_ID)) {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    groupId = parser.getText();
                                }
                            }
                        }
                        UserHandle userHandle = null;
                        if (userSerialNumberString != null) {
                            try {
                                long serialNumber = Long.parseLong(userSerialNumberString);
                                userHandle = context.getSystemService(UserManager.class)
                                        .getUserForSerialNumber(serialNumber);
                            } catch (NumberFormatException e) {
                                Log.e(this, e,
                                        "Could not parse UserHandle " + userSerialNumberString);
                            }
                        }
                        if (accountHandle != null && userHandle != null && groupId != null) {
                            return new DefaultPhoneAccountHandle(userHandle, accountHandle,
                                    groupId);
                        }
                    }
                    return null;
                }
            };


    @VisibleForTesting
    public static final XmlSerialization<PhoneAccount> sPhoneAccountXml =
            new XmlSerialization<PhoneAccount>() {
        private static final String CLASS_PHONE_ACCOUNT = "phone_account";
        private static final String ACCOUNT_HANDLE = "account_handle";
        private static final String ADDRESS = "handle";
        private static final String SUBSCRIPTION_ADDRESS = "subscription_number";
        private static final String CAPABILITIES = "capabilities";
        private static final String SUPPORTED_AUDIO_ROUTES = "supported_audio_routes";
        private static final String ICON_RES_ID = "icon_res_id";
        private static final String ICON_PACKAGE_NAME = "icon_package_name";
        private static final String ICON_BITMAP = "icon_bitmap";
        private static final String ICON_TINT = "icon_tint";
        private static final String HIGHLIGHT_COLOR = "highlight_color";
        private static final String LABEL = "label";
        private static final String SHORT_DESCRIPTION = "short_description";
        private static final String SUPPORTED_URI_SCHEMES = "supported_uri_schemes";
        private static final String ICON = "icon";
        private static final String EXTRAS = "extras";
        private static final String ENABLED = "enabled";
        private static final String SIMULTANEOUS_CALLING_RESTRICTION
                = "simultaneous_calling_restriction";

        @Override
        public void writeToXml(PhoneAccount o, XmlSerializer serializer, Context context,
                FeatureFlags telephonyFeatureFlags) throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_PHONE_ACCOUNT);

                if (o.getAccountHandle() != null) {
                    serializer.startTag(null, ACCOUNT_HANDLE);
                    sPhoneAccountHandleXml.writeToXml(o.getAccountHandle(), serializer, context,
                            telephonyFeatureFlags);
                    serializer.endTag(null, ACCOUNT_HANDLE);
                }

                writeTextIfNonNull(ADDRESS, o.getAddress(), serializer);
                writeTextIfNonNull(SUBSCRIPTION_ADDRESS, o.getSubscriptionAddress(), serializer);
                writeTextIfNonNull(CAPABILITIES, Integer.toString(o.getCapabilities()), serializer);
                writeIconIfNonNull(ICON, o.getIcon(), serializer);
                writeTextIfNonNull(HIGHLIGHT_COLOR,
                        Integer.toString(o.getHighlightColor()), serializer);
                writeTextIfNonNull(LABEL, o.getLabel(), serializer);
                writeTextIfNonNull(SHORT_DESCRIPTION, o.getShortDescription(), serializer);
                writeStringList(SUPPORTED_URI_SCHEMES, o.getSupportedUriSchemes(), serializer);
                writeBundle(EXTRAS, o.getExtras(), serializer);
                writeTextIfNonNull(ENABLED, o.isEnabled() ? "true" : "false" , serializer);
                writeTextIfNonNull(SUPPORTED_AUDIO_ROUTES, Integer.toString(
                        o.getSupportedAudioRoutes()), serializer);
                if (o.hasSimultaneousCallingRestriction()
                        && telephonyFeatureFlags.simultaneousCallingIndications()) {
                    writePhoneAccountHandleSet(SIMULTANEOUS_CALLING_RESTRICTION,
                            o.getSimultaneousCallingRestriction(), serializer, context,
                            telephonyFeatureFlags);
                }

                serializer.endTag(null, CLASS_PHONE_ACCOUNT);
            }
        }

        public PhoneAccount readFromXml(XmlPullParser parser, int version, Context context,
                FeatureFlags telephonyFeatureFlags,
                com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags) throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_PHONE_ACCOUNT)) {
                int outerDepth = parser.getDepth();
                PhoneAccountHandle accountHandle = null;
                Uri address = null;
                Uri subscriptionAddress = null;
                int capabilities = 0;
                int supportedAudioRoutes = 0;
                int iconResId = PhoneAccount.NO_RESOURCE_ID;
                String iconPackageName = null;
                Bitmap iconBitmap = null;
                int iconTint = PhoneAccount.NO_ICON_TINT;
                int highlightColor = PhoneAccount.NO_HIGHLIGHT_COLOR;
                String label = null;
                String shortDescription = null;
                List<String> supportedUriSchemes = null;
                Icon icon = null;
                boolean enabled = false;
                Bundle extras = null;
                Set<PhoneAccountHandle> simultaneousCallingRestriction = null;

                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(ACCOUNT_HANDLE)) {
                        parser.nextTag();
                        accountHandle = sPhoneAccountHandleXml.readFromXml(parser, version,
                                context, telephonyFeatureFlags, telecomFeatureFlags);
                    } else if (parser.getName().equals(ADDRESS)) {
                        parser.next();
                        address = Uri.parse(parser.getText());
                    } else if (parser.getName().equals(SUBSCRIPTION_ADDRESS)) {
                        parser.next();
                        String nextText = parser.getText();
                        subscriptionAddress = nextText == null ? null : Uri.parse(nextText);
                    } else if (parser.getName().equals(CAPABILITIES)) {
                        parser.next();
                        capabilities = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(ICON_RES_ID)) {
                        parser.next();
                        iconResId = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(ICON_PACKAGE_NAME)) {
                        parser.next();
                        iconPackageName = parser.getText();
                    } else if (parser.getName().equals(ICON_BITMAP)) {
                        parser.next();
                        iconBitmap = readBitmap(parser);
                    } else if (parser.getName().equals(ICON_TINT)) {
                        parser.next();
                        iconTint = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(HIGHLIGHT_COLOR)) {
                        parser.next();
                        highlightColor = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(LABEL)) {
                        parser.next();
                        label = parser.getText();
                    } else if (parser.getName().equals(SHORT_DESCRIPTION)) {
                        parser.next();
                        shortDescription = parser.getText();
                    } else if (parser.getName().equals(SUPPORTED_URI_SCHEMES)) {
                        supportedUriSchemes = readStringList(parser);
                    } else if (parser.getName().equals(ICON)) {
                        parser.next();
                        icon = readIcon(parser);
                    } else if (parser.getName().equals(ENABLED)) {
                        parser.next();
                        enabled = "true".equalsIgnoreCase(parser.getText());
                    } else if (parser.getName().equals(EXTRAS)) {
                        extras = readBundle(parser);
                    } else if (parser.getName().equals(SUPPORTED_AUDIO_ROUTES)) {
                        parser.next();
                        supportedAudioRoutes = Integer.parseInt(parser.getText());
                    } else if (parser.getName().equals(SIMULTANEOUS_CALLING_RESTRICTION)) {
                        // We can not flag this because we always need to handle the case where
                        // this info is in the XML for parsing reasons. We only flag setting the
                        // parsed value below based on the flag.
                        simultaneousCallingRestriction = readPhoneAccountHandleSet(parser, version,
                                context, telephonyFeatureFlags, telecomFeatureFlags);
                    }
                }

                ComponentName pstnComponentName = new ComponentName("com.android.phone",
                        "com.android.services.telephony.TelephonyConnectionService");
                ComponentName sipComponentName = new ComponentName("com.android.phone",
                        "com.android.services.telephony.sip.SipConnectionService");

                // Upgrade older phone accounts to specify the supported URI schemes.
                if (version < 2) {
                    supportedUriSchemes = new ArrayList<>();

                    // Handle the SIP connection service.
                    // Check the system settings to see if it also should handle "tel" calls.
                    if (accountHandle.getComponentName().equals(sipComponentName)) {
                        boolean useSipForPstn = useSipForPstnCalls(context);
                        supportedUriSchemes.add(PhoneAccount.SCHEME_SIP);
                        if (useSipForPstn) {
                            supportedUriSchemes.add(PhoneAccount.SCHEME_TEL);
                        }
                    } else {
                        supportedUriSchemes.add(PhoneAccount.SCHEME_TEL);
                        supportedUriSchemes.add(PhoneAccount.SCHEME_VOICEMAIL);
                    }
                }

                // Upgrade older phone accounts with explicit package name
                if (version < 5) {
                    if (iconBitmap == null) {
                        iconPackageName = accountHandle.getComponentName().getPackageName();
                    }
                }

                if (version < 6) {
                    // Always enable all SIP accounts on upgrade to version 6
                    if (accountHandle.getComponentName().equals(sipComponentName)) {
                        enabled = true;
                    }
                }
                if (version < 7) {
                    // Always enabled all PSTN acocunts on upgrade to version 7
                    if (accountHandle.getComponentName().equals(pstnComponentName)) {
                        enabled = true;
                    }
                }
                if (version < 8) {
                    // Migrate the SIP account handle ids to use SIP username instead of SIP URI.
                    if (accountHandle.getComponentName().equals(sipComponentName)) {
                        Uri accountUri = Uri.parse(accountHandle.getId());
                        if (accountUri.getScheme() != null &&
                            accountUri.getScheme().equals(PhoneAccount.SCHEME_SIP)) {
                            accountHandle = new PhoneAccountHandle(accountHandle.getComponentName(),
                                    accountUri.getSchemeSpecificPart(),
                                    accountHandle.getUserHandle());
                        }
                    }
                }

                if (version < 9) {
                    // Set supported audio routes to all by default
                    supportedAudioRoutes = CallAudioState.ROUTE_ALL;
                }

                PhoneAccount.Builder builder = PhoneAccount.builder(accountHandle, label)
                        .setAddress(address)
                        .setSubscriptionAddress(subscriptionAddress)
                        .setCapabilities(capabilities)
                        .setSupportedAudioRoutes(supportedAudioRoutes)
                        .setShortDescription(shortDescription)
                        .setSupportedUriSchemes(supportedUriSchemes)
                        .setHighlightColor(highlightColor)
                        .setExtras(extras)
                        .setIsEnabled(enabled);

                if (icon != null) {
                    builder.setIcon(icon);
                } else if (iconBitmap != null) {
                    builder.setIcon(Icon.createWithBitmap(iconBitmap));
                } else if (!TextUtils.isEmpty(iconPackageName)) {
                    builder.setIcon(Icon.createWithResource(iconPackageName, iconResId));
                    // TODO: Need to set tint.
                } else if (simultaneousCallingRestriction != null
                        && telephonyFeatureFlags.simultaneousCallingIndications()) {
                    builder.setSimultaneousCallingRestriction(simultaneousCallingRestriction);
                }

                return builder.build();
            }
            return null;
        }

        /**
         * Determines if the SIP call settings specify to use SIP for all calls, including PSTN
         * calls.
         *
         * @param context The context.
         * @return {@code True} if SIP should be used for all calls.
         */
        private boolean useSipForPstnCalls(Context context) {
            String option = Settings.System.getStringForUser(context.getContentResolver(),
                    Settings.System.SIP_CALL_OPTIONS, context.getUserId());
            option = (option != null) ? option : Settings.System.SIP_ADDRESS_ONLY;
            return option.equals(Settings.System.SIP_ALWAYS);
        }
    };

    @VisibleForTesting
    public static final XmlSerialization<PhoneAccountHandle> sPhoneAccountHandleXml =
            new XmlSerialization<PhoneAccountHandle>() {
        private static final String CLASS_PHONE_ACCOUNT_HANDLE = "phone_account_handle";
        private static final String COMPONENT_NAME = "component_name";
        private static final String ID = "id";
        private static final String USER_SERIAL_NUMBER = "user_serial_number";

        @Override
        public void writeToXml(PhoneAccountHandle o, XmlSerializer serializer, Context context,
                FeatureFlags telephonyFeatureFlags) throws IOException {
            if (o != null) {
                serializer.startTag(null, CLASS_PHONE_ACCOUNT_HANDLE);

                if (o.getComponentName() != null) {
                    writeTextIfNonNull(
                            COMPONENT_NAME, o.getComponentName().flattenToString(), serializer);
                }

                writeTextIfNonNull(ID, o.getId(), serializer);

                if (o.getUserHandle() != null && context != null) {
                    UserManager userManager = context.getSystemService(UserManager.class);
                    writeLong(USER_SERIAL_NUMBER,
                            userManager.getSerialNumberForUser(o.getUserHandle()), serializer);
                }

                serializer.endTag(null, CLASS_PHONE_ACCOUNT_HANDLE);
            }
        }

        @Override
        public PhoneAccountHandle readFromXml(XmlPullParser parser, int version, Context context,
                FeatureFlags telephonyFeatureFlags,
                com.android.server.telecom.flags.FeatureFlags telecomFeatureFlags)
                throws IOException, XmlPullParserException {
            if (parser.getName().equals(CLASS_PHONE_ACCOUNT_HANDLE)) {
                String componentNameString = null;
                String idString = null;
                String userSerialNumberString = null;
                int outerDepth = parser.getDepth();

                UserManager userManager = context.getSystemService(UserManager.class);

                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    if (parser.getName().equals(COMPONENT_NAME)) {
                        parser.next();
                        componentNameString = parser.getText();
                    } else if (parser.getName().equals(ID)) {
                        parser.next();
                        idString = parser.getText();
                    } else if (parser.getName().equals(USER_SERIAL_NUMBER)) {
                        parser.next();
                        userSerialNumberString = parser.getText();
                    }
                }
                if (componentNameString != null) {
                    UserHandle userHandle = null;
                    if (userSerialNumberString != null) {
                        try {
                            long serialNumber = Long.parseLong(userSerialNumberString);
                            userHandle = userManager.getUserForSerialNumber(serialNumber);
                        } catch (NumberFormatException e) {
                            Log.e(this, e, "Could not parse UserHandle " + userSerialNumberString);
                        }
                    }
                    return new PhoneAccountHandle(
                            ComponentName.unflattenFromString(componentNameString),
                            idString,
                            userHandle);
                }
            }
            return null;
        }
    };

    /**
     * Determines if an app specified by a uid has a phone account for that uid.
     * @param uid the uid to check
     * @return {@code true} if there is a phone account for that UID, {@code false} otherwise.
     */
    public boolean hasPhoneAccountForUid(int uid) {
        String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
        if (packageNames == null || packageNames.length == 0) {
            return false;
        }
        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
        return mState.accounts.stream()
                .anyMatch(p -> {
                    PhoneAccountHandle handle = p.getAccountHandle();
                    return handle.getUserHandle().equals(userHandle)
                            && Arrays.stream(packageNames).anyMatch( s -> s.equals(
                                    handle.getComponentName().getPackageName()));
                });
    }
}
