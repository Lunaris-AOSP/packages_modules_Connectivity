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
 * limitations under the License.
 */

package com.android.networkstack.tethering;

import static android.net.TetheringConstants.EXTRA_ADD_TETHER_TYPE;
import static android.net.TetheringConstants.EXTRA_PROVISION_CALLBACK;
import static android.net.TetheringConstants.EXTRA_RUN_PROVISION;
import static android.net.TetheringConstants.EXTRA_TETHER_PROVISIONING_RESPONSE;
import static android.net.TetheringConstants.EXTRA_TETHER_SILENT_PROVISIONING_ACTION;
import static android.net.TetheringConstants.EXTRA_TETHER_SUBID;
import static android.net.TetheringConstants.EXTRA_TETHER_UI_PROVISIONING_APP_NAME;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_INVALID;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_PROVISIONING_FAILED;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.networkstack.apishim.ConstantsShim.KEY_CARRIER_SUPPORTS_TETHERING_BOOL;
import static com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import static com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.ArrayTrackRecord;
import com.android.net.module.util.SharedLog;
import com.android.testutils.DevSdkIgnoreRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class EntitlementManagerTest {

    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final String PROVISIONING_NO_UI_APP_NAME = "no_ui_app";
    private static final String PROVISIONING_APP_RESPONSE = "app_response";
    private static final String TEST_PACKAGE_NAME = "com.android.tethering.test";
    private static final String FAILED_TETHERING_REASON = "Tethering provisioning failed.";
    private static final int RECHECK_TIMER_HOURS = 24;

    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private SharedLog mLog;
    @Mock private PackageManager mPm;
    @Mock private EntitlementManager
            .OnTetherProvisioningFailedListener mTetherProvisioningFailedListener;
    @Mock private AlarmManager mAlarmManager;
    @Mock private UserManager mUserManager;
    @Mock private PendingIntent mAlarmIntent;

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();
    private final TestLooper mLooper = new TestLooper();
    private MockContext mMockContext;
    private Runnable mPermissionChangeCallback;

    private EntitlementManager mEnMgr;
    private TetheringConfiguration mConfig;
    private MockitoSession mMockingSession;
    private TestDependencies mDeps;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.ALARM_SERVICE.equals(name)) return mAlarmManager;
            if (Context.USER_SERVICE.equals(name)) return mUserManager;

            return super.getSystemService(name);
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            if (UserManager.class.equals(serviceClass)) return Context.USER_SERVICE;
            return super.getSystemServiceName(serviceClass);
        }

        @NonNull
        @Override
        public Context createContextAsUser(UserHandle user, int flags) {
            if (mCreateContextAsUserException != null) {
                throw mCreateContextAsUserException;
            }
            return mMockContext; // Return self for easier test injection.
        }

        private RuntimeException mCreateContextAsUserException = null;

        private void setCreateContextAsUserException(RuntimeException e) {
            mCreateContextAsUserException = e;
        }
    }

    class TestDependencies extends EntitlementManager.Dependencies {
        public int fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKNOWN;
        public int uiProvisionCount = 0;
        public int silentProvisionCount = 0;
        TestDependencies(@NonNull Context context,
                @NonNull SharedLog log) {
            super(context, log);
        }

        public void reset() {
            fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKNOWN;
            uiProvisionCount = 0;
            silentProvisionCount = 0;
        }

        @Override
        protected Intent runUiTetherProvisioning(int type,
                final TetheringConfiguration config, final ResultReceiver receiver) {
            Intent intent = super.runUiTetherProvisioning(type, config, receiver);
            if (intent != null) {
                assertUiTetherProvisioningIntent(type, config, receiver, intent);
                uiProvisionCount++;
                // If the intent is null, the result is sent by the underlying method.
                receiver.send(fakeEntitlementResult, null);
            }
            return intent;
        }

        private void assertUiTetherProvisioningIntent(int type, final TetheringConfiguration config,
                final ResultReceiver receiver, final Intent intent) {
            assertEquals(Settings.ACTION_TETHER_PROVISIONING_UI, intent.getAction());
            assertEquals(type, intent.getIntExtra(EXTRA_ADD_TETHER_TYPE, TETHERING_INVALID));
            final String[] appName = intent.getStringArrayExtra(
                    EXTRA_TETHER_UI_PROVISIONING_APP_NAME);
            assertEquals(PROVISIONING_APP_NAME.length, appName.length);
            for (int i = 0; i < PROVISIONING_APP_NAME.length; i++) {
                assertEquals(PROVISIONING_APP_NAME[i], appName[i]);
            }
            assertEquals(receiver, intent.getParcelableExtra(EXTRA_PROVISION_CALLBACK));
            assertEquals(config.activeDataSubId,
                    intent.getIntExtra(EXTRA_TETHER_SUBID, INVALID_SUBSCRIPTION_ID));
        }

        @Override
        protected Intent runSilentTetherProvisioning(int type,
                final TetheringConfiguration config, final ResultReceiver receiver) {
            Intent intent = super.runSilentTetherProvisioning(type, config, receiver);
            assertSilentTetherProvisioning(type, config, intent);
            silentProvisionCount++;
            mEnMgr.addDownstreamMapping(type, fakeEntitlementResult);
            return intent;
        }

        private void assertSilentTetherProvisioning(int type, final TetheringConfiguration config,
                final Intent intent) {
            assertEquals(type, intent.getIntExtra(EXTRA_ADD_TETHER_TYPE, TETHERING_INVALID));
            assertEquals(true, intent.getBooleanExtra(EXTRA_RUN_PROVISION, false));
            assertEquals(PROVISIONING_NO_UI_APP_NAME,
                    intent.getStringExtra(EXTRA_TETHER_SILENT_PROVISIONING_ACTION));
            assertEquals(PROVISIONING_APP_RESPONSE,
                    intent.getStringExtra(EXTRA_TETHER_PROVISIONING_RESPONSE));
            assertTrue(intent.hasExtra(EXTRA_PROVISION_CALLBACK));
            assertEquals(config.activeDataSubId,
                    intent.getIntExtra(EXTRA_TETHER_SUBID, INVALID_SUBSCRIPTION_ID));
        }

        @Override
        PendingIntent createRecheckAlarmIntent(final String pkgName) {
            assertEquals(TEST_PACKAGE_NAME, pkgName);
            return mAlarmIntent;
        }

        @Override
        int getCurrentUser() {
            // The result is not used, just override to bypass the need of accessing
            // the static method.
            return 0;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(SystemProperties.class)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        // Don't disable tethering provisioning unless requested.
        doReturn(false).when(
                () -> SystemProperties.getBoolean(
                eq(EntitlementManager.DISABLE_PROVISIONING_SYSPROP_KEY), anyBoolean()));
        doReturn(null).when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY), anyString()));
        doReturn(mPm).when(mContext).getPackageManager();
        doReturn(TEST_PACKAGE_NAME).when(mContext).getPackageName();
        doReturn(new PackageInfo()).when(mPm).getPackageInfo(anyString(), anyInt());
        doReturn(new ModuleInfo()).when(mPm).getModuleInfo(anyString(), anyInt());

        when(mResources.getStringArray(R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_usb_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_wifi_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                false);
        when(mResources.getString(R.string.config_wifi_tether_enable)).thenReturn("");
        when(mLog.forSubComponent(anyString())).thenReturn(mLog);
        doReturn(true).when(mUserManager).isAdminUser();

        mMockContext = new MockContext(mContext);
        mDeps = new TestDependencies(mMockContext, mLog);
        mPermissionChangeCallback = spy(() -> { });
        mEnMgr = new EntitlementManager(mMockContext, new Handler(mLooper.getLooper()), mLog,
                mPermissionChangeCallback, mDeps);
        mEnMgr.setOnTetherProvisioningFailedListener(mTetherProvisioningFailedListener);
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        mEnMgr.setTetheringConfigurationFetcher(() -> {
            return mConfig;
        });
    }

    @After
    public void tearDown() throws Exception {
        mMockingSession.finishMocking();
    }

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        when(mResources.getString(R.string.config_mobile_hotspot_provision_app_no_ui))
                .thenReturn(PROVISIONING_NO_UI_APP_NAME);
        when(mResources.getString(R.string.config_mobile_hotspot_provision_response)).thenReturn(
                PROVISIONING_APP_RESPONSE);
        when(mResources.getInteger(R.integer.config_mobile_hotspot_provision_check_period))
                .thenReturn(RECHECK_TIMER_HOURS);
        // Act like the CarrierConfigManager is present and ready unless told otherwise.
        mockService(Context.CARRIER_CONFIG_SERVICE,
                CarrierConfigManager.class, mCarrierConfigManager);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mCarrierConfig);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL, true);
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
    }

    private void setupCarrierConfig(boolean carrierSupported) {
        mCarrierConfig.putBoolean(KEY_CARRIER_SUPPORTS_TETHERING_BOOL, carrierSupported);
    }

    private <T> void mockService(String serviceName, Class<T> serviceClass, T service) {
        when(mMockContext.getSystemServiceName(serviceClass)).thenReturn(serviceName);
        when(mMockContext.getSystemService(serviceName)).thenReturn(service);
    }

    @Test
    public void canRequireProvisioning() {
        setupForRequiredProvisioning();
        assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void provisioningNotRequiredWhenAppNotFound() {
        setupForRequiredProvisioning();
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(null);
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(mEnMgr.isTetherProvisioningRequired(mConfig));
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(new String[] {"malformedApp"});
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void testRequestLastEntitlementCacheValue() throws Exception {
        // 1. Entitlement check is not required.
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_NO_ERROR, true);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();

        setupForRequiredProvisioning();
        // 2. No cache value and don't need to run entitlement check.
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_ENTITLEMENT_UNKNOWN, false);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
        // 3. No cache value and ui entitlement check is needed.
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_PROVISIONING_FAILED, true);
        assertEquals(1, mDeps.uiProvisionCount);
        mDeps.reset();
        // 4. Cache value is TETHER_ERROR_PROVISIONING_FAILED and don't need to run entitlement
        // check.
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_PROVISIONING_FAILED, false);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
        // 5. Cache value is TETHER_ERROR_PROVISIONING_FAILED and ui entitlement check is needed.
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_NO_ERROR, true);
        assertEquals(1, mDeps.uiProvisionCount);
        mDeps.reset();
        // 6. Cache value is TETHER_ERROR_NO_ERROR.
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_NO_ERROR, true);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
        // 7. Test get value for other downstream type.
        assertLatestEntitlementResult(TETHERING_USB, TETHER_ERROR_ENTITLEMENT_UNKNOWN, false);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
        // 8. Test get value for invalid downstream type.
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI_P2P, TETHER_ERROR_ENTITLEMENT_UNKNOWN, true);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
    }

    private void assertPermissionChangeCallback(InOrder inOrder) {
        inOrder.verify(mPermissionChangeCallback, times(1)).run();
    }

    private void assertNoPermissionChange(InOrder inOrder) {
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void verifyPermissionResult() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);

        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: false -> true
        assertPermissionChangeCallback(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAllNotApproved() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAnyApproved() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionWhenProvisioningNotStarted() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        assertNoPermissionChange(inOrder);
        setupForRequiredProvisioning();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        assertNoPermissionChange(inOrder);
    }

    @Test
    public void testRunTetherProvisioning() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        // 1. start ui provisioning, upstream is mobile
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertEquals(1, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mDeps.reset();

        // 2. start no-ui provisioning
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, false);
        mLooper.dispatchAll();
        assertEquals(0, mDeps.uiProvisionCount);
        assertEquals(1, mDeps.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mDeps.reset();

        // 3. tear down mobile, then start ui provisioning
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        assertEquals(0, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mDeps.reset();

        // 4. switch upstream back to mobile
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(1, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mDeps.reset();

        // 5. tear down mobile, then switch SIM
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.reevaluateSimCardProvisioning(mConfig);
        assertEquals(0, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mDeps.reset();

        // 6. switch upstream back to mobile again
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(0, mDeps.uiProvisionCount);
        assertEquals(3, mDeps.silentProvisionCount);
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        mDeps.reset();

        // 7. start ui provisioning, upstream is mobile, downstream is ethernet
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_ETHERNET, true);
        mLooper.dispatchAll();
        assertEquals(1, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        // Permitted: false -> true
        assertPermissionChangeCallback(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mDeps.reset();

        // 8. downstream is invalid
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI_P2P, true);
        mLooper.dispatchAll();
        assertEquals(0, mDeps.uiProvisionCount);
        assertEquals(0, mDeps.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mDeps.reset();
    }

    @Test
    public void testCallStopTetheringWhenUiProvisioningFail() {
        setupForRequiredProvisioning();
        verify(mTetherProvisioningFailedListener, times(0))
                .onTetherProvisioningFailed(TETHERING_WIFI, FAILED_TETHERING_REASON);
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertEquals(1, mDeps.uiProvisionCount);
        verify(mTetherProvisioningFailedListener, times(1))
                .onTetherProvisioningFailed(TETHERING_WIFI, FAILED_TETHERING_REASON);
    }

    @IgnoreUpTo(SC_V2)
    @Test
    public void testUiProvisioningMultiUser_aboveT_createContextAsUserThrows() {
        mMockContext.setCreateContextAsUserException(new IllegalStateException());
        doTestUiProvisioningMultiUser(true, 1);
        doTestUiProvisioningMultiUser(false, 1);
    }

    @IgnoreUpTo(SC_V2)
    @Test
    public void testUiProvisioningMultiUser_aboveT() {
        doTestUiProvisioningMultiUser(true, 1);
        doTestUiProvisioningMultiUser(false, 0);
    }

    @IgnoreAfter(SC_V2)
    @Test
    public void testUiProvisioningMultiUser_belowT() {
        doTestUiProvisioningMultiUser(true, 1);
        doTestUiProvisioningMultiUser(false, 1);
    }

    private static class TestableResultReceiver extends ResultReceiver {
        private static final long DEFAULT_TIMEOUT_MS = 200L;
        private final ArrayTrackRecord<Integer>.ReadHead mHistory =
                new ArrayTrackRecord<Integer>().newReadHead();

        TestableResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mHistory.add(resultCode);
        }

        void expectResult(int resultCode) {
            final int event = mHistory.poll(DEFAULT_TIMEOUT_MS, it -> true);
            assertEquals(resultCode, event);
        }
    }

    void assertLatestEntitlementResult(int downstreamType, int expectedCode,
            boolean showEntitlementUi) {
        final TestableResultReceiver receiver = new TestableResultReceiver(null);
        mEnMgr.requestLatestTetheringEntitlementResult(downstreamType, receiver, showEntitlementUi);
        mLooper.dispatchAll();
        receiver.expectResult(expectedCode);
    }

    private void doTestUiProvisioningMultiUser(boolean isAdminUser, int expectedUiProvisionCount) {
        setupForRequiredProvisioning();
        doReturn(isAdminUser).when(mUserManager).isAdminUser();

        mDeps.reset();
        clearInvocations(mTetherProvisioningFailedListener);
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertEquals(expectedUiProvisionCount, mDeps.uiProvisionCount);
        if (expectedUiProvisionCount == 0) { // Failed to launch entitlement UI.
            assertLatestEntitlementResult(TETHERING_USB, TETHER_ERROR_PROVISIONING_FAILED, false);
            verify(mTetherProvisioningFailedListener).onTetherProvisioningFailed(TETHERING_USB,
                    FAILED_TETHERING_REASON);
        } else {
            assertLatestEntitlementResult(TETHERING_USB, TETHER_ERROR_NO_ERROR, false);
            verify(mTetherProvisioningFailedListener, never()).onTetherProvisioningFailed(anyInt(),
                    anyString());
        }
    }

    @Test
    public void testSetExemptedDownstreamType() {
        setupForRequiredProvisioning();
        // Cellular upstream is not permitted when no entitlement result.
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        // If there is exempted downstream and no other non-exempted downstreams, cellular is
        // permitted.
        mEnMgr.setExemptedDownstreamType(TETHERING_WIFI);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        // If second downstream run entitlement check fail, cellular upstream is not permitted.
        mDeps.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        // When second downstream is down, exempted downstream can use cellular upstream.
        assertEquals(1, mDeps.uiProvisionCount);
        verify(mTetherProvisioningFailedListener).onTetherProvisioningFailed(TETHERING_USB,
                FAILED_TETHERING_REASON);
        mEnMgr.stopProvisioningIfNeeded(TETHERING_USB);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    private void sendProvisioningRecheckAlarm() {
        final Intent intent = new Intent(EntitlementManager.ACTION_PROVISIONING_ALARM);
        mMockContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        mLooper.dispatchAll();
    }

    @Test
    public void testScheduleProvisioningReCheck() throws Exception {
        setupForRequiredProvisioning();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP), anyLong(),
                eq(mAlarmIntent));
        reset(mAlarmManager);

        sendProvisioningRecheckAlarm();
        verify(mAlarmManager).cancel(eq(mAlarmIntent));
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP), anyLong(),
                eq(mAlarmIntent));
    }

    @Test
    @IgnoreUpTo(SC_V2)
    public void requestLatestTetheringEntitlementResult_carrierDoesNotSupport_noProvisionCount()
            throws Exception {
        setupCarrierConfig(false);
        setupForRequiredProvisioning();
        mDeps.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        assertLatestEntitlementResult(TETHERING_WIFI, TETHER_ERROR_PROVISIONING_FAILED, false);
        assertEquals(0, mDeps.uiProvisionCount);
        mDeps.reset();
    }

    @Test
    @IgnoreUpTo(SC_V2)
    public void reevaluateSimCardProvisioning_carrierUnsupportAndSimswitch() {
        setupForRequiredProvisioning();

        // Start a tethering with cellular data without provisioning.
        mEnMgr.notifyUpstream(true);
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, false);
        mLooper.dispatchAll();

        // Tear down mobile, then switch SIM.
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        setupCarrierConfig(false);
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        mEnMgr.reevaluateSimCardProvisioning(mConfig);

        // Turn on upstream.
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();

        verify(mTetherProvisioningFailedListener)
                .onTetherProvisioningFailed(TETHERING_WIFI, "Carrier does not support.");
    }

    @Test
    @IgnoreUpTo(SC_V2)
    public void startProvisioningIfNeeded_carrierUnsupport()
            throws Exception {
        setupCarrierConfig(false);
        setupForRequiredProvisioning();
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        verify(mTetherProvisioningFailedListener, never())
                .onTetherProvisioningFailed(TETHERING_WIFI, "Carrier does not support.");

        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        verify(mTetherProvisioningFailedListener)
                .onTetherProvisioningFailed(TETHERING_WIFI, "Carrier does not support.");
        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        reset(mTetherProvisioningFailedListener);

        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        verify(mTetherProvisioningFailedListener)
                .onTetherProvisioningFailed(TETHERING_WIFI, "Carrier does not support.");
    }

    @Test
    public void isTetherProvisioningRequired_carrierUnSupport() {
        setupForRequiredProvisioning();
        setupCarrierConfig(false);
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(new String[0]);
        mConfig = new FakeTetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);

        if (SdkLevel.isAtLeastT()) {
            assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
        } else {
            assertFalse(mEnMgr.isTetherProvisioningRequired(mConfig));
        }
    }
}
