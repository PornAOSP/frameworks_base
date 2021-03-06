/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.computeNextCycleBoundary;
import static android.net.NetworkPolicyManager.uidPoliciesToString;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.TrafficStats.KB_IN_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_USE_PLATFORM_DEFAULT;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_THRESHOLD_DISABLED;
import static android.telephony.CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG;
import static android.telephony.CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG;
import static android.telephony.CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.Time.TIMEZONE_UTC;

import static com.android.server.net.NetworkPolicyManagerService.MAX_PROC_STATE_SEQ_HISTORY;
import static com.android.server.net.NetworkPolicyManagerService.ProcStateSeqHistory;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT_SNOOZED;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_WARNING;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkPolicy;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.INetworkManagementService;
import android.os.PersistableBundle;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.UserHandle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.util.TrustedTime;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.BroadcastInterceptingContext.FutureIntent;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkPolicyManagerService;

import libcore.io.IoUtils;
import libcore.io.Streams;

import com.google.common.util.concurrent.AbstractFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 *
 * <p>Typical usage:
 *
 * <pre><code>
    m -j32 FrameworksServicesTests && adb install -r -g \
    ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk && \
    adb shell am instrument -e class "com.android.server.NetworkPolicyManagerServiceTest" -w \
    "com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner"
 * </code></pre>
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class NetworkPolicyManagerServiceTest {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private static final long TEST_START = 1194220800000L;
    private static final String TEST_IFACE = "test0";
    private static final String TEST_SSID = "AndroidAP";

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static NetworkTemplate sTemplateWifi = NetworkTemplate.buildTemplateWifi(TEST_SSID);

    /**
     * Path on assets where files used by {@link NetPolicyXml} are located.
     */
    private static final String NETPOLICY_DIR = "NetworkPolicyManagerServiceTest/netpolicy";

    private BroadcastInterceptingContext mServiceContext;
    private File mPolicyDir;

    /**
     * Relative path of the XML file that will be used as {@code netpolicy.xml}.
     *
     * <p>Typically set through a {@link NetPolicyXml} annotation in the test method.
     */
    private String mNetpolicyXml;

    private @Mock IActivityManager mActivityManager;
    private @Mock INetworkStatsService mStatsService;
    private @Mock INetworkManagementService mNetworkManager;
    private @Mock TrustedTime mTime;
    private @Mock IConnectivityManager mConnManager;
    private @Mock INotificationManager mNotifManager;
    private @Mock PackageManager mPackageManager;
    private @Mock IPackageManager mIpm;
    private @Mock SubscriptionManager mSubscriptionManager;
    private @Mock CarrierConfigManager mCarrierConfigManager;
    private @Mock TelephonyManager mTelephonyManager;

    private static ActivityManagerInternal mActivityManagerInternal;

    private IUidObserver mUidObserver;
    private INetworkManagementEventObserver mNetworkObserver;

    private NetworkPolicyListenerAnswer mPolicyListener;
    private NetworkPolicyManagerService mService;

    /**
     * In some of the tests while initializing NetworkPolicyManagerService,
     * ACTION_RESTRICT_BACKGROUND_CHANGED is broadcasted. This is for capturing that broadcast.
     */
    private FutureIntent mFutureIntent;

    private long mStartTime;
    private long mElapsedRealtime;

    private static final int USER_ID = 0;
    private static final int FAKE_SUB_ID = 3737373;
    private static final String FAKE_SUBSCRIBER_ID = "FAKE_SUB_ID";
    private static final int DEFAULT_CYCLE_DAY = 1;
    private static final int INVALID_CARRIER_CONFIG_VALUE = -9999;
    private long mDefaultWarningBytes; // filled in with the actual default before tests are run
    private long mDefaultLimitBytes; // filled in with the actual default before tests are run

    private static final int APP_ID_A = android.os.Process.FIRST_APPLICATION_UID + 4;
    private static final int APP_ID_B = android.os.Process.FIRST_APPLICATION_UID + 8;
    private static final int APP_ID_C = android.os.Process.FIRST_APPLICATION_UID + 15;
    private static final int APP_ID_D = android.os.Process.FIRST_APPLICATION_UID + 16;
    private static final int APP_ID_E = android.os.Process.FIRST_APPLICATION_UID + 23;
    private static final int APP_ID_F = android.os.Process.FIRST_APPLICATION_UID + 42;

    private static final int UID_A = UserHandle.getUid(USER_ID, APP_ID_A);
    private static final int UID_B = UserHandle.getUid(USER_ID, APP_ID_B);
    private static final int UID_C = UserHandle.getUid(USER_ID, APP_ID_C);
    private static final int UID_D = UserHandle.getUid(USER_ID, APP_ID_D);
    private static final int UID_E = UserHandle.getUid(USER_ID, APP_ID_E);
    private static final int UID_F = UserHandle.getUid(USER_ID, APP_ID_F);

    private static final String PKG_NAME_A = "name.is.A,pkg.A";

    public final @Rule NetPolicyMethodRule mNetPolicyXmlRule = new NetPolicyMethodRule();

    private void registerLocalServices() {
        addLocalServiceMock(DeviceIdleController.LocalService.class);

        final UsageStatsManagerInternal usageStats =
                addLocalServiceMock(UsageStatsManagerInternal.class);
        when(usageStats.getIdleUidsForUser(anyInt())).thenReturn(new int[]{});

        mActivityManagerInternal = addLocalServiceMock(ActivityManagerInternal.class);

        final PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(false).build();
        final PowerManagerInternal pmInternal = addLocalServiceMock(PowerManagerInternal.class);
        when(pmInternal.getLowPowerState(anyInt())).thenReturn(state);
    }

    @Before
    public void callSystemReady() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getContext();

        setCurrentTimeMillis(TEST_START);

        registerLocalServices();
        // Intercept various broadcasts, and pretend that uids have packages.
        // Also return mock service instances for a few critical services.
        mServiceContext = new BroadcastInterceptingContext(context) {
            @Override
            public PackageManager getPackageManager() {
                return mPackageManager;
            }

            @Override
            public void startActivity(Intent intent) {
                // ignored
            }

            @Override
            public Object getSystemService(String name) {
                switch (name) {
                    case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                        return mSubscriptionManager;
                    case Context.CARRIER_CONFIG_SERVICE:
                        return mCarrierConfigManager;
                    case Context.TELEPHONY_SERVICE:
                        return mTelephonyManager;
                    default:
                        return super.getSystemService(name);
                }
            }
        };

        setNetpolicyXml(context);

        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mUidObserver = (IUidObserver) invocation.getArguments()[0];
                Log.d(TAG, "set mUidObserver to " + mUidObserver);
                return null;
            }
        }).when(mActivityManager).registerUidObserver(any(), anyInt(),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN), isNull(String.class));

        mFutureIntent = newRestrictBackgroundChangedFuture();
        mService = new NetworkPolicyManagerService(mServiceContext, mActivityManager, mStatsService,
                mNetworkManager, mIpm, mTime, mPolicyDir, true);
        mService.bindConnectivityManager(mConnManager);
        mService.bindNotificationManager(mNotifManager);
        mPolicyListener = new NetworkPolicyListenerAnswer(mService);

        // Sets some common expectations.
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenAnswer(
                new Answer<PackageInfo>() {

                    @Override
                    public PackageInfo answer(InvocationOnMock invocation) throws Throwable {
                        final String packageName = (String) invocation.getArguments()[0];
                        final PackageInfo info = new PackageInfo();
                        final Signature signature;
                        if ("android".equals(packageName)) {
                            signature = new Signature("F00D");
                        } else {
                            signature = new Signature("DEAD");
                        }
                        info.signatures = new Signature[] {
                            signature
                        };
                        return info;
                    }
                });
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(new ApplicationInfo());
        when(mPackageManager.getPackagesForUid(UID_A)).thenReturn(new String[] {PKG_NAME_A});
        when(mNetworkManager.isBandwidthControlEnabled()).thenReturn(true);
        expectCurrentTime();

        // Prepare NPMS.
        mService.systemReady(mService.networkScoreAndNetworkManagementServiceReady());

        // catch INetworkManagementEventObserver during systemReady()
        final ArgumentCaptor<INetworkManagementEventObserver> networkObserver =
              ArgumentCaptor.forClass(INetworkManagementEventObserver.class);
        verify(mNetworkManager).registerObserver(networkObserver.capture());
        mNetworkObserver = networkObserver.getValue();

        NetworkPolicy defaultPolicy = mService.buildDefaultMobilePolicy(0, "");
        mDefaultWarningBytes = defaultPolicy.warningBytes;
        mDefaultLimitBytes = defaultPolicy.limitBytes;
    }

    @After
    public void removeFiles() throws Exception {
        for (File file : mPolicyDir.listFiles()) {
            file.delete();
        }
    }

    @After
    public void unregisterLocalServices() throws Exception {
        // Registered by NetworkPolicyManagerService's constructor.
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);

        // Added in registerLocalServices()
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(DeviceIdleController.LocalService.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Test
    public void testTurnRestrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOff(); // Sanity check.
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        assertRestrictBackgroundChangedReceived(futureIntent, null);
    }

    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testTurnRestrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOn(); // Sanity check.
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(false);
        assertRestrictBackgroundChangedReceived(futureIntent, null);
    }

    /**
     * Adds whitelist when restrict background is on - app should receive an intent.
     */
    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testAddRestrictBackgroundWhitelist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn(); // Sanity check.
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        addRestrictBackgroundWhitelist(true);
    }

    /**
     * Adds whitelist when restrict background is off - app should not receive an intent.
     */
    @Test
    public void testAddRestrictBackgroundWhitelist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff(); // Sanity check.
        addRestrictBackgroundWhitelist(false);
    }

    private void addRestrictBackgroundWhitelist(boolean expectIntent) throws Exception {
        // Sanity checks.
        assertWhitelistUids();
        assertUidPolicy(UID_A, POLICY_NONE);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);

        assertWhitelistUids(UID_A);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_ALLOW_METERED_BACKGROUND);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Removes whitelist when restrict background is on - app should receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-whitelisted-restrict-background-on.xml")
    public void testRemoveRestrictBackgroundWhitelist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn(); // Sanity check.
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        removeRestrictBackgroundWhitelist(true);
    }

    /**
     * Removes whitelist when restrict background is off - app should not receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-whitelisted-restrict-background-off.xml")
    public void testRemoveRestrictBackgroundWhitelist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff(); // Sanity check.
        removeRestrictBackgroundWhitelist(false);
    }

    @Test
    public void testLowPowerModeObserver_ListenersRegistered()
            throws Exception {
        PowerManagerInternal pmInternal = LocalServices.getService(PowerManagerInternal.class);

        verify(pmInternal, atLeast(2)).registerLowPowerModeObserver(any());
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_RestrictOnBeforeBsm_RestrictOnAfterBsm()
            throws Exception {
        setRestrictBackground(true);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be on even though battery saver want to turn it off
        assertThat(mService.getRestrictBackground()).isTrue();

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be on, following its previous state
        assertThat(mService.getRestrictBackground()).isTrue();
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_RestrictOffBeforeBsm_RestrictOffAfterBsm()
            throws Exception {
        setRestrictBackground(false);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(true)
                .build();

        doReturn(true).when(mNetworkManager).setDataSaverModeEnabled(true);
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be turned on because of battery saver
        assertThat(mService.getRestrictBackground()).isTrue();

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be off, following its previous state
        assertThat(mService.getRestrictBackground()).isFalse();
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_StatusChangedInBsm_DoNotRestore()
            throws Exception {
        setRestrictBackground(true);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(true)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should still be on
        assertThat(mService.getRestrictBackground()).isTrue();

        // User turns off RestrictBackground manually
        setRestrictBackground(false);
        PowerSaveState stateOff = new PowerSaveState.Builder().setBatterySaverEnabled(
                false).build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be off because user changes it manually
        assertThat(mService.getRestrictBackground()).isFalse();
    }

    private void removeRestrictBackgroundWhitelist(boolean expectIntent) throws Exception {
        // Sanity checks.
        assertWhitelistUids(UID_A);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_NONE);

        assertWhitelistUids();
        assertUidPolicy(UID_A, POLICY_NONE);
        mPolicyListener.waitAndVerify().onUidPoliciesChanged(APP_ID_A, POLICY_NONE);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Adds blacklist when restrict background is on - app should not receive an intent.
     */
    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testAddRestrictBackgroundBlacklist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn(); // Sanity check.
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        addRestrictBackgroundBlacklist(false);
    }

    /**
     * Adds blacklist when restrict background is off - app should receive an intent.
     */
    @Test
    public void testAddRestrictBackgroundBlacklist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff(); // Sanity check.
        addRestrictBackgroundBlacklist(true);
    }

    private void addRestrictBackgroundBlacklist(boolean expectIntent) throws Exception {
        assertUidPolicy(UID_A, POLICY_NONE); // Sanity check.
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);

        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Removes blacklist when restrict background is on - app should not receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-blacklisted-restrict-background-on.xml")
    public void testRemoveRestrictBackgroundBlacklist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn(); // Sanity check.
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        removeRestrictBackgroundBlacklist(false);
    }

    /**
     * Removes blacklist when restrict background is off - app should receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-blacklisted-restrict-background-off.xml")
    public void testRemoveRestrictBackgroundBlacklist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff(); // Sanity check.
        removeRestrictBackgroundBlacklist(true);
    }

    private void removeRestrictBackgroundBlacklist(boolean expectIntent) throws Exception {
        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND); // Sanity check.
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_NONE);

        assertUidPolicy(UID_A, POLICY_NONE);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_NONE);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    @Test
    @NetPolicyXml("uidA-blacklisted-restrict-background-on.xml")
    public void testBlacklistedAppIsNotNotifiedWhenRestrictBackgroundIsOn() throws Exception {
        // Sanity checks.
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        futureIntent.assertNotReceived();
    }

    @Test
    @NetPolicyXml("uidA-whitelisted-restrict-background-on.xml")
    public void testWhitelistedAppIsNotNotifiedWhenRestrictBackgroundIsOn() throws Exception {
        // Sanity checks.
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertWhitelistUids(UID_A);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        futureIntent.assertNotReceived();
    }

    @Test
    @NetPolicyXml("uidA-whitelisted-restrict-background-on.xml")
    public void testWhitelistedAppIsNotifiedWhenBlacklisted() throws Exception {
        // Sanity checks.
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertWhitelistUids(UID_A);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
    }

    @Test
    @NetPolicyXml("restrict-background-lists-whitelist-format.xml")
    public void testRestrictBackgroundLists_whitelistFormat() throws Exception {
        restrictBackgroundListsTest();
    }

    @Test
    @NetPolicyXml("restrict-background-lists-uid-policy-format.xml")
    public void testRestrictBackgroundLists_uidPolicyFormat() throws Exception {
        restrictBackgroundListsTest();
    }

    private void restrictBackgroundListsTest() throws Exception {
        // UIds that are whitelisted.
        assertWhitelistUids(UID_A, UID_B, UID_C);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_C, POLICY_ALLOW_METERED_BACKGROUND);

        // UIDs that are blacklisted.
        assertUidPolicy(UID_D, POLICY_NONE);
        assertUidPolicy(UID_E, POLICY_REJECT_METERED_BACKGROUND);

        // UIDS that have legacy policies.
        assertUidPolicy(UID_F, 2); // POLICY_ALLOW_BACKGROUND_BATTERY_SAVE

        // Remove whitelist.
        mService.setUidPolicy(UID_A, POLICY_NONE);
        assertUidPolicy(UID_A, POLICY_NONE);
        assertWhitelistUids(UID_B, UID_C);

        // Add whitelist when blacklisted.
        mService.setUidPolicy(UID_E, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_E, POLICY_ALLOW_METERED_BACKGROUND);
        assertWhitelistUids(UID_B, UID_C, UID_E);

        // Add blacklist when whitelisted.
        mService.setUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND);
        assertWhitelistUids(UID_C, UID_E);
    }

    /**
     * Tests scenario where an UID had {@code restrict-background} and {@code uid-policy} tags.
     */
    @Test
    @NetPolicyXml("restrict-background-lists-mixed-format.xml")
    public void testRestrictBackgroundLists_mixedFormat() throws Exception {
        assertWhitelistUids(UID_A, UID_C, UID_D);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND); // Blacklist prevails.
        assertUidPolicy(UID_C, (POLICY_ALLOW_METERED_BACKGROUND | 2));
        assertUidPolicy(UID_D, POLICY_ALLOW_METERED_BACKGROUND);
    }

    @Test
    @NetPolicyXml("uids-with-mixed-policies.xml")
    public void testGetUidsWithPolicy() throws Exception {
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_NONE));
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND),
                UID_B, UID_D);
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_ALLOW_METERED_BACKGROUND),
                UID_E, UID_F);
        // Legacy (POLICY_ALLOW_BACKGROUND_BATTERY_SAVE)
        assertContainsInAnyOrder(mService.getUidsWithPolicy(2),
                UID_C, UID_D, UID_F);
    }

    // NOTE: testPolicyChangeTriggersListener() is too superficial, they
    // don't check for side-effects (like calls to NetworkManagementService) neither cover all
    // different modes (Data Saver, Battery Saver, Doze, App idle, etc...).
    // These scenarios are extensively tested on CTS' HostsideRestrictBackgroundNetworkTests.
    @Test
    public void testUidForeground() throws Exception {
        // push all uids into background
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, 0);
        callOnUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_SERVICE, 0);
        assertFalse(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // push one of the uids into foreground
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_TOP, 0);
        assertTrue(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // and swap another uid into foreground
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, 0);
        callOnUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_TOP, 0);
        assertFalse(mService.isUidForeground(UID_A));
        assertTrue(mService.isUidForeground(UID_B));
    }

    @Test
    public void testLastCycleBoundaryThisMonth() throws Exception {
        // assume cycle day of "5th", which should be in same month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-11-05T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 5, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonth() throws Exception {
        // assume cycle day of "20th", which should be in last month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-10-20T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 20, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryThisMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february; should go to january
        final long currentTime = parseTime("2007-02-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-01-30T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february, which should clamp
        final long currentTime = parseTime("2007-03-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-02-28T23:59:59.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testCycleBoundaryLeapYear() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 29, TIMEZONE_UTC, 1024L, 1024L, false);

        assertTimeEquals(parseTime("2012-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeLastCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));

        assertTimeEquals(parseTime("2007-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.000Z"),
                computeNextCycleBoundary(parseTime("2007-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.000Z"),
                computeLastCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneAfterUtc() throws Exception {
        // US/Central is UTC-6
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "US/Central", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-10T06:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneBeforeUtc() throws Exception {
        // Israel is UTC+2
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "Israel", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-09T22:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleSane() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 31, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED, false);
        final LinkedHashSet<Long> seen = new LinkedHashSet<Long>();

        // walk forwards, ensuring that cycle boundaries don't get stuck
        long currentCycle = computeNextCycleBoundary(parseTime("2011-08-01T00:00:00.000Z"), policy);
        for (int i = 0; i < 128; i++) {
            long nextCycle = computeNextCycleBoundary(currentCycle, policy);
            assertEqualsFuzzy(DAY_IN_MILLIS * 30, nextCycle - currentCycle, DAY_IN_MILLIS * 3);
            assertUnique(seen, nextCycle);
            currentCycle = nextCycle;
        }
    }

    @Test
    public void testLastCycleSane() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 31, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED, false);
        final LinkedHashSet<Long> seen = new LinkedHashSet<Long>();

        // walk backwards, ensuring that cycle boundaries look sane
        long currentCycle = computeLastCycleBoundary(parseTime("2011-08-04T00:00:00.000Z"), policy);
        for (int i = 0; i < 128; i++) {
            long lastCycle = computeLastCycleBoundary(currentCycle, policy);
            assertEqualsFuzzy(DAY_IN_MILLIS * 30, currentCycle - lastCycle, DAY_IN_MILLIS * 3);
            assertUnique(seen, lastCycle);
            currentCycle = lastCycle;
        }
    }

    @Test
    public void testCycleTodayJanuary() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 14, "US/Pacific", 1024L, 1024L, false);

        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));

        assertTimeEquals(parseTime("2012-12-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));
    }

    @Test
    public void testLastCycleBoundaryDST() throws Exception {
        final long currentTime = parseTime("1989-01-02T07:30:00.000");
        final long expectedCycle = parseTime("1988-12-03T02:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 3, "America/Argentina/Buenos_Aires", 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryJanuaryDST() throws Exception {
        final long currentTime = parseTime("1989-01-26T21:00:00.000Z");
        final long expectedCycle = parseTime("1989-01-01T01:59:59.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 32, "America/Argentina/Buenos_Aires", 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testNetworkPolicyAppliedCycleLastMonth() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // first, pretend that wifi network comes online. no policy active,
        // which means we shouldn't push limit to interface.
        state = new NetworkState[] { buildWifi() };
        when(mConnManager.getAllNetworkState()).thenReturn(state);
        expectCurrentTime();

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(any());

        // now change cycle to be on 15th, and test in early march, to verify we
        // pick cycle day in previous month.
        when(mConnManager.getAllNetworkState()).thenReturn(state);
        expectCurrentTime();

        // pretend that 512 bytes total have happened
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 256L, 2L, 256L, 2L);
        when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, TIME_MAR_10))
                .thenReturn(stats.getTotalBytes());

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        setNetworkPolicies(new NetworkPolicy(
                sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1 * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

        // TODO: consider making strongly ordered mock
        verifyPolicyDataEnable(TYPE_WIFI, true);
        verifyRemoveInterfaceQuota(TEST_IFACE);
        verifySetInterfaceQuota(TEST_IFACE, (2 * MB_IN_BYTES) - 512);
    }

    @Test
    public void testOverWarningLimitNotification() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;
        Future<String> tagFuture = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // assign wifi policy
        state = new NetworkState[] {};
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            setNetworkPolicies(new NetworkPolicy(sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1
                    * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(any());
            verifyPolicyDataEnable(TYPE_WIFI, true);
        }

        // bring up wifi network
        incrementCurrentTime(MINUTE_IN_MILLIS);
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            verifyPolicyDataEnable(TYPE_WIFI, true);
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, 2 * MB_IN_BYTES);
        }

        // go over warning, which should kick notification
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 1536 * KB_IN_BYTES, 15L, 0L, 0L);

        {
            expectCurrentTime();
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mNetworkObserver.limitReached(null, TEST_IFACE);

            assertNotificationType(TYPE_WARNING, tagFuture.get());
            verifyPolicyDataEnable(TYPE_WIFI, true);

        }

        // go over limit, which should kick notification and dialog
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 5 * MB_IN_BYTES, 512L, 0L, 0L);

        {
            expectCurrentTime();
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mNetworkObserver.limitReached(null, TEST_IFACE);

            assertNotificationType(TYPE_LIMIT, tagFuture.get());
            verifyPolicyDataEnable(TYPE_WIFI, false);
        }

        // now snooze policy, which should remove quota
        incrementCurrentTime(MINUTE_IN_MILLIS);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            mService.snoozeLimit(sTemplateWifi);
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            assertNotificationType(TYPE_LIMIT_SNOOZED, tagFuture.get());
            // snoozed interface still has high quota so background data is
            // still restricted.
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);
            verifyPolicyDataEnable(TYPE_WIFI, true);
        }
    }

    @Test
    public void testMeteredNetworkWithoutLimit() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // bring up wifi network with metered policy
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            setNetworkPolicies(new NetworkPolicy(
                    sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED,
                    true));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            verifyPolicyDataEnable(TYPE_WIFI, true);
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);
        }
    }

    @Test
    public void testOnUidStateChanged_notifyAMS() throws Exception {
        final long procStateSeq = 222;
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, procStateSeq);
        verify(mActivityManagerInternal).notifyNetworkPolicyRulesUpdated(UID_A, procStateSeq);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final IndentingPrintWriter writer = new IndentingPrintWriter(
                new PrintWriter(outputStream), " ");
        mService.mObservedHistory.dumpUL(writer);
        writer.flush();
        assertEquals(ProcStateSeqHistory.getString(UID_A, procStateSeq),
                outputStream.toString().trim());
    }

    private void callOnUidStateChanged(int uid, int procState, long procStateSeq)
            throws Exception {
        mUidObserver.onUidStateChanged(uid, procState, procStateSeq);
        final CountDownLatch latch = new CountDownLatch(1);
        mService.mUidEventHandler.post(() -> {
            latch.countDown();
        });
        latch.await(2, TimeUnit.SECONDS);
    }

    @Test
    public void testProcStateHistory() {
        // Verify dump works correctly with no elements added.
        verifyProcStateHistoryDump(0);

        // Add items upto half of the max capacity and verify that dump works correctly.
        verifyProcStateHistoryDump(MAX_PROC_STATE_SEQ_HISTORY / 2);

        // Add items upto the max capacity and verify that dump works correctly.
        verifyProcStateHistoryDump(MAX_PROC_STATE_SEQ_HISTORY);

        // Add more items than max capacity and verify that dump works correctly.
        verifyProcStateHistoryDump(MAX_PROC_STATE_SEQ_HISTORY + MAX_PROC_STATE_SEQ_HISTORY / 2);

    }

    private void verifyProcStateHistoryDump(int count) {
        final ProcStateSeqHistory history = new ProcStateSeqHistory(MAX_PROC_STATE_SEQ_HISTORY);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final IndentingPrintWriter writer = new IndentingPrintWriter(
                new PrintWriter(outputStream), " ");

        if (count == 0) {
            // Verify with no uid info written to history.
            history.dumpUL(writer);
            writer.flush();
            assertEquals("When no uid info is there, dump should contain NONE",
                    "NONE", outputStream.toString().trim());
            return;
        }

        int uid = 111;
        long procStateSeq = 222;
        // Add count items and verify dump works correctly.
        for (int i = 0; i < count; ++i) {
            uid++;
            procStateSeq++;
            history.addProcStateSeqUL(uid, procStateSeq);
        }
        history.dumpUL(writer);
        writer.flush();
        final String[] uidsDump = outputStream.toString().split(LINE_SEPARATOR);
        // Dump will have at most MAX_PROC_STATE_SEQ_HISTORY items.
        final int expectedCount = (count < MAX_PROC_STATE_SEQ_HISTORY)
                ? count : MAX_PROC_STATE_SEQ_HISTORY;
        assertEquals(expectedCount, uidsDump.length);
        for (int i = 0; i < expectedCount; ++i) {
            assertEquals(ProcStateSeqHistory.getString(uid, procStateSeq), uidsDump[i]);
            uid--;
            procStateSeq--;
        }
    }

    private void assertCycleDayAsExpected(PersistableBundle config, int carrierCycleDay,
            boolean expectValid) {
        config.putInt(KEY_MONTHLY_DATA_CYCLE_DAY_INT, carrierCycleDay);
        int actualCycleDay = mService.getCycleDayFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        if (expectValid) {
            assertEquals(carrierCycleDay, actualCycleDay);
        } else {
            // INVALID_CARRIER_CONFIG_VALUE is returned for invalid values
            assertEquals(INVALID_CARRIER_CONFIG_VALUE, actualCycleDay);
        }
    }

    @Test
    public void testGetCycleDayFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        final Calendar cal = Calendar.getInstance();
        int actualCycleDay;

        config.putInt(KEY_MONTHLY_DATA_CYCLE_DAY_INT, DATA_CYCLE_USE_PLATFORM_DEFAULT);
        actualCycleDay = mService.getCycleDayFromCarrierConfig(config, DEFAULT_CYCLE_DAY);
        assertEquals(DEFAULT_CYCLE_DAY, actualCycleDay);

        // null config returns a default value
        actualCycleDay = mService.getCycleDayFromCarrierConfig(null, DEFAULT_CYCLE_DAY);
        assertEquals(DEFAULT_CYCLE_DAY, actualCycleDay);

        // Sane, non-default values
        assertCycleDayAsExpected(config, 1, true);
        assertCycleDayAsExpected(config, cal.getMaximum(Calendar.DAY_OF_MONTH), true);
        assertCycleDayAsExpected(config, cal.getMinimum(Calendar.DAY_OF_MONTH), true);

        // Invalid values
        assertCycleDayAsExpected(config, 0, false);
        assertCycleDayAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, false);
        assertCycleDayAsExpected(config, cal.getMaximum(Calendar.DAY_OF_MONTH) + 1, false);
        assertCycleDayAsExpected(config, cal.getMinimum(Calendar.DAY_OF_MONTH) - 5, false);
    }

    private void assertWarningBytesAsExpected(PersistableBundle config, long carrierWarningBytes,
            long expected) {
        config.putLong(KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, carrierWarningBytes);
        long actualWarning = mService.getWarningBytesFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        assertEquals(expected, actualWarning);
    }

    @Test
    public void testGetWarningBytesFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        long actualWarningBytes;

        assertWarningBytesAsExpected(config, DATA_CYCLE_USE_PLATFORM_DEFAULT,
                mDefaultWarningBytes);
        assertWarningBytesAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, WARNING_DISABLED);
        assertWarningBytesAsExpected(config, 0, 0);
        // not a valid value
        assertWarningBytesAsExpected(config, -1000, INVALID_CARRIER_CONFIG_VALUE);

        // null config returns a default value
        actualWarningBytes = mService.getWarningBytesFromCarrierConfig(null, mDefaultWarningBytes);
        assertEquals(mDefaultWarningBytes, actualWarningBytes);
    }

    private void assertLimitBytesAsExpected(PersistableBundle config,  long carrierWarningBytes,
            long expected) {
        config.putLong(KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, carrierWarningBytes);
        long actualWarning = mService.getLimitBytesFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        assertEquals(expected, actualWarning);
    }

    @Test
    public void testGetLimitBytesFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        long actualLimitBytes;

        assertLimitBytesAsExpected(config, DATA_CYCLE_USE_PLATFORM_DEFAULT,
                mDefaultLimitBytes);
        assertLimitBytesAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, LIMIT_DISABLED);
        assertLimitBytesAsExpected(config, 0, 0);
        // not a valid value
        assertLimitBytesAsExpected(config, -1000, INVALID_CARRIER_CONFIG_VALUE);

        // null config returns a default value
        actualLimitBytes = mService.getWarningBytesFromCarrierConfig(null, mDefaultLimitBytes);
        assertEquals(mDefaultLimitBytes, actualLimitBytes);
    }

    private PersistableBundle setupUpdateMobilePolicyCycleTests() throws RemoteException {
        when(mConnManager.getAllNetworkState()).thenReturn(new NetworkState[0]);
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(new int[]{FAKE_SUB_ID});
        when(mTelephonyManager.getSubscriberId(FAKE_SUB_ID)).thenReturn(FAKE_SUBSCRIBER_ID);
        PersistableBundle bundle = CarrierConfigManager.getDefaultConfig();
        when(mCarrierConfigManager.getConfigForSubId(FAKE_SUB_ID)).thenReturn(bundle);
        setNetworkPolicies(buildDefaultFakeMobilePolicy());
        return bundle;
    }

    @Test
    public void testUpdateMobilePolicyCycleWithNullConfig() throws RemoteException {
        when(mConnManager.getAllNetworkState()).thenReturn(new NetworkState[0]);
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(new int[]{FAKE_SUB_ID});
        when(mTelephonyManager.getSubscriberId(FAKE_SUB_ID)).thenReturn(FAKE_SUBSCRIBER_ID);
        when(mCarrierConfigManager.getConfigForSubId(FAKE_SUB_ID)).thenReturn(null);
        setNetworkPolicies(buildDefaultFakeMobilePolicy());
        // smoke test to make sure no errors are raised
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );
        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateMobilePolicyCycleWithInvalidConfig() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();
        // Test with an invalid CarrierConfig, there should be no changes or crashes.
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, -100);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, -100);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, -100);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateMobilePolicyCycleWithDefaultConfig() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();
        // Test that we respect the platform values when told to
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateMobilePolicyCycleWithUserOverrides() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();

        // inferred = false implies that a user manually modified this policy.
        NetworkPolicy policy = buildDefaultFakeMobilePolicy();
        policy.inferred = false;
        setNetworkPolicies(policy);

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, 9999);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        // The policy still shouldn't change, because we don't want to overwrite user settings.
        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                false);
    }

    @Test
    public void testUpdateMobilePolicyCycleUpdatesDataCycle() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, 9999);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, 9999);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, 9999, 9999, true);
    }

    @Test
    public void testUpdateMobilePolicyCycleDisableThresholds() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, WARNING_DISABLED, LIMIT_DISABLED, true);
    }

    @Test
    public void testUpdateMobilePolicyCycleRevertsToDefault() throws RemoteException {
        PersistableBundle bundle = setupUpdateMobilePolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );
        assertNetworkPolicyEquals(31, WARNING_DISABLED, LIMIT_DISABLED, true);

        // If the user switches carriers to one that doesn't use a CarrierConfig, we should revert
        // to the default data limit and warning. The cycle date doesn't need to revert as it's
        // arbitrary anyways.
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    private NetworkPolicy buildDefaultFakeMobilePolicy() {
        NetworkPolicy p = mService.buildDefaultMobilePolicy(FAKE_SUB_ID, FAKE_SUBSCRIBER_ID);
        // set a deterministic cycle date
        p.cycleDay = DEFAULT_CYCLE_DAY;
        return p;
    }

    private static NetworkPolicy buildFakeMobilePolicy(int cycleDay, long warningBytes,
            long limitBytes, boolean inferred){
        final NetworkTemplate template = buildTemplateMobileAll(FAKE_SUBSCRIBER_ID);
        return new NetworkPolicy(template, cycleDay, new Time().timezone, warningBytes,
                limitBytes, SNOOZE_NEVER, SNOOZE_NEVER, true, inferred);
    }

    private void assertNetworkPolicyEquals(int expectedCycleDay, long expectedWarningBytes,
            long expectedLimitBytes, boolean expectedInferred) {
        NetworkPolicy[] policies = mService.getNetworkPolicies(
                mServiceContext.getOpPackageName());
        assertEquals("Unexpected number of network policies", 1, policies.length);
        NetworkPolicy actualPolicy = policies[0];
        NetworkPolicy expectedPolicy = buildFakeMobilePolicy(expectedCycleDay, expectedWarningBytes,
                expectedLimitBytes, expectedInferred);
        assertEquals(expectedPolicy, actualPolicy);
    }

    private static long parseTime(String time) {
        final Time result = new Time();
        result.parse3339(time);
        return result.toMillis(true);
    }

    private void setNetworkPolicies(NetworkPolicy... policies) {
        mService.setNetworkPolicies(policies);
    }

    private static NetworkState buildWifi() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        final NetworkCapabilities networkCapabilities = new NetworkCapabilities();
        return new NetworkState(info, prop, networkCapabilities, null, null, TEST_SSID);
    }

    private void expectCurrentTime() throws Exception {
        when(mTime.forceRefresh()).thenReturn(false);
        when(mTime.hasCache()).thenReturn(true);
        when(mTime.currentTimeMillis()).thenReturn(currentTimeMillis());
        when(mTime.getCacheAge()).thenReturn(0L);
        when(mTime.getCacheCertainty()).thenReturn(0L);
    }

    private Future<String> expectEnqueueNotification() throws Exception {
        final FutureAnswer<String> futureAnswer = new FutureAnswer<String>(2);
        doAnswer(futureAnswer).when(mNotifManager).enqueueNotificationWithTag(
                anyString(), anyString(), anyString() /* capture here (index 2)*/,
                anyInt(), isA(Notification.class), anyInt());
        return futureAnswer;
    }

    private void expectHasInternetPermission(int uid, boolean hasIt) throws Exception {
        when(mIpm.checkUidPermission(Manifest.permission.INTERNET, uid)).thenReturn(
                hasIt ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
    }

    private void verifySetInterfaceQuota(String iface, long quotaBytes) throws Exception {
        verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(iface, quotaBytes);
    }

    private void verifyRemoveInterfaceQuota(String iface) throws Exception {
        verify(mNetworkManager, atLeastOnce()).removeInterfaceQuota(iface);
    }

    private Future<Void> verifyPolicyDataEnable(int type, boolean enabled) throws Exception {
        // TODO: bring back this test
        return null;
    }

    private void verifyAdvisePersistThreshold() throws Exception {
        verify(mStatsService).advisePersistThreshold(anyLong());
    }

    private static class TestAbstractFuture<T> extends AbstractFuture<T> {
        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FutureAnswer<T> extends TestAbstractFuture<T> implements Answer<Void> {
        private final int index;

        FutureAnswer(int index) {
            this.index = index;
        }
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            @SuppressWarnings("unchecked")
            T captured = (T) invocation.getArguments()[index];
            set(captured);
            return null;
        }
    }

    private static void assertTimeEquals(long expected, long actual) {
        if (expected != actual) {
            fail("expected " + formatTime(expected) + " but was actually " + formatTime(actual));
        }
    }

    private static String formatTime(long millis) {
        final Time time = new Time(Time.TIMEZONE_UTC);
        time.set(millis);
        return time.format3339(false);
    }

    private static void assertEqualsFuzzy(long expected, long actual, long fuzzy) {
        final long low = expected - fuzzy;
        final long high = expected + fuzzy;
        if (actual < low || actual > high) {
            fail("value " + actual + " is outside [" + low + "," + high + "]");
        }
    }

    private static void assertUnique(LinkedHashSet<Long> seen, Long value) {
        if (!seen.add(value)) {
            fail("found duplicate time " + value + " in series " + seen.toString());
        }
    }

    private static void assertNotificationType(int expected, String actualTag) {
        assertEquals("notification type mismatch for '" + actualTag +"'",
                Integer.toString(expected), actualTag.substring(actualTag.lastIndexOf(':') + 1));
    }

    private void assertUidPolicy(int uid, int expected) {
        final int actual = mService.getUidPolicy(uid);
        if (expected != actual) {
            fail("Wrong policy for UID " + uid + ": expected " + uidPoliciesToString(expected)
                    + ", actual " + uidPoliciesToString(actual));
        }
    }

    private void assertWhitelistUids(int... uids) {
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_ALLOW_METERED_BACKGROUND), uids);
    }

    private void assertRestrictBackgroundOn() throws Exception {
        assertTrue("restrictBackground should be set", mService.getRestrictBackground());
    }

    private void assertRestrictBackgroundOff() throws Exception {
        assertFalse("restrictBackground should not be set", mService.getRestrictBackground());
    }

    private FutureIntent newRestrictBackgroundChangedFuture() {
        return mServiceContext
                .nextBroadcastIntent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
    }

    private void assertRestrictBackgroundChangedReceived(Future<Intent> future,
            String expectedPackage) throws Exception {
        final String action = ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED;
        final Intent intent = future.get(5, TimeUnit.SECONDS);
        assertNotNull("Didn't get a " + action + "intent in 5 seconds");
        assertEquals("Wrong package on " + action + " intent", expectedPackage, intent.getPackage());
    }

    // TODO: replace by Truth, Hamcrest, or a similar tool.
    private void assertContainsInAnyOrder(int[] actual, int...expected) {
        final StringBuilder errors = new StringBuilder();
        if (actual.length != expected.length) {
            errors.append("\tsize does not match\n");
        }
        final List<Integer> actualList =
                Arrays.stream(actual).boxed().collect(Collectors.<Integer>toList());
        final List<Integer> expectedList =
                Arrays.stream(expected).boxed().collect(Collectors.<Integer>toList());
        if (!actualList.containsAll(expectedList)) {
            errors.append("\tmissing elements on actual list\n");
        }
        if (!expectedList.containsAll(actualList)) {
            errors.append("\tmissing elements on expected list\n");
        }
        if (errors.length() > 0) {
            fail("assertContainsInAnyOrder(expected=" + Arrays.toString(expected)
                    + ", actual=" + Arrays.toString(actual) +") failed: \n" + errors);
        }
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private void setCurrentTimeMillis(long currentTimeMillis) {
        mStartTime = currentTimeMillis;
        mElapsedRealtime = 0L;
    }

    private long currentTimeMillis() {
        return mStartTime + mElapsedRealtime;
    }

    private void incrementCurrentTime(long duration) {
        mElapsedRealtime += duration;
    }

    private FutureIntent mRestrictBackgroundChanged;

    private void setRestrictBackground(boolean flag) throws Exception {
        // Must set expectation, otherwise NMPS will reset value to previous one.
        doReturn(true).when(mNetworkManager).setDataSaverModeEnabled(flag);
        mService.setRestrictBackground(flag);
        // Sanity check.
        assertEquals("restrictBackground not set", flag, mService.getRestrictBackground());
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> T addLocalServiceMock(Class<T> clazz) {
        final T mock = mock(clazz);
        LocalServices.addService(clazz, mock);
        return mock;
    }

    /**
     * Custom Mockito answer used to verify async {@link INetworkPolicyListener} calls.
     *
     * <p>Typical usage:
     * <pre><code>
     *    mPolicyListener.expect().someCallback(any());
     *    // do something on objects under test
     *    mPolicyListener.waitAndVerify().someCallback(eq(expectedValue));
     * </code></pre>
     */
    final class NetworkPolicyListenerAnswer implements Answer<Void> {
        private CountDownLatch latch;
        private final INetworkPolicyListener listener;

        NetworkPolicyListenerAnswer(NetworkPolicyManagerService service) {
            this.listener = mock(INetworkPolicyListener.class);
            // RemoteCallbackList needs a binder to use as key
            when(listener.asBinder()).thenReturn(new Binder());
            service.registerListener(listener);
        }

        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            Log.d(TAG,"counting down on answer: " + invocation);
            latch.countDown();
            return null;
        }

        INetworkPolicyListener expect() {
            assertNull("expect() called before waitAndVerify()", latch);
            latch = new CountDownLatch(1);
            return doAnswer(this).when(listener);
        }

        INetworkPolicyListener waitAndVerify() {
            assertNotNull("waitAndVerify() called before expect()", latch);
            try {
                assertTrue("callback not called in 5 seconds", latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                fail("Thread interrupted before callback called");
            } finally {
                latch = null;
            }
            return verify(listener, atLeastOnce());
        }

        INetworkPolicyListener verifyNotCalled() {
            return verify(listener, never());
        }

    }

    private void setNetpolicyXml(Context context) throws Exception {
        mPolicyDir = context.getFilesDir();
        if (mPolicyDir.exists()) {
            IoUtils.deleteContents(mPolicyDir);
        }
        if (!TextUtils.isEmpty(mNetpolicyXml)) {
            final String assetPath = NETPOLICY_DIR + "/" + mNetpolicyXml;
            final File netConfigFile = new File(mPolicyDir, "netpolicy.xml");
            Log.d(TAG, "Creating " + netConfigFile + " from asset " + assetPath);
            try (final InputStream in = context.getResources().getAssets().open(assetPath);
                    final OutputStream out = new FileOutputStream(netConfigFile)) {
                Streams.copy(in, out);
            }
        }
    }

    /**
     * Annotation used to define the relative path of the {@code netpolicy.xml} file.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface NetPolicyXml {

        public String value() default "";

    }

    /**
     * Rule used to set {@code mNetPolicyXml} according to the {@link NetPolicyXml} annotation.
     */
    public static class NetPolicyMethodRule implements MethodRule {

        @Override
        public Statement apply(Statement base, FrameworkMethod method, Object target) {
            for (Annotation annotation : method.getAnnotations()) {
                if ((annotation instanceof NetPolicyXml)) {
                    final String path = ((NetPolicyXml) annotation).value();
                    if (!path.isEmpty()) {
                        ((NetworkPolicyManagerServiceTest) target).mNetpolicyXml = path;
                        break;
                    }
                }
            }
            return base;
        }
    }
}
