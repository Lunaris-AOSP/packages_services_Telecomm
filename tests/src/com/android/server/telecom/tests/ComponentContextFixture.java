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

import com.android.server.telecom.flags.FeatureFlags;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IInCallService;

import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.StatsManager;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.app.role.RoleManager;
import android.content.AttributionSource;
import android.content.AttributionSourceState;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.location.CountryDetector;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BugreportManager;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IInterface;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.permission.PermissionCheckerManager;
import android.provider.BlockedNumbersManager;
import android.telecom.ConnectionService;
import android.telecom.Log;
import android.telecom.InCallService;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import android.test.mock.MockContext;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import static android.content.Context.DEVICE_ID_DEFAULT;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Controls a test {@link Context} as would be provided by the Android framework to an
 * {@code Activity}, {@code Service} or other system-instantiated component.
 *
 * The {@link Context} created by this object is "hollow" but its {@code applicationContext}
 * property points to an application context implementing all the nontrivial functionality.
 */
public class ComponentContextFixture implements TestFixture<Context> {
    private HandlerThread mHandlerThread;
    private Map<UserHandle, Context> mContextsByUser = new HashMap<>();

    public class FakeApplicationContext extends MockContext {
        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public Executor getMainExecutor() {
            // TODO: This doesn't actually execute anything as we don't need to do so for now, but
            //  future users might need it.
            return mMainExecutor;
        }

        @Override
        public Context createContextAsUser(UserHandle userHandle, int flags) {
            if (mContextsByUser.containsKey(userHandle)) {
                return mContextsByUser.get(userHandle);
            }
            return this;
        }

        @Override
        public Context createAttributionContext(String attributionTag) { return this; }

        @Override
        public String getPackageName() {
            return "com.android.server.telecom.tests";
        }

        @Override
        public String getPackageResourcePath() {
            return "/tmp/i/dont/know";
        }

        @Override
        public Context getApplicationContext() {
            return mApplicationContextSpy;
        }

        @Override
        public Resources.Theme getTheme() {
            return mResourcesTheme;
        }

        @Override
        public File getFilesDir() {
            try {
                return File.createTempFile("temp", "temp").getParentFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean bindServiceAsUser(
                Intent serviceIntent,
                ServiceConnection connection,
                int flags,
                UserHandle userHandle) {
            // TODO: Implement "as user" functionality
            return bindService(serviceIntent, connection, flags);
        }

        @Override
        public boolean bindService(
                Intent serviceIntent,
                ServiceConnection connection,
                int flags) {
            if (mServiceByServiceConnection.containsKey(connection)) {
                throw new RuntimeException("ServiceConnection already bound: " + connection);
            }
            IInterface service = mServiceByComponentName.get(serviceIntent.getComponent());
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: "
                        + serviceIntent.getComponent());
            }
            mServiceByServiceConnection.put(connection, service);
            connection.onServiceConnected(serviceIntent.getComponent(), service.asBinder());
            return true;
        }

