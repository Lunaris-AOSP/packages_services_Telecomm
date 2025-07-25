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

import static com.android.server.telecom.tests.TelecomSystemTest.TEST_TIMEOUT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.location.Country;
import android.location.CountryDetector;
import android.location.CountryListener;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.server.telecom.Analytics;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallLogManager;
import com.android.server.telecom.CallState;
import com.android.server.telecom.HandoverState;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.TelephonyUtil;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public class CallLogManagerTest extends TelecomTestCase {

    private CallLogManager mCallLogManager;
    private PhoneAccountHandle mDefaultAccountHandle;
    private PhoneAccountHandle mOtherUserAccountHandle;
    private PhoneAccountHandle mManagedProfileAccountHandle;
    private PhoneAccountHandle mSelfManagedAccountHandle;
    private Analytics.CallInfo mCallInfo;

    private static final Uri TEL_PHONEHANDLE = Uri.parse("tel:5555551234");

    private static final PhoneAccountHandle EMERGENCY_ACCT_HANDLE = TelephonyUtil
            .getDefaultEmergencyPhoneAccount()
            .getAccountHandle();

    private static final int NO_VIDEO_STATE = VideoProfile.STATE_AUDIO_ONLY;
    private static final int BIDIRECTIONAL_VIDEO_STATE = VideoProfile.STATE_BIDIRECTIONAL;
    private static final String POST_DIAL_STRING = ";12345";
    private static final String VIA_NUMBER_STRING = "5555555678";
    private static final String TEST_PHONE_ACCOUNT_ID= "testPhoneAccountId";
    private static final String TEST_SELF_MGD_PHONE_ACCOUNT_ID= "testPhoneAccountId";

    private static final int TEST_TIMEOUT_MILLIS = 200;
    private static final int CURRENT_USER_ID = 0;
    private static final int OTHER_USER_ID = 10;
    private static final int MANAGED_USER_ID = 11;
    private static final int PRIVATE_USER_ID = 12;

    private static final String TEST_ISO = "KR";
    private static final String TEST_ISO_2 = "JP";

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    ContentProvider mContentProvider;
    @Mock
    PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock
    MissedCallNotifier mMissedCallNotifier;
    @Mock
    AnomalyReporterAdapter mAnomalyReporterAdapter;

    @Mock
    FeatureFlags mFeatureFlags;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mCallLogManager = new CallLogManager(mContext, mMockPhoneAccountRegistrar,
                mMissedCallNotifier, mAnomalyReporterAdapter, mFeatureFlags);
        mDefaultAccountHandle = new PhoneAccountHandle(
                new ComponentName("com.android.server.telecom.tests", "CallLogManagerTest"),
                TEST_PHONE_ACCOUNT_ID,
                UserHandle.of(CURRENT_USER_ID)
        );

        mOtherUserAccountHandle = new PhoneAccountHandle(
                new ComponentName("com.android.server.telecom.tests", "CallLogManagerTest"),
                TEST_PHONE_ACCOUNT_ID,
                UserHandle.of(OTHER_USER_ID)
        );

        mManagedProfileAccountHandle = new PhoneAccountHandle(
                new ComponentName("com.android.server.telecom.tests", "CallLogManagerTest"),
                TEST_PHONE_ACCOUNT_ID,
                UserHandle.of(MANAGED_USER_ID)
        );

        mSelfManagedAccountHandle = new PhoneAccountHandle(
                new ComponentName("com.android.server.telecom.tests", "CallLogManagerSelfMgdTest"),
                TEST_SELF_MGD_PHONE_ACCOUNT_ID,
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallInfo = new Analytics.CallInfo();

        // Since we can't mock ContentResolver directly, use a ContentProvider
        when(mContext.getContentResolver()).thenReturn(ContentResolver.wrap(mContentProvider));

        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        UserInfo userInfo = new UserInfo(CURRENT_USER_ID, "test", 0);
        userInfo.profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        userInfo.userType = UserManager.USER_TYPE_FULL_SYSTEM;

        UserInfo otherUserInfo = new UserInfo(OTHER_USER_ID, "test2", 0);
        otherUserInfo.profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        otherUserInfo.userType = UserManager.USER_TYPE_FULL_SECONDARY;

        UserInfo managedProfileUserInfo = new UserInfo(MANAGED_USER_ID, "test3",
                UserInfo.FLAG_MANAGED_PROFILE | userInfo.FLAG_PROFILE);
        managedProfileUserInfo.profileGroupId = 90210;
        managedProfileUserInfo.userType = UserManager.USER_TYPE_PROFILE_MANAGED;

        UserInfo privateProfileUserInfo = new UserInfo(PRIVATE_USER_ID, "private",
                UserInfo.FLAG_PROFILE);
        privateProfileUserInfo.profileGroupId = 90210;
        privateProfileUserInfo.userType = UserManager.USER_TYPE_PROFILE_PRIVATE;

        doAnswer(new Answer<Uri>() {
            @Override
            public Uri answer(InvocationOnMock invocation) throws Throwable {
                return (Uri) invocation.getArguments()[0];
            }
        }).when(mContentProvider).insert(any(Uri.class), any(ContentValues.class));

        when(userManager.isUserRunning(any(UserHandle.class))).thenReturn(true);
        when(userManager.isUserUnlocked(any(UserHandle.class))).thenReturn(true);
        when(userManager.hasUserRestrictionForUser(any(String.class), any(UserHandle.class)))
                .thenReturn(false);
        when(userManager.getAliveUsers())
                .thenReturn(Arrays.asList(userInfo, otherUserInfo, managedProfileUserInfo));
        configureContextForUser(CURRENT_USER_ID, userInfo);
        when(userManager.getUserInfo(eq(CURRENT_USER_ID))).thenReturn(userInfo);

        configureContextForUser(OTHER_USER_ID, otherUserInfo);
        when(userManager.getUserInfo(eq(OTHER_USER_ID))).thenReturn(otherUserInfo);

        configureContextForUser(MANAGED_USER_ID, managedProfileUserInfo);
        when(userManager.getUserInfo(eq(MANAGED_USER_ID))).thenReturn(managedProfileUserInfo);

        configureContextForUser(PRIVATE_USER_ID, privateProfileUserInfo);
        when(userManager.getUserInfo(eq(PRIVATE_USER_ID))).thenReturn(privateProfileUserInfo);

        PackageManager packageManager = mContext.getPackageManager();
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)).thenReturn(false);
        when(mFeatureFlags.telecomLogExternalWearableCalls()).thenReturn(false);
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(true);
    }

    /**
     * Yuck; this is absolutely wretched that we have to mock things out in this way.
     * Because the preferred way to get info about a user is to first user
     * {@link Context#createContextAsUser(UserHandle, int)} to first get a user-specific context,
     * and to then query the {@link UserManager} instance to see if it's a profile, we need to do
     * all of this really gross mocking.
     * @param userId The userid.
     * @param info The associated userinfo.
     */
    private void configureContextForUser(int userId, UserInfo info) {
        Context mockContext = mock(Context.class);
        mComponentContextFixture.addContextForUser(UserHandle.of(userId), mockContext);
        UserManager mockUserManager = mock(UserManager.class);
        when(mockUserManager.getUserInfo(eq(userId))).thenReturn(info);
        when(mockUserManager.isProfile()).thenReturn(info.isProfile());
        when(mockContext.getSystemService(eq(UserManager.class))).thenReturn(mockUserManager);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @MediumTest
    @Test
    public void testDontLogCancelledCall() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.CANCELED,
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.DIALING, CallState.DISCONNECTED);
        verifyNoInsertion();
        mCallLogManager.onCallStateChanged(fakeCall, CallState.DIALING, CallState.ABORTED);
        verifyNoInsertion();
    }

    @MediumTest
    @Test
    public void testDontLogChoosingAccountCall() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.SELECT_PHONE_ACCOUNT,
                CallState.DISCONNECTED);
        verifyNoInsertion();
    }

    @MediumTest
    @Test
    public void testDontLogUnloggableNumbers() {
        // Set up the carrier config source
        String number1 = "90000";
        String number2 = "80000";
        CarrierConfigManager mockCarrierConfigManager =
                (CarrierConfigManager) mComponentContextFixture.getTestDouble()
                        .getApplicationContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(CarrierConfigManager.KEY_UNLOGGABLE_NUMBERS_STRING_ARRAY,
                new String[] {number1});
        when(mockCarrierConfigManager.getConfig()).thenReturn(bundle);

        Resources mockResources = mContext.getResources();
        when(mockResources.getStringArray(com.android.internal.R.array.unloggable_phone_numbers))
                .thenReturn(new String[] {number2});

        Call fakeCall1 = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                Uri.parse("tel:" + number1),
                EMERGENCY_ACCT_HANDLE, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );

        Call fakeCall2 = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                Uri.parse("tel:" + number2),
                EMERGENCY_ACCT_HANDLE, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );

        mCallLogManager.onCallStateChanged(fakeCall1, CallState.ACTIVE, CallState.DISCONNECTED);
        mCallLogManager.onCallStateChanged(fakeCall2, CallState.ACTIVE, CallState.DISCONNECTED);
        verifyNoInsertion();
    }

    @MediumTest
    @Test
    public void testDontLogCallsFromEmergencyAccount() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(EMERGENCY_ACCT_HANDLE, 0));
        CarrierConfigManager mockCarrierConfigManager =
                (CarrierConfigManager) mComponentContextFixture.getTestDouble()
                        .getApplicationContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG_BOOL, false);
        when(mockCarrierConfigManager.getConfig()).thenReturn(bundle);

        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                EMERGENCY_ACCT_HANDLE, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        verifyNoInsertion();
    }

    @MediumTest
    @Test
    public void testLogCallDirectionOutgoing() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionIncoming() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeIncomingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        when(mFeatureFlags.addCallUriForMissedCalls()).thenReturn(true);
        mCallLogManager.onCallStateChanged(fakeIncomingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.INCOMING_TYPE));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionMissedAddCallUriForMissedCallsFlagOff() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.MISSED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        when(mFeatureFlags.addCallUriForMissedCalls()).thenReturn(false);

        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.MISSED_TYPE));
        // Timeout needed because showMissedCallNotification is called from onPostExecute.
        verify(mMissedCallNotifier, timeout(TEST_TIMEOUT_MILLIS))
                .showMissedCallNotification(any(MissedCallNotifier.CallInfo.class),
                        /* uri= */ eq(null));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionMissedAddCallUriForMissedCallsFlagOn() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.MISSED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        when(mFeatureFlags.addCallUriForMissedCalls()).thenReturn(true);

        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.MISSED_TYPE));
        // Timeout needed because showMissedCallNotification is called from onPostExecute.
        verify(mMissedCallNotifier, timeout(TEST_TIMEOUT_MILLIS))
                .showMissedCallNotification(any(MissedCallNotifier.CallInfo.class),
                        /* uri= */ any(Uri.class));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionRejected() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.REJECTED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );

        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(Calls.REJECTED_TYPE));
    }

    @MediumTest
    @Test
    public void testCreationTimeAndAge() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        long currentTime = System.currentTimeMillis();
        long duration = 1000L;
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                currentTime, // creationTimeMillis
                duration, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsLong(CallLog.Calls.DATE),
                Long.valueOf(currentTime));
        assertEquals(insertedValues.getAsLong(CallLog.Calls.DURATION),
                Long.valueOf(duration / 1000));
    }

    @MediumTest
    @Test
    public void testLogPhoneAccountId() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsString(CallLog.Calls.PHONE_ACCOUNT_ID),
                TEST_PHONE_ACCOUNT_ID);
    }

    @MediumTest
    @Test
    public void testLogCorrectPhoneNumber() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(insertedValues.getAsString(CallLog.Calls.NUMBER),
                TEL_PHONEHANDLE.getSchemeSpecificPart());
        assertEquals(insertedValues.getAsString(CallLog.Calls.POST_DIAL_DIGITS), POST_DIAL_STRING);
        String expectedNumber = PhoneNumberUtils.formatNumber(VIA_NUMBER_STRING,
                mCallLogManager.getCountryIso());
        assertEquals(insertedValues.getAsString(Calls.VIA_NUMBER), expectedNumber);
    }

    @MediumTest
    @Test
    public void testLogCallVideoFeatures() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeVideoCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                BIDIRECTIONAL_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeVideoCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertTrue((insertedValues.getAsInteger(CallLog.Calls.FEATURES)
                & CallLog.Calls.FEATURES_VIDEO) == CallLog.Calls.FEATURES_VIDEO);
    }

    @MediumTest
    @FlakyTest
    @Test
    public void testLogCallDirectionOutgoingWithMultiUserCapability() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mOtherUserAccountHandle,
                        PhoneAccount.CAPABILITY_MULTI_USER));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Outgoing call placed through a phone account with multi user capability is inserted to
        // all users except managed profile.
        SystemClock.sleep(TEST_TIMEOUT_MILLIS);

        ArgumentCaptor<Uri> uris = ArgumentCaptor.forClass(Uri.class);
        ArgumentCaptor<ContentValues> values = ArgumentCaptor.forClass(ContentValues.class);

        verify(mContentProvider, atLeast(2)).insert(uris.capture(), values.capture());

        assertTrue(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, CURRENT_USER_ID)));
        assertTrue(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, OTHER_USER_ID)));
        assertFalse(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, MANAGED_USER_ID)));
        assertFalse(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, PRIVATE_USER_ID)));
        for (ContentValues v : values.getAllValues()) {
            assertEquals(v.getAsInteger(CallLog.Calls.TYPE),
                    Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
        }
    }

    @MediumTest
    @FlakyTest(bugId = 117751305)
    @Test
    public void testLogCallDirectionIncomingWithMultiUserCapability() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mOtherUserAccountHandle,
                        PhoneAccount.CAPABILITY_MULTI_USER));
        Call fakeIncomingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        mCallLogManager.onCallStateChanged(fakeIncomingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Incoming call using a phone account with multi user capability is inserted to all users
        // except managed profile.
        SystemClock.sleep(TEST_TIMEOUT_MILLIS);

        ArgumentCaptor<Uri> uris = ArgumentCaptor.forClass(Uri.class);
        ArgumentCaptor<ContentValues> values = ArgumentCaptor.forClass(ContentValues.class);

        verify(mContentProvider, atLeast(2)).insert(uris.capture(), values.capture());

        assertTrue(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, CURRENT_USER_ID)));
        assertTrue(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, OTHER_USER_ID)));
        assertFalse(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, MANAGED_USER_ID)));
        assertFalse(uris.getAllValues().contains(
                ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, PRIVATE_USER_ID)));
        for (ContentValues v : values.getAllValues()) {
            assertEquals(v.getAsInteger(CallLog.Calls.TYPE),
                    Integer.valueOf(CallLog.Calls.INCOMING_TYPE));
        }
    }

    @MediumTest
    @Test
    public void testLogCallDirectionOutgoingWithMultiUserCapabilityFromManagedProfile() {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        when(userManager.isManagedProfile()).thenReturn(true);
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mManagedProfileAccountHandle,
                        PhoneAccount.CAPABILITY_MULTI_USER));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mManagedProfileAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(MANAGED_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Outgoing call placed through work dialer should be inserted to managed profile only.
        verifyNoInsertionInUser(CURRENT_USER_ID);
        verifyNoInsertionInUser(OTHER_USER_ID);
        ContentValues insertedValues = verifyInsertionWithCapture(MANAGED_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionOutgoingWithMultiUserCapabilityFromPrivateProfile() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle,
                        PhoneAccount.CAPABILITY_MULTI_USER));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(PRIVATE_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Outgoing call placed through private space should only show up in the private space
        // call logs.
        verifyNoInsertionInUser(CURRENT_USER_ID);
        verifyNoInsertionInUser(OTHER_USER_ID);
        verifyNoInsertionInUser(MANAGED_USER_ID);
        ContentValues insertedValues = verifyInsertionWithCapture(PRIVATE_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionOutgoingWithMultiUserCapabilityFromPrivateProfileNoRefactor() {
        // Same as the above test, but turns off the hidden deps refactor; there are some minor
        // differences in how we detect profiles, so we want to ensure this works both ways.
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(false);
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle,
                        PhoneAccount.CAPABILITY_MULTI_USER));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(PRIVATE_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Outgoing call placed through work dialer should be inserted to managed profile only.
        verifyNoInsertionInUser(CURRENT_USER_ID);
        verifyNoInsertionInUser(OTHER_USER_ID);
        verifyNoInsertionInUser(MANAGED_USER_ID);
        ContentValues insertedValues = verifyInsertionWithCapture(PRIVATE_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    @MediumTest
    @FlakyTest
    @Test
    public void testLogCallDirectionOutgoingFromManagedProfile() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mManagedProfileAccountHandle, 0));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                false, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mManagedProfileAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(MANAGED_USER_ID)
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Outgoing call using phone account in managed profile should be inserted to managed
        // profile only.
        verifyNoInsertionInUser(CURRENT_USER_ID);
        verifyNoInsertionInUser(OTHER_USER_ID);
        verifyNoInsertionInUser(PRIVATE_USER_ID);
        ContentValues insertedValues = verifyInsertionWithCapture(MANAGED_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(CallLog.Calls.OUTGOING_TYPE));
    }

    @MediumTest
    @Test
    public void testLogCallDirectionIngoingFromManagedProfile() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mManagedProfileAccountHandle, 0));
        Call fakeOutgoingCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mManagedProfileAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        mCallLogManager.onCallStateChanged(fakeOutgoingCall, CallState.ACTIVE,
                CallState.DISCONNECTED);

        // Incoming call using phone account in managed profile should be inserted to managed
        // profile only.
        verifyNoInsertionInUser(CURRENT_USER_ID);
        verifyNoInsertionInUser(OTHER_USER_ID);
        verifyNoInsertionInUser(PRIVATE_USER_ID);
        ContentValues insertedValues = verifyInsertionWithCapture(MANAGED_USER_ID);
        assertEquals(insertedValues.getAsInteger(CallLog.Calls.TYPE),
                Integer.valueOf(Calls.INCOMING_TYPE));
    }

    /**
     * Ensure call data usage is persisted to the call log when present in the call.
     */
    @MediumTest
    @Test
    public void testLogCallDataUsageSet() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeVideoCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                BIDIRECTIONAL_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID), // initiatingUser
                1000 // callDataUsage
        );
        mCallLogManager.onCallStateChanged(fakeVideoCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(Long.valueOf(1000), insertedValues.getAsLong(CallLog.Calls.DATA_USAGE));
    }

    /**
     * Ensures call data usage is null in the call log when not set on the call.
     */
    @MediumTest
    @Test
    public void testLogCallDataUsageNotSet() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        Call fakeVideoCall = makeFakeCall(
                DisconnectCause.OTHER, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                BIDIRECTIONAL_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID), // initiatingUser
                Call.DATA_USAGE_NOT_SET // callDataUsage
        );
        mCallLogManager.onCallStateChanged(fakeVideoCall, CallState.ACTIVE, CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertNull(insertedValues.getAsLong(CallLog.Calls.DATA_USAGE));
    }

    /**
     * Ensures missed self-managed calls are marked as read..
     */
    @MediumTest
    @Test
    public void testLogMissedSelfManaged() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle,
                        PhoneAccount.CAPABILITY_SELF_MANAGED));
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.MISSED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mSelfManagedAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeMissedCall.isSelfManaged()).thenReturn(true);
        when(fakeMissedCall.isLoggedSelfManaged()).thenReturn(true);
        when(fakeMissedCall.getHandoverState()).thenReturn(HandoverState.HANDOVER_NONE);
        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues insertedValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(1, insertedValues.getAsInteger(Calls.IS_READ).intValue());
    }

    @Test
    public void testLogCallWhenExternalCallOnWatch() {
        when(mMockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(makeFakePhoneAccount(mDefaultAccountHandle, 0 /* capabilities */));
        PackageManager packageManager = mContext.getPackageManager();
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)).thenReturn(true);
        when(mFeatureFlags.telecomLogExternalWearableCalls()).thenReturn(true);
        Call fakeMissedCall = makeFakeCall(
                DisconnectCause.REJECTED, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                null
        );
        when(fakeMissedCall.isExternalCall()).thenReturn(true);

        mCallLogManager.onCallStateChanged(fakeMissedCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        verifyInsertionWithCapture(CURRENT_USER_ID);
    }


    @SmallTest
    @Test
    public void testCountryIso_newCountryDetected() {
        Country testCountry = new Country(TEST_ISO, Country.COUNTRY_SOURCE_LOCALE);
        Country testCountry2 = new Country(TEST_ISO_2, Country.COUNTRY_SOURCE_LOCALE);
        CountryDetector mockDetector = (CountryDetector) mContext.getSystemService(
                Context.COUNTRY_DETECTOR);
        Handler handler = new Handler(Looper.getMainLooper());

        String initialIso = mCallLogManager.getCountryIso();
        assertEquals(Locale.getDefault().getCountry(), initialIso);

        ArgumentCaptor<Consumer<Country>> capture = ArgumentCaptor.forClass(Consumer.class);
        verify(mockDetector).registerCountryDetectorCallback(
                any(Executor.class), capture.capture());
        Consumer<Country> countryConsumer = capture.getValue();

        countryConsumer.accept(testCountry);
        waitForHandlerAction(handler, TEST_TIMEOUT);
        String resultIso = mCallLogManager.getCountryIso();
        assertEquals(TEST_ISO, resultIso);

        // If default locale is equal to TEST_ISO, test another ISO to assure working functionality.
        if (initialIso.equals(TEST_ISO)) {
            countryConsumer.accept(testCountry2);
            waitForHandlerAction(handler, TEST_TIMEOUT);
            resultIso = mCallLogManager.getCountryIso();
            assertEquals(TEST_ISO_2, resultIso);
        }
    }

    @SmallTest
    @Test
    public void testCallComposerElements() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        String subject = "segmentation fault";
        // =)
        double lat = 40.649723;
        double lon = -80.082090;
        Location location = new Location("");
        location.setLatitude(lat);
        location.setLongitude(lon);

        Uri fakeProviderUri = Uri.parse("content://nothing_to_see_here/12345");

        Bundle extras = new Bundle();
        extras.putInt(TelecomManager.EXTRA_PRIORITY, TelecomManager.PRIORITY_URGENT);
        extras.putString(TelecomManager.EXTRA_CALL_SUBJECT, subject);
        extras.putParcelable(TelecomManager.EXTRA_LOCATION, location);
        extras.putParcelable(TelecomManager.EXTRA_PICTURE_URI, fakeProviderUri);
        when(fakeCall.getIntentExtras()).thenReturn(extras);

        mCallLogManager.onCallStateChanged(fakeCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        ContentValues locationValues = verifyLocationInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(lat, locationValues.getAsDouble(CallLog.Locations.LATITUDE), 0);
        assertEquals(lon, locationValues.getAsDouble(CallLog.Locations.LONGITUDE), 0);

        ContentValues callLogValues = verifyInsertionWithCapture(CURRENT_USER_ID);
        assertEquals(subject, callLogValues.getAsString(Calls.SUBJECT));
        assertEquals(fakeProviderUri.toString(),
                callLogValues.getAsString(Calls.COMPOSER_PHOTO_URI));
        assertEquals(TelecomManager.PRIORITY_URGENT,
                (int) callLogValues.getAsInteger(Calls.PRIORITY));
        assertNotNull(callLogValues.getAsString(Calls.LOCATION));
    }

    @SmallTest
    @Test
    public void testDoNotLogConferenceWithNoChildren() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                true, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeCall.hadChildren()).thenReturn(false);

        assertFalse(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testDoNotLogCallExtra() {
        when(mFeatureFlags.telecomSkipLogBasedOnExtra()).thenReturn(true);
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        Bundle extras = new Bundle();
        extras.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        when(fakeCall.getExtras()).thenReturn(extras);

        assertFalse(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testIgnoresDoNotLogCallExtra_whenFlagDisabled() {
        when(mFeatureFlags.telecomSkipLogBasedOnExtra()).thenReturn(false);
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        Bundle extras = new Bundle();
        extras.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        when(fakeCall.getExtras()).thenReturn(extras);

        assertTrue(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testDoNotLogConferenceWithChildren() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                true, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeCall.hadChildren()).thenReturn(true);

        assertFalse(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testLogRemotelyHostedConferenceWithChildren() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                true, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeCall.hadChildren()).thenReturn(true);
        when(fakeCall.hasProperty(eq(Connection.PROPERTY_REMOTELY_HOSTED))).thenReturn(true);

        assertTrue(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testLogRemotelyHostedConferenceWithNoChildren() {
        Call fakeCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                true, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeCall.hadChildren()).thenReturn(false);
        when(fakeCall.hasProperty(eq(Connection.PROPERTY_REMOTELY_HOSTED))).thenReturn(true);

        assertTrue(mCallLogManager.shouldLogDisconnectedCall(fakeCall, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    @SmallTest
    @Test
    public void testDoNotLogChildOfRemotelyHostedConference() {
        Call fakeConfCall = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                true, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeConfCall.hadChildren()).thenReturn(true);
        when(fakeConfCall.hasProperty(eq(Connection.PROPERTY_REMOTELY_HOSTED))).thenReturn(true);

        Call fakeChild = makeFakeCall(
                DisconnectCause.LOCAL, // disconnectCauseCode
                false, // isConference
                true, // isIncoming
                1L, // creationTimeMillis
                1000L, // ageMillis
                TEL_PHONEHANDLE, // callHandle
                mDefaultAccountHandle, // phoneAccountHandle
                NO_VIDEO_STATE, // callVideoState
                POST_DIAL_STRING, // postDialDigits
                VIA_NUMBER_STRING, // viaNumber
                UserHandle.of(CURRENT_USER_ID)
        );
        when(fakeChild.hadChildren()).thenReturn(false);
        when(fakeChild.getParentCall()).thenReturn(fakeConfCall);
        when(fakeChild.hasProperty(eq(Connection.PROPERTY_REMOTELY_HOSTED))).thenReturn(true);

        assertFalse(mCallLogManager.shouldLogDisconnectedCall(fakeChild, CallState.DISCONNECTED,
                false /* isCanceled */));
    }

    private ArgumentCaptor<CountryListener> verifyCountryIso(CountryDetector mockDetector,
            String resultIso) {
        ArgumentCaptor<CountryListener> captor = ArgumentCaptor.forClass(CountryListener.class);
        verify(mockDetector).addCountryListener(captor.capture(), any(Looper.class));
        assertEquals(TEST_ISO, resultIso);
        return captor;
    }

    private void verifyNoInsertion() {
        SystemClock.sleep(TEST_TIMEOUT_MILLIS);

        verify(mContentProvider, never()).insert(any(Uri.class), any(ContentValues.class));
    }

    private void verifyNoInsertionInUser(int userId) {
        SystemClock.sleep(TEST_TIMEOUT_MILLIS);

        Uri uri = ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, userId);
        verify(mContentProvider, never()).insert(eq(uri), any(ContentValues.class));
    }

    private ContentValues verifyInsertionWithCapture(int userId) {
        Uri uri = ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI, userId);
        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentProvider, timeout(TEST_TIMEOUT_MILLIS).times(1)).insert(
                eq(uri), captor.capture());
        return captor.getValue();
    }

    private ContentValues verifyLocationInsertionWithCapture(int userId) {
        Uri uri = ContentProvider.maybeAddUserId(CallLog.Locations.CONTENT_URI, userId);
        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentProvider, timeout(TEST_TIMEOUT_MILLIS).times(1)).insert(
                eq(uri), captor.capture());
        return captor.getValue();
    }

    private Call makeFakeCall(int disconnectCauseCode, boolean isConference, boolean isIncoming,
            long creationTimeMillis, long ageMillis, Uri callHandle,
            PhoneAccountHandle phoneAccountHandle, int callVideoState,
            String postDialDigits, String viaNumber, UserHandle initiatingUser) {
        return makeFakeCall(disconnectCauseCode, isConference, isIncoming, creationTimeMillis,
                ageMillis, callHandle, phoneAccountHandle, callVideoState, postDialDigits,
                viaNumber, initiatingUser, Call.DATA_USAGE_NOT_SET);
    }

    private Call makeFakeCall(int disconnectCauseCode, boolean isConference, boolean isIncoming,
            long creationTimeMillis, long ageMillis, Uri callHandle,
            PhoneAccountHandle phoneAccountHandle, int callVideoState,
            String postDialDigits, String viaNumber, UserHandle initiatingUser,
            long callDataUsage) {
        Call fakeCall = mock(Call.class);
        when(fakeCall.getDisconnectCause()).thenReturn(
                new DisconnectCause(disconnectCauseCode));
        when(fakeCall.isConference()).thenReturn(isConference);
        when(fakeCall.isIncoming()).thenReturn(isIncoming);
        when(fakeCall.getCreationTimeMillis()).thenReturn(creationTimeMillis);
        when(fakeCall.getAgeMillis()).thenReturn(ageMillis);
        when(fakeCall.getOriginalHandle()).thenReturn(callHandle);
        when(fakeCall.getTargetPhoneAccount()).thenReturn(phoneAccountHandle);
        when(fakeCall.getVideoStateHistory()).thenReturn(callVideoState);
        when(fakeCall.getPostDialDigits()).thenReturn(postDialDigits);
        when(fakeCall.getViaNumber()).thenReturn(viaNumber);
        when(fakeCall.getAssociatedUser()).thenReturn(initiatingUser);
        when(fakeCall.getCallDataUsage()).thenReturn(callDataUsage);
        when(fakeCall.isEmergencyCall()).thenReturn(
                phoneAccountHandle.equals(EMERGENCY_ACCT_HANDLE));
        when(fakeCall.getParentCall()).thenReturn(null);
        when(fakeCall.hadChildren()).thenReturn(true);
        when(fakeCall.hasProperty(eq(Connection.PROPERTY_REMOTELY_HOSTED))).thenReturn(false);
        when(fakeCall.getAnalytics()).thenReturn(mCallInfo);
        return fakeCall;
    }

    private PhoneAccount makeFakePhoneAccount(PhoneAccountHandle phoneAccountHandle,
            int capabilities) {
        return PhoneAccount.builder(phoneAccountHandle, "testing")
                .setCapabilities(capabilities).build();
    }
}
