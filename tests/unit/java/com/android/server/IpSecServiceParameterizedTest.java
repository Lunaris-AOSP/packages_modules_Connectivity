/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.INetd.IF_STATE_DOWN;
import static android.net.INetd.IF_STATE_UP;
import static android.net.IpSecManager.DIRECTION_FWD;
import static android.net.IpSecManager.DIRECTION_IN;
import static android.net.IpSecManager.DIRECTION_OUT;
import static android.net.IpSecManager.FEATURE_IPSEC_TUNNEL_MIGRATION;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecMigrateInfoParcel;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.test.mock.MockContext;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.net.module.util.netlink.xfrm.XfrmNetlinkNewSaMessage;
import com.android.server.IpSecService.TunnelInterfaceRecord;
import com.android.testutils.DevSdkIgnoreRule;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(Parameterized.class)
public class IpSecServiceParameterizedTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule(
            Build.VERSION_CODES.S_V2 /* ignoreClassUpTo */);

    private static final int TEST_SPI = 0xD1201D;

    private final String mSourceAddr;
    private final String mDestinationAddr;
    private final LinkAddress mLocalInnerAddress;
    private final int mFamily;

    private static final int[] ADDRESS_FAMILIES =
            new int[] {AF_INET, AF_INET6};

    @Parameterized.Parameters
    public static Collection ipSecConfigs() {
        return Arrays.asList(
                new Object[][] {
                {"1.2.3.4", "8.8.4.4", "10.0.1.1/24", AF_INET},
                {"2601::2", "2601::10", "2001:db8::1/64", AF_INET6}
        });
    }

    private static final byte[] AEAD_KEY = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
        0x73, 0x61, 0x6C, 0x74
    };
    private static final byte[] CRYPT_KEY = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
    };
    private static final byte[] AUTH_KEY = {
        0x7A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F,
        0x7A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F
    };

    private static final String NEW_SRC_ADDRESS = "2001:db8:2::1";
    private static final String NEW_DST_ADDRESS = "2001:db8:2::2";

    AppOpsManager mMockAppOps = mock(AppOpsManager.class);
    ConnectivityManager mMockConnectivityMgr = mock(ConnectivityManager.class);

    TestContext mTestContext = new TestContext();

    private class TestContext extends MockContext {
        private Set<String> mAllowedPermissions = new ArraySet<>(Arrays.asList(
                android.Manifest.permission.MANAGE_IPSEC_TUNNELS,
                android.Manifest.permission.NETWORK_STACK,
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                PERMISSION_MAINLINE_NETWORK_STACK));

        private void setAllowedPermissions(String... permissions) {
            mAllowedPermissions = new ArraySet<>(permissions);
        }

        @Override
        public Object getSystemService(String name) {
            switch(name) {
                case Context.APP_OPS_SERVICE:
                    return mMockAppOps;
                case Context.CONNECTIVITY_SERVICE:
                    return mMockConnectivityMgr;
                default:
                    return null;
            }
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            if (ConnectivityManager.class == serviceClass) {
                return Context.CONNECTIVITY_SERVICE;
            }
            return null;
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPkgMgr;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            if (mAllowedPermissions.contains(permission)) {
                return;
            } else {
                throw new SecurityException("Unavailable permission requested");
            }
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            if (mAllowedPermissions.contains(permission)) {
                return PERMISSION_GRANTED;
            } else {
                return PERMISSION_DENIED;
            }
        }
    }

    private IpSecService.Dependencies makeDependencies() throws RemoteException {
        final IpSecService.Dependencies deps = mock(IpSecService.Dependencies.class);
        when(deps.getNetdInstance(mTestContext)).thenReturn(mMockNetd);
        when(deps.getIpSecXfrmController()).thenReturn(mMockXfrmCtrl);
        return deps;
    }

    INetd mMockNetd;
    PackageManager mMockPkgMgr;
    IpSecXfrmController mMockXfrmCtrl;
    IpSecService.Dependencies mDeps;
    IpSecService mIpSecService;
    Network fakeNetwork = new Network(0xAB);
    int mUid = Os.getuid();

    private static final IpSecAlgorithm AUTH_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4);
    private static final IpSecAlgorithm CRYPT_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
    private static final IpSecAlgorithm AEAD_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, AEAD_KEY, 128);
    private static final int REMOTE_ENCAP_PORT = 4500;

    private static final String BLESSED_PACKAGE = "blessedPackage";
    private static final String SYSTEM_PACKAGE = "systemPackage";
    private static final String BAD_PACKAGE = "badPackage";

    public IpSecServiceParameterizedTest(
            String sourceAddr, String destAddr, String localInnerAddr, int family) {
        mSourceAddr = sourceAddr;
        mDestinationAddr = destAddr;
        mLocalInnerAddress = new LinkAddress(localInnerAddr);
        mFamily = family;
    }

    @Before
    public void setUp() throws Exception {
        mMockNetd = mock(INetd.class);
        mMockXfrmCtrl = mock(IpSecXfrmController.class);
        mMockPkgMgr = mock(PackageManager.class);
        mDeps = makeDependencies();
        mIpSecService = new IpSecService(mTestContext, mDeps);

        // PackageManager should always return true (feature flag tests in IpSecServiceTest)
        when(mMockPkgMgr.hasSystemFeature(anyString())).thenReturn(true);

        // A package granted the AppOp for MANAGE_IPSEC_TUNNELS will be MODE_ALLOWED.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq(BLESSED_PACKAGE)))
                .thenReturn(AppOpsManager.MODE_ALLOWED);
        // A system package will not be granted the app op, so this should fall back to
        // a permissions check, which should pass.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq(SYSTEM_PACKAGE)))
                .thenReturn(AppOpsManager.MODE_DEFAULT);
        // A mismatch between the package name and the UID will return MODE_IGNORED.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq(BAD_PACKAGE)))
                .thenReturn(AppOpsManager.MODE_IGNORED);
    }

    //TODO: Add a test to verify SPI.

    @Test
    public void testIpSecServiceReserveSpi() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());
        assertEquals(IpSecManager.Status.OK, spiResp.status);
        assertEquals(TEST_SPI, spiResp.spi);
    }

    @Test
    public void testReleaseSecurityParameterIndex() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());

        mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);
        try {
            userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testSecurityParameterIndexBinderDeath() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);
        try {
            userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    private int getNewSpiResourceId(String remoteAddress, int returnSpi) throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), anyString(), anyInt()))
                .thenReturn(returnSpi);

        IpSecSpiResponse spi =
                mIpSecService.allocateSecurityParameterIndex(
                        InetAddresses.parseNumericAddress(remoteAddress).getHostAddress(),
                        IpSecManager.INVALID_SECURITY_PARAMETER_INDEX,
                        new Binder());
        return spi.resourceId;
    }

    private void addDefaultSpisAndRemoteAddrToIpSecConfig(IpSecConfig config) throws Exception {
        config.setSpiResourceId(getNewSpiResourceId(mDestinationAddr, TEST_SPI));
        config.setSourceAddress(mSourceAddr);
        config.setDestinationAddress(mDestinationAddr);
    }

    private void addAuthAndCryptToIpSecConfig(IpSecConfig config) throws Exception {
        config.setEncryption(CRYPT_ALGO);
        config.setAuthentication(AUTH_ALGO);
    }

    private void addEncapSocketToIpSecConfig(int resourceId, IpSecConfig config) throws Exception {
        config.setEncapType(IpSecTransform.ENCAP_ESPINUDP);
        config.setEncapSocketResourceId(resourceId);
        config.setEncapRemotePort(REMOTE_ENCAP_PORT);
    }

    private void verifyTransformNetdCalledForCreatingSA(
            IpSecConfig config, IpSecTransformResponse resp) throws Exception {
        verifyTransformNetdCalledForCreatingSA(config, resp, 0);
    }

    private void verifyTransformNetdCalledForCreatingSA(
            IpSecConfig config, IpSecTransformResponse resp, int encapSocketPort) throws Exception {
        IpSecAlgorithm auth = config.getAuthentication();
        IpSecAlgorithm crypt = config.getEncryption();
        IpSecAlgorithm authCrypt = config.getAuthenticatedEncryption();

        verify(mMockNetd, times(1))
                .ipSecAddSecurityAssociation(
                        eq(mUid),
                        eq(config.getMode()),
                        eq(mSourceAddr),
                        eq(mDestinationAddr),
                        eq((config.getNetwork() != null) ? config.getNetwork().netId : 0),
                        eq(TEST_SPI),
                        eq(0),
                        eq(0),
                        eq((auth != null) ? auth.getName() : ""),
                        eq((auth != null) ? auth.getKey() : new byte[] {}),
                        eq((auth != null) ? auth.getTruncationLengthBits() : 0),
                        eq((crypt != null) ? crypt.getName() : ""),
                        eq((crypt != null) ? crypt.getKey() : new byte[] {}),
                        eq((crypt != null) ? crypt.getTruncationLengthBits() : 0),
                        eq((authCrypt != null) ? authCrypt.getName() : ""),
                        eq((authCrypt != null) ? authCrypt.getKey() : new byte[] {}),
                        eq((authCrypt != null) ? authCrypt.getTruncationLengthBits() : 0),
                        eq(config.getEncapType()),
                        eq(encapSocketPort),
                        eq(config.getEncapRemotePort()),
                        eq(config.getXfrmInterfaceId()));
    }

    @Test
    public void testCreateTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testCreateTransformAead() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);

        ipSecConfig.setAuthenticatedEncryption(AEAD_ALGO);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testCreateTransportModeTransformWithEncap() throws Exception {
        IpSecUdpEncapResponse udpSock = mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TRANSPORT);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);
        addEncapSocketToIpSecConfig(udpSock.resourceId, ipSecConfig);

        if (mFamily == AF_INET) {
            IpSecTransformResponse createTransformResp =
                    mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
            assertEquals(IpSecManager.Status.OK, createTransformResp.status);

            verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp, udpSock.port);
        } else {
            try {
                IpSecTransformResponse createTransformResp =
                        mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
                fail("Expected IllegalArgumentException on attempt to use UDP Encap in IPv6");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testCreateTunnelModeTransformWithEncap() throws Exception {
        IpSecUdpEncapResponse udpSock = mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TUNNEL);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);
        addEncapSocketToIpSecConfig(udpSock.resourceId, ipSecConfig);

        if (mFamily == AF_INET) {
            IpSecTransformResponse createTransformResp =
                    mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
            assertEquals(IpSecManager.Status.OK, createTransformResp.status);

            verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp, udpSock.port);
        } else {
            try {
                IpSecTransformResponse createTransformResp =
                        mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
                fail("Expected IllegalArgumentException on attempt to use UDP Encap in IPv6");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testCreateTwoTransformsWithSameSpis() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        // Attempting to create transform a second time with the same SPIs should throw an error...
        try {
            mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }

        // ... even if the transform is deleted
        mIpSecService.deleteTransform(createTransformResp.resourceId);
        try {
            mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void getTransformState() throws Exception {
        XfrmNetlinkNewSaMessage mockXfrmNewSaMsg = mock(XfrmNetlinkNewSaMessage.class);
        when(mockXfrmNewSaMsg.getBitmap()).thenReturn(new byte[512]);
        when(mMockXfrmCtrl.ipSecGetSa(any(InetAddress.class), anyLong()))
                .thenReturn(mockXfrmNewSaMsg);

        // Create transform
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        // Get transform state
        mIpSecService.getTransformState(createTransformResp.resourceId);

        // Verifications
        verify(mMockXfrmCtrl)
                .ipSecGetSa(
                        eq(InetAddresses.parseNumericAddress(mDestinationAddr)),
                        eq(Integer.toUnsignedLong(TEST_SPI)));
    }

    @Test
    public void testReleaseOwnedSpi() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);
        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        verify(mMockNetd, times(0))
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());
        // quota is not released until the SPI is released by the Transform
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);
    }

    @Test
    public void testDeleteTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        mIpSecService.deleteTransform(createTransformResp.resourceId);

        verify(mMockNetd, times(1))
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(0, userRecord.mTransformQuotaTracker.mCurrent);
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);

        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        // Verify that ipSecDeleteSa was not called when the SPI was released because the
        // ownedByTransform property should prevent it; (note, the called count is cumulative).
        verify(mMockNetd, times(1))
                .ipSecDeleteSecurityAssociation(
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);

        try {
            userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                    createTransformResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testTransportModeTransformBinderDeath() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                        createTransformResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTransformQuotaTracker.mCurrent);
        try {
            userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                    createTransformResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testApplyTransportModeTransform() throws Exception {
        verifyApplyTransportModeTransformCommon(false);
    }

    @Test
    public void testApplyTransportModeTransformReleasedSpi() throws Exception {
        verifyApplyTransportModeTransformCommon(true);
    }

    public void verifyApplyTransportModeTransformCommon(
                boolean closeSpiBeforeApply) throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);

        if (closeSpiBeforeApply) {
            mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        }

        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);

        int resourceId = createTransformResp.resourceId;
        mIpSecService.applyTransportModeTransform(pfd, IpSecManager.DIRECTION_OUT, resourceId);

        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd),
                        eq(mUid),
                        eq(IpSecManager.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI));
    }

    @Test
    public void testApplyTransportModeTransformWithClosedSpi() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);

        // Close SPI record
        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());

        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);

        int resourceId = createTransformResp.resourceId;
        mIpSecService.applyTransportModeTransform(pfd, IpSecManager.DIRECTION_OUT, resourceId);

        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd),
                        eq(mUid),
                        eq(IpSecManager.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI));
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);
        mIpSecService.removeTransportModeTransforms(pfd);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd);
    }

    private IpSecTunnelInterfaceResponse createAndValidateTunnel(
            String localAddr, String remoteAddr, String pkgName) throws Exception {
        final InterfaceConfigurationParcel config = new InterfaceConfigurationParcel();
        config.flags = new String[] {IF_STATE_DOWN};
        when(mMockNetd.interfaceGetCfg(anyString())).thenReturn(config);
        IpSecTunnelInterfaceResponse createTunnelResp =
                mIpSecService.createTunnelInterface(
                        mSourceAddr, mDestinationAddr, fakeNetwork, new Binder(), pkgName);

        assertNotNull(createTunnelResp);
        assertEquals(IpSecManager.Status.OK, createTunnelResp.status);
        for (int direction : new int[] {DIRECTION_IN, DIRECTION_OUT, DIRECTION_FWD}) {
            for (int selAddrFamily : ADDRESS_FAMILIES) {
                verify(mMockNetd).ipSecAddSecurityPolicy(
                        eq(mUid),
                        eq(selAddrFamily),
                        eq(direction),
                        anyString(),
                        anyString(),
                        eq(0),
                        anyInt(), // iKey/oKey
                        anyInt(), // mask
                        eq(createTunnelResp.resourceId));
            }
        }

        return createTunnelResp;
    }

    @Test
    public void testCreateTunnelInterface() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);

        // Check that we have stored the tracking object, and retrieve it
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                        createTunnelResp.resourceId);

        assertEquals(1, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd)
                .ipSecAddTunnelInterface(
                        eq(createTunnelResp.interfaceName),
                        eq(mSourceAddr),
                        eq(mDestinationAddr),
                        anyInt(),
                        anyInt(),
                        anyInt());
        verify(mMockNetd).interfaceSetCfg(argThat(
                config -> Arrays.asList(config.flags).contains(IF_STATE_UP)));
    }

    @Test
    public void testDeleteTunnelInterface() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);

        mIpSecService.deleteTunnelInterface(createTunnelResp.resourceId, BLESSED_PACKAGE);

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd).ipSecRemoveTunnelInterface(eq(createTunnelResp.interfaceName));

        for (int direction : new int[] {DIRECTION_OUT, DIRECTION_IN, DIRECTION_FWD}) {
            verify(mMockNetd, times(ADDRESS_FAMILIES.length))
                    .ipSecDeleteSecurityPolicy(
                            anyInt(), anyInt(), eq(direction), anyInt(), anyInt(), anyInt());
        }

        try {
            userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                    createTunnelResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {
        }
    }

    private Network createFakeUnderlyingNetwork(String interfaceName) {
        final Network fakeNetwork = new Network(1000);
        final LinkProperties fakeLp = new LinkProperties();
        fakeLp.setInterfaceName(interfaceName);
        when(mMockConnectivityMgr.getLinkProperties(eq(fakeNetwork))).thenReturn(fakeLp);
        return fakeNetwork;
    }

    @Test
    public void testSetNetworkForTunnelInterface() throws Exception {
        final IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);
        final Network newFakeNetwork = createFakeUnderlyingNetwork("newFakeNetworkInterface");
        final int tunnelIfaceResourceId = createTunnelResp.resourceId;
        mIpSecService.setNetworkForTunnelInterface(
                tunnelIfaceResourceId, newFakeNetwork, BLESSED_PACKAGE);

        final IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(1, userRecord.mTunnelQuotaTracker.mCurrent);

        final TunnelInterfaceRecord tunnelInterfaceInfo =
                userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelIfaceResourceId);
        assertEquals(newFakeNetwork, tunnelInterfaceInfo.getUnderlyingNetwork());
    }

    @Test
    public void testSetNetworkForTunnelInterfaceFailsForNullLp() throws Exception {
        final IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);
        final Network newFakeNetwork = new Network(1000);
        final int tunnelIfaceResourceId = createTunnelResp.resourceId;

        try {
            mIpSecService.setNetworkForTunnelInterface(
                    tunnelIfaceResourceId, newFakeNetwork, BLESSED_PACKAGE);
            fail(
                    "Expected an IllegalArgumentException for underlying network with null"
                            + " LinkProperties");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSetNetworkForTunnelInterfaceFailsForInvalidResourceId() throws Exception {
        final IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);
        final Network newFakeNetwork = new Network(1000);

        try {
            mIpSecService.setNetworkForTunnelInterface(
                    IpSecManager.INVALID_RESOURCE_ID, newFakeNetwork, BLESSED_PACKAGE);
            fail("Expected an IllegalArgumentException for invalid resource ID.");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSetNetworkForTunnelInterfaceFailsWhenSettingTunnelNetwork() throws Exception {
        final IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);
        final int tunnelIfaceResourceId = createTunnelResp.resourceId;
        final IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        final TunnelInterfaceRecord tunnelInterfaceInfo =
                userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelIfaceResourceId);

        final Network newFakeNetwork =
                createFakeUnderlyingNetwork(tunnelInterfaceInfo.getInterfaceName());

        try {
            mIpSecService.setNetworkForTunnelInterface(
                    tunnelIfaceResourceId, newFakeNetwork, BLESSED_PACKAGE);
            fail(
                    "Expected an IllegalArgumentException because the underlying network is the"
                            + " network being exposed by this tunnel.");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTunnelInterfaceBinderDeath() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                        createTunnelResp.resourceId);

        refcountedRecord.binderDied();

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd).ipSecRemoveTunnelInterface(eq(createTunnelResp.interfaceName));
        try {
            userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                    createTunnelResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testApplyTunnelModeTransformOutbound() throws Exception {
        verifyApplyTunnelModeTransformCommon(false /* closeSpiBeforeApply */, DIRECTION_OUT);
    }

    @Test
    public void testApplyTunnelModeTransformOutboundNonNetworkStack() throws Exception {
        mTestContext.setAllowedPermissions(android.Manifest.permission.MANAGE_IPSEC_TUNNELS);
        verifyApplyTunnelModeTransformCommon(false /* closeSpiBeforeApply */, DIRECTION_OUT);
    }

    @Test
    public void testApplyTunnelModeTransformOutboundReleasedSpi() throws Exception {
        verifyApplyTunnelModeTransformCommon(true /* closeSpiBeforeApply */, DIRECTION_OUT);
    }

    @Test
    public void testApplyTunnelModeTransformInbound() throws Exception {
        verifyApplyTunnelModeTransformCommon(true /* closeSpiBeforeApply */, DIRECTION_IN);
    }

    @Test
    public void testApplyTunnelModeTransformInboundNonNetworkStack() throws Exception {
        mTestContext.setAllowedPermissions(android.Manifest.permission.MANAGE_IPSEC_TUNNELS);
        verifyApplyTunnelModeTransformCommon(true /* closeSpiBeforeApply */, DIRECTION_IN);
    }

    @Test
    public void testApplyTunnelModeTransformForward() throws Exception {
        verifyApplyTunnelModeTransformCommon(true /* closeSpiBeforeApply */, DIRECTION_FWD);
    }

    @Test
    public void testApplyTunnelModeTransformForwardNonNetworkStack() throws Exception {
        mTestContext.setAllowedPermissions(android.Manifest.permission.MANAGE_IPSEC_TUNNELS);

        try {
            verifyApplyTunnelModeTransformCommon(true /* closeSpiBeforeApply */, DIRECTION_FWD);
            fail("Expected security exception due to use of forward policies without NETWORK_STACK"
                     + " or MAINLINE_NETWORK_STACK permission");
        } catch (SecurityException expected) {
        }
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformOutbound() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(false, DIRECTION_OUT);
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformOutboundReleasedSpi() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(true, DIRECTION_OUT);
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformInbound() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(false, DIRECTION_IN);
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformInboundReleasedSpi() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(true, DIRECTION_IN);
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformForward() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(false, DIRECTION_FWD);
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testApplyAndMigrateTunnelModeTransformForwardReleasedSpi() throws Exception {
        verifyApplyAndMigrateTunnelModeTransformCommon(true, DIRECTION_FWD);
    }

    public void verifyApplyTunnelModeTransformCommon(boolean closeSpiBeforeApply, int direction)
            throws Exception {
        verifyApplyTunnelModeTransformCommon(
                new IpSecConfig(), closeSpiBeforeApply, false /* isMigrating */, direction);
    }

    public void verifyApplyAndMigrateTunnelModeTransformCommon(
            boolean closeSpiBeforeApply, int direction) throws Exception {
        verifyApplyTunnelModeTransformCommon(
                new IpSecConfig(), closeSpiBeforeApply, true /* isMigrating */, direction);
    }

    public int verifyApplyTunnelModeTransformCommon(
            IpSecConfig ipSecConfig,
            boolean closeSpiBeforeApply,
            boolean isMigrating,
            int direction)
            throws Exception {
        ipSecConfig.setMode(IpSecTransform.MODE_TUNNEL);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);

        if (closeSpiBeforeApply) {
            mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        }

        int transformResourceId = createTransformResp.resourceId;
        int tunnelResourceId = createTunnelResp.resourceId;

        if (isMigrating) {
            mIpSecService.migrateTransform(
                    transformResourceId, NEW_SRC_ADDRESS, NEW_DST_ADDRESS, BLESSED_PACKAGE);
        }

        mIpSecService.applyTunnelModeTransform(
                tunnelResourceId, direction, transformResourceId, BLESSED_PACKAGE);

        for (int selAddrFamily : ADDRESS_FAMILIES) {
            verify(mMockNetd)
                    .ipSecUpdateSecurityPolicy(
                            eq(mUid),
                            eq(selAddrFamily),
                            eq(direction),
                            anyString(),
                            anyString(),
                            eq(direction == DIRECTION_OUT ? TEST_SPI : 0),
                            anyInt(), // iKey/oKey
                            anyInt(), // mask
                            eq(tunnelResourceId));
        }

        ipSecConfig.setXfrmInterfaceId(tunnelResourceId);
        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);

        if (isMigrating) {
            verify(mMockNetd, times(ADDRESS_FAMILIES.length))
                    .ipSecMigrate(any(IpSecMigrateInfoParcel.class));
        } else {
            verify(mMockNetd, never()).ipSecMigrate(any());
        }

        return tunnelResourceId;
    }

    @Test
    public void testApplyTunnelModeTransformWithClosedSpi() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TUNNEL);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), BLESSED_PACKAGE);
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, BLESSED_PACKAGE);

        // Close SPI record
        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());

        int transformResourceId = createTransformResp.resourceId;
        int tunnelResourceId = createTunnelResp.resourceId;
        mIpSecService.applyTunnelModeTransform(
                tunnelResourceId, IpSecManager.DIRECTION_OUT, transformResourceId, BLESSED_PACKAGE);

        for (int selAddrFamily : ADDRESS_FAMILIES) {
            verify(mMockNetd)
                    .ipSecUpdateSecurityPolicy(
                            eq(mUid),
                            eq(selAddrFamily),
                            eq(IpSecManager.DIRECTION_OUT),
                            anyString(),
                            anyString(),
                            eq(TEST_SPI),
                            anyInt(), // iKey/oKey
                            anyInt(), // mask
                            eq(tunnelResourceId));
        }

        ipSecConfig.setXfrmInterfaceId(tunnelResourceId);
        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testAddRemoveAddressFromTunnelInterface() throws Exception {
        for (String pkgName : new String[] {BLESSED_PACKAGE, SYSTEM_PACKAGE}) {
            IpSecTunnelInterfaceResponse createTunnelResp =
                    createAndValidateTunnel(mSourceAddr, mDestinationAddr, pkgName);
            mIpSecService.addAddressToTunnelInterface(
                    createTunnelResp.resourceId, mLocalInnerAddress, pkgName);
            verify(mMockNetd, times(1))
                    .interfaceAddAddress(
                            eq(createTunnelResp.interfaceName),
                            eq(mLocalInnerAddress.getAddress().getHostAddress()),
                            eq(mLocalInnerAddress.getPrefixLength()));
            mIpSecService.removeAddressFromTunnelInterface(
                    createTunnelResp.resourceId, mLocalInnerAddress, pkgName);
            verify(mMockNetd, times(1))
                    .interfaceDelAddress(
                            eq(createTunnelResp.interfaceName),
                            eq(mLocalInnerAddress.getAddress().getHostAddress()),
                            eq(mLocalInnerAddress.getPrefixLength()));
            mIpSecService.deleteTunnelInterface(createTunnelResp.resourceId, pkgName);
        }
    }

    @Ignore
    @Test
    public void testAddTunnelFailsForBadPackageName() throws Exception {
        try {
            IpSecTunnelInterfaceResponse createTunnelResp =
                    createAndValidateTunnel(mSourceAddr, mDestinationAddr, BAD_PACKAGE);
            fail("Expected a SecurityException for badPackage.");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testFeatureFlagIpSecTunnelsVerification() throws Exception {
        when(mMockPkgMgr.hasSystemFeature(eq(PackageManager.FEATURE_IPSEC_TUNNELS)))
                .thenReturn(false);

        try {
            String addr = Inet4Address.getLoopbackAddress().getHostAddress();
            mIpSecService.createTunnelInterface(
                    addr, addr, new Network(0), new Binder(), BLESSED_PACKAGE);
            fail("Expected UnsupportedOperationException for disabled feature");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testFeatureFlagIpSecTunnelMigrationVerification() throws Exception {
        when(mMockPkgMgr.hasSystemFeature(eq(FEATURE_IPSEC_TUNNEL_MIGRATION))).thenReturn(false);

        try {
            mIpSecService.migrateTransform(
                    1 /* transformId */, NEW_SRC_ADDRESS, NEW_DST_ADDRESS, BLESSED_PACKAGE);
            fail("Expected UnsupportedOperationException for disabled feature");
        } catch (UnsupportedOperationException expected) {
        }
    }
}