        @Override
        public void unbindService(
                ServiceConnection connection) {
            IInterface service = mServiceByServiceConnection.remove(connection);
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: " + connection);
            }
            connection.onServiceDisconnected(mComponentNameByService.get(service));
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.AUDIO_SERVICE:
                    return mAudioManager;
                case Context.TELEPHONY_SERVICE:
                    return mTelephonyManager;
                case Context.LOCATION_SERVICE:
                    return mLocationManager;
                case Context.APP_OPS_SERVICE:
                    return mAppOpsManager;
                case Context.NOTIFICATION_SERVICE:
                    return mNotificationManager;
                case Context.STATUS_BAR_SERVICE:
                    return mStatusBarManager;
                case Context.USER_SERVICE:
                    return mUserManager;
                case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                    return mSubscriptionManager;
                case Context.TELECOM_SERVICE:
                    return mTelecomManager;
                case Context.CARRIER_CONFIG_SERVICE:
                    return mCarrierConfigManager;
                case Context.COUNTRY_DETECTOR:
                    return mCountryDetector;
                case Context.ROLE_SERVICE:
                    return mRoleManager;
                case Context.TELEPHONY_REGISTRY_SERVICE:
                    return mTelephonyRegistryManager;
                case Context.UI_MODE_SERVICE:
                    return mUiModeManager;
                case Context.VIBRATOR_SERVICE:
                    return mVibrator;
                case Context.VIBRATOR_MANAGER_SERVICE:
                    return mVibratorManager;
                case Context.PERMISSION_CHECKER_SERVICE:
                    return mPermissionCheckerManager;
                case Context.SENSOR_PRIVACY_SERVICE:
                    return mSensorPrivacyManager;
                case Context.ACCESSIBILITY_SERVICE:
                    return mAccessibilityManager;
                case Context.BLOCKED_NUMBERS_SERVICE:
                    return mBlockedNumbersManager;
                case Context.STATS_MANAGER_SERVICE:
                    return mStatsManager;
                default:
                    return null;
            }
        }

        @Override
        public String getSystemServiceName(Class<?> svcClass) {
            if (svcClass == UserManager.class) {
                return Context.USER_SERVICE;
            } else if (svcClass == RoleManager.class) {
                return Context.ROLE_SERVICE;
            } else if (svcClass == AudioManager.class) {
                return Context.AUDIO_SERVICE;
            } else if (svcClass == TelephonyManager.class) {
                return Context.TELEPHONY_SERVICE;
            } else if (svcClass == CarrierConfigManager.class) {
                return Context.CARRIER_CONFIG_SERVICE;
            } else if (svcClass == SubscriptionManager.class) {
                return Context.TELEPHONY_SUBSCRIPTION_SERVICE;
            } else if (svcClass == TelephonyRegistryManager.class) {
                return Context.TELEPHONY_REGISTRY_SERVICE;
            } else if (svcClass == UiModeManager.class) {
                return Context.UI_MODE_SERVICE;
            } else if (svcClass == Vibrator.class) {
                return Context.VIBRATOR_SERVICE;
            } else if (svcClass == VibratorManager.class) {
                return Context.VIBRATOR_MANAGER_SERVICE;
            } else if (svcClass == PermissionCheckerManager.class) {
                return Context.PERMISSION_CHECKER_SERVICE;
            } else if (svcClass == SensorPrivacyManager.class) {
                return Context.SENSOR_PRIVACY_SERVICE;
            } else if (svcClass == NotificationManager.class) {
                return Context.NOTIFICATION_SERVICE;
            } else if (svcClass == AccessibilityManager.class) {
                return Context.ACCESSIBILITY_SERVICE;
            } else if (svcClass == DropBoxManager.class) {
                return Context.DROPBOX_SERVICE;
            } else if (svcClass == BugreportManager.class) {
                return Context.BUGREPORT_SERVICE;
            } else if (svcClass == TelecomManager.class) {
                return Context.TELECOM_SERVICE;
            } else if (svcClass == BlockedNumbersManager.class) {
                return Context.BLOCKED_NUMBERS_SERVICE;
            } else if (svcClass == AppOpsManager.class) {
                return Context.APP_OPS_SERVICE;
            } else if (svcClass == StatsManager.class) {
                return Context.STATS_MANAGER_SERVICE;
            }
            throw new UnsupportedOperationException(svcClass.getName());
        }

        @Override
        public int getUserId() {
            return 0;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public String getOpPackageName() {
            return "com.android.server.telecom.tests";
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return mTestApplicationInfo;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return mAttributionSource;
        }

        @Override
        public Looper getMainLooper() {
            if (mHandlerThread == null) {
                mHandlerThread = new HandlerThread(this.getClass().getSimpleName());
                mHandlerThread.start();
            }
            return mHandlerThread.getLooper();
        }

        @Override
        public ContentResolver getContentResolver() {
            return new ContentResolver(mApplicationContextSpy) {
                @Override
                protected IContentProvider acquireProvider(Context c, String name) {
                    Log.i(this, "acquireProvider %s", name);
                    return getOrCreateProvider(name);
                }

                @Override
                public boolean releaseProvider(IContentProvider icp) {
                    return true;
                }

                @Override
                protected IContentProvider acquireUnstableProvider(Context c, String name) {
                    Log.i(this, "acquireUnstableProvider %s", name);
                    return getOrCreateProvider(name);
                }

                private IContentProvider getOrCreateProvider(String name) {
                    if (!mIContentProviderByUri.containsKey(name)) {
                        mIContentProviderByUri.put(name, mock(IContentProvider.class));
                    }
                    return mIContentProviderByUri.get(name);
                }

                @Override
                public boolean releaseUnstableProvider(IContentProvider icp) {
                    return false;
                }

                @Override
                public void unstableProviderDied(IContentProvider icp) {
                }
            };
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            mBroadcastReceivers.add(receiver);
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            mBroadcastReceivers.add(receiver);
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler) {
            mBroadcastReceivers.add(receiver);
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler, int flags) {
            mBroadcastReceivers.add(receiver);
            return null;
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle handle,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            mBroadcastReceivers.add(receiver);
            return null;
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendBroadcast(Intent intent, String receiverPermission) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle userHandle) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {
            // Override so that this can be verified via spy.
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
                Bundle options) {
            // Override so that this can be verified via spy.
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
                int appOp) {
            // Override so that this can be verified via spy.
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
                int initialCode, String initialData, Bundle initialExtras) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
                Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, int appOp, Bundle options,
                BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
                String initialData, Bundle initialExtras) {
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            return this;
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            // Don't bother enforcing anything in mock.
        }

        @Override
        public void enforcePermission(
                String permission, int pid, int uid, String message) {
            // By default, don't enforce anything in mock.
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle userHandle) {
            // For capturing
        }
    }

    public class FakeAudioManager extends AudioManager {

        private boolean mMute = false;
        private boolean mSpeakerphoneOn = false;
        private int mAudioStreamValue = 1;
        private int mMode = AudioManager.MODE_NORMAL;
        private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
        private AudioDeviceInfo mCommunicationDevice;

        public FakeAudioManager(Context context) {
            super(context);
        }

        @Override
        public void setMicrophoneMute(boolean value) {
            mMute = value;
        }

        @Override
        public boolean isMicrophoneMute() {
            return mMute;
        }

        @Override
        public void setSpeakerphoneOn(boolean value) {
            mSpeakerphoneOn = value;
        }

        @Override
        public boolean isSpeakerphoneOn() {
            return mSpeakerphoneOn;
        }

        @Override
        public void setMode(int mode) {
            mMode = mode;
        }

        @Override
        public int getMode() {
            return mMode;
        }

        @Override
        public void setRingerModeInternal(int ringerMode) {
            mRingerMode = ringerMode;
        }

        @Override
        public int getRingerModeInternal() {
            return mRingerMode;
        }

        @Override
        public void setStreamVolume(int streamTypeUnused, int index, int flagsUnused){
            mAudioStreamValue = index;
        }

        @Override
        public int getStreamVolume(int streamValueUnused) {
            return mAudioStreamValue;
        }

        @Override
        public void clearCommunicationDevice() {
            mCommunicationDevice = null;
        }

        @Override
        public AudioDeviceInfo getCommunicationDevice() {
            return mCommunicationDevice;
        }

        @Override
        public boolean setCommunicationDevice(AudioDeviceInfo device) {
            mCommunicationDevice = device;
            return true;
        }
    }

    private static final String PACKAGE_NAME = "com.android.server.telecom.tests";
    private final AttributionSource mAttributionSource =
            new AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE_NAME).build();

    private final Multimap<String, ComponentName> mComponentNamesByAction =
            ArrayListMultimap.create();
    private final Map<ComponentName, IInterface> mServiceByComponentName = new HashMap<>();
    private final Map<ComponentName, ServiceInfo> mServiceInfoByComponentName = new HashMap<>();
    private final Map<ComponentName, ActivityInfo> mActivityInfoByComponentName = new HashMap<>();
    private final Map<IInterface, ComponentName> mComponentNameByService = new HashMap<>();
    private final Map<ServiceConnection, IInterface> mServiceByServiceConnection = new HashMap<>();

    private final Context mContext = new MockContext() {
        @Override
        public Context getApplicationContext() {
            return mApplicationContextSpy;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public int getDeviceId() {
          return DEVICE_ID_DEFAULT;
        }
    };

    // The application context is the most important object this class provides to the system
    // under test.
    private final Context mApplicationContext = new FakeApplicationContext();

    // We then create a spy on the application context allowing standard Mockito-style
    // when(...) logic to be used to add specific little responses where needed.

    private final Resources.Theme mResourcesTheme = mock(Resources.Theme.class);
    private final Resources mResources = mock(Resources.class);
    private final Context mApplicationContextSpy = spy(mApplicationContext);
    private final DisplayMetrics mDisplayMetrics = mock(DisplayMetrics.class);
    private final PackageManager mPackageManager = mock(PackageManager.class);
    private final Executor mMainExecutor = mock(Executor.class);
    private final AudioManager mAudioManager = spy(new FakeAudioManager(mContext));
    private final TelephonyManager mTelephonyManager = mock(TelephonyManager.class);
    private final LocationManager mLocationManager = mock(LocationManager.class);
    private final AppOpsManager mAppOpsManager = mock(AppOpsManager.class);
    private final NotificationManager mNotificationManager = mock(NotificationManager.class);
    private final AccessibilityManager mAccessibilityManager = mock(AccessibilityManager.class);
    private final UserManager mUserManager = mock(UserManager.class);
    private final StatusBarManager mStatusBarManager = mock(StatusBarManager.class);
    private SubscriptionManager mSubscriptionManager = mock(SubscriptionManager.class);
    private final CarrierConfigManager mCarrierConfigManager = mock(CarrierConfigManager.class);
    private final CountryDetector mCountryDetector = mock(CountryDetector.class);
    private final Map<String, IContentProvider> mIContentProviderByUri = new HashMap<>();
    private final Configuration mResourceConfiguration = new Configuration();
    private final ApplicationInfo mTestApplicationInfo = new ApplicationInfo();
    private final RoleManager mRoleManager = mock(RoleManager.class);
    private final TelephonyRegistryManager mTelephonyRegistryManager =
            mock(TelephonyRegistryManager.class);
    private final Vibrator mVibrator = mock(Vibrator.class);
    private final VibratorManager mVibratorManager = mock(VibratorManager.class);
    private final UiModeManager mUiModeManager = mock(UiModeManager.class);
    private final PermissionCheckerManager mPermissionCheckerManager =
            mock(PermissionCheckerManager.class);
    private final PermissionInfo mPermissionInfo = mock(PermissionInfo.class);
    private final SensorPrivacyManager mSensorPrivacyManager = mock(SensorPrivacyManager.class);
    private final List<BroadcastReceiver> mBroadcastReceivers = new ArrayList<>();
    private final StatsManager mStatsManager = mock(StatsManager.class);

    private TelecomManager mTelecomManager = mock(TelecomManager.class);
    private BlockedNumbersManager mBlockedNumbersManager = mock(BlockedNumbersManager.class);

    public ComponentContextFixture(FeatureFlags featureFlags) {
        MockitoAnnotations.initMocks(this);
        when(featureFlags.telecomResolveHiddenDependencies()).thenReturn(true);
        when(mResources.getConfiguration()).thenReturn(mResourceConfiguration);
        when(mResources.getString(anyInt())).thenReturn("");
        when(mResources.getStringArray(anyInt())).thenReturn(new String[0]);
        when(mResources.newTheme()).thenReturn(mResourcesTheme);
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 3.125f;
        mResourceConfiguration.setLocale(Locale.TAIWAN);

        // TODO: Move into actual tests
        doReturn(false).when(mAudioManager).isWiredHeadsetOn();

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServices((Intent) any(), anyInt());

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServicesAsUser((Intent) any(), anyInt(), anyInt());

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentReceivers(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryBroadcastReceivers((Intent) any(), anyInt());

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentReceivers(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryBroadcastReceiversAsUser((Intent) any(), anyInt(), anyInt());

        // By default, tests use non-ui apps instead of 3rd party companion apps.
        when(mPermissionCheckerManager.checkPermission(
                matches(Manifest.permission.CALL_COMPANION_APP), any(AttributionSourceState.class),
                nullable(String.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(PermissionCheckerManager.PERMISSION_HARD_DENIED);

        try {
            when(mPackageManager.getPermissionInfo(anyString(), anyInt())).thenReturn(
                    mPermissionInfo);
        } catch (PackageManager.NameNotFoundException ex) {
        }

        when(mPermissionInfo.isAppOp()).thenReturn(true);
        when(mVibrator.getDefaultVibrationIntensity(anyInt()))
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorManager.getVibratorIds()).thenReturn(new int[0]);
        when(mVibratorManager.getDefaultVibrator()).thenReturn(mVibrator);

        // Used in CreateConnectionProcessor to rank emergency numbers by viability.
        // For the test, make them all equal to INVALID so that the preferred PhoneAccount will be
        // chosen.
        when(mTelephonyManager.getSubscriptionId(any())).thenReturn(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        when(mTelephonyManager.getNetworkOperatorName()).thenReturn("label1");
        when(mTelephonyManager.getMaxNumberOfSimultaneouslyActiveSims()).thenReturn(1);
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mResources.getBoolean(eq(R.bool.grant_location_permission_enabled))).thenReturn(false);
        doAnswer(new Answer<Void>(){
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                return null;
            }
        }).when(mAppOpsManager).checkPackage(anyInt(), anyString());

        when(mNotificationManager.matchesCallFilter(any(Uri.class))).thenReturn(true);

        when(mCarrierConfigManager.getConfig()).thenReturn(new PersistableBundle());
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(new PersistableBundle());

        when(mUserManager.getSerialNumberForUser(any(UserHandle.class))).thenReturn(-1L);

        doReturn(null).when(mApplicationContextSpy).registerReceiver(any(BroadcastReceiver.class),
                any(IntentFilter.class));

        // Make sure we do not hide PII during testing.
        Log.setTag("TelecomTEST");
        Log.setIsExtendedLoggingEnabled(true);
        Log.setUnitTestingEnabled(true);
        Log.VERBOSE = true;
    }

    public void destroy() {
        if (mHandlerThread == null) return;
        mHandlerThread.quit();
        try {
            mHandlerThread.join();
        } catch (InterruptedException ex) {
            Log.w(this, "HandlerThread join interrupted", ex);
        }
        mHandlerThread = null;
    }

    @Override
    public Context getTestDouble() {
        return mContext;
    }

    public void addConnectionService(
            ComponentName componentName,
            IConnectionService service)
            throws Exception {
        addService(ConnectionService.SERVICE_INTERFACE, componentName, service);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = android.Manifest.permission.BIND_CONNECTION_SERVICE;
        serviceInfo.packageName = componentName.getPackageName();
        serviceInfo.name = componentName.getClassName();
        mServiceInfoByComponentName.put(componentName, serviceInfo);
    }

    public void removeConnectionService(
            ComponentName componentName,
            IConnectionService service)
            throws Exception {
        removeService(ConnectionService.SERVICE_INTERFACE, componentName, service);
        mServiceInfoByComponentName.remove(componentName);
    }

    public void addInCallService(
            ComponentName componentName,
            IInCallService service,
            int uid)
            throws Exception {
        addService(InCallService.SERVICE_INTERFACE, componentName, service);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = android.Manifest.permission.BIND_INCALL_SERVICE;
        serviceInfo.packageName = componentName.getPackageName();
        serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.applicationInfo.uid = uid;
        serviceInfo.metaData = new Bundle();
        serviceInfo.metaData.putBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI, false);
        serviceInfo.name = componentName.getClassName();
        mServiceInfoByComponentName.put(componentName, serviceInfo);

        // Used in InCallController to check permissions for CONTROL_INCALL_fvEXPERIENCE
        when(mPackageManager.getPackagesForUid(eq(uid))).thenReturn(new String[] {
                componentName.getPackageName() });
        when(mPackageManager.checkPermission(eq(Manifest.permission.CONTROL_INCALL_EXPERIENCE),
                eq(componentName.getPackageName()))).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mPackageManager.checkPermission(eq(Manifest.permission.INTERACT_ACROSS_USERS),
                eq(componentName.getPackageName()))).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mPermissionCheckerManager.checkPermission(
                eq(Manifest.permission.CONTROL_INCALL_EXPERIENCE),
                any(AttributionSourceState.class), anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
    }

    public void addIntentReceiver(String action, ComponentName name) {
        mComponentNamesByAction.put(action, name);
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = name.getPackageName();
        activityInfo.name = name.getClassName();
        mActivityInfoByComponentName.put(name, activityInfo);
    }

    public void putResource(int id, final String value) {
        when(mResources.getText(eq(id))).thenReturn(value);
        when(mResources.getString(eq(id))).thenReturn(value);
        when(mResources.getString(eq(id), any())).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                return String.format(value, Arrays.copyOfRange(args, 1, args.length));
            }
        });
    }

    public void putFloatResource(int id, final float value) {
        when(mResources.getFloat(eq(id))).thenReturn(value);
    }

    public void putBooleanResource(int id, boolean value) {
        when(mResources.getBoolean(eq(id))).thenReturn(value);
    }

    public void putStringArrayResource(int id, String[] value) {
        when(mResources.getStringArray(eq(id))).thenReturn(value);
    }

    public void putRawResource(int id, String content) {
        when(mResources.openRawResource(id))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    public void setTelecomManager(TelecomManager telecomManager) {
        mTelecomManager = telecomManager;
    }

    public void setSubscriptionManager(SubscriptionManager subscriptionManager) {
        mSubscriptionManager = subscriptionManager;
    }

    public SubscriptionManager getSubscriptionManager() {
        return mSubscriptionManager;
    }

    public TelephonyManager getTelephonyManager() {
        return mTelephonyManager;
    }

    public AudioManager getAudioManager() {
        return mAudioManager;
    }

    public CarrierConfigManager getCarrierConfigManager() {
        return mCarrierConfigManager;
    }

    public NotificationManager getNotificationManager() {
        return mNotificationManager;
    }

    public List<BroadcastReceiver> getBroadcastReceivers() {
        return mBroadcastReceivers;
    }

    public TelephonyRegistryManager getTelephonyRegistryManager() {
        return mTelephonyRegistryManager;
    }

    /**
     * For testing purposes, add a context for a specific user.
     * @param userHandle the userhandle
     * @param context the context
     */
    public void addContextForUser(UserHandle userHandle, Context context) {
        mContextsByUser.put(userHandle, context);
    }

    private void addService(String action, ComponentName name, IInterface service) {
        mComponentNamesByAction.put(action, name);
        mServiceByComponentName.put(name, service);
        mComponentNameByService.put(service, name);
    }

    private void removeService(String action, ComponentName name, IInterface service) {
        mComponentNamesByAction.remove(action, name);
        mServiceByComponentName.remove(name);
        mComponentNameByService.remove(service);
    }

    private List<ResolveInfo> doQueryIntentServices(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ComponentName componentName : mComponentNamesByAction.get(intent.getAction())) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.serviceInfo = mServiceInfoByComponentName.get(componentName);
            resolveInfo.serviceInfo.metaData = new Bundle();
            resolveInfo.serviceInfo.metaData.putBoolean(
                    TelecomManager.METADATA_INCLUDE_EXTERNAL_CALLS, true);
            result.add(resolveInfo);
        }
        return result;
    }

    private List<ResolveInfo> doQueryIntentReceivers(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ComponentName componentName : mComponentNamesByAction.get(intent.getAction())) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = mActivityInfoByComponentName.get(componentName);
            result.add(resolveInfo);
        }
        return result;
    }
}
