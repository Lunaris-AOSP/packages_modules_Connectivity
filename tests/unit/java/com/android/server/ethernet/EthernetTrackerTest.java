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

package com.android.server.ethernet;

import static android.net.TestNetworkManager.TEST_TAP_PREFIX;

import static com.android.server.ethernet.EthernetTracker.DEFAULT_CAPABILITIES;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;
import android.os.Build;
import android.os.HandlerThread;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.server.ethernet.EthernetTracker.EthernetConfigParser;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.ArrayList;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class EthernetTrackerTest {
    private static final String TEST_IFACE = "test123";
    private static final int TIMEOUT_MS = 1_000;
    private static final String THREAD_NAME = "EthernetServiceThread";
    private static final EthernetCallback NULL_CB = new EthernetCallback(null);
    private EthernetTracker tracker;
    private HandlerThread mHandlerThread;
    @Mock private Context mContext;
    @Mock private EthernetNetworkFactory mFactory;
    @Mock private INetd mNetd;
    @Mock private EthernetTracker.Dependencies mDeps;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        initMockResources();
        doReturn(false).when(mFactory).updateInterfaceLinkState(anyString(), anyBoolean());
        doReturn(new String[0]).when(mNetd).interfaceGetList();
        doReturn(new String[0]).when(mFactory).getAvailableInterfaces(anyBoolean());
        mHandlerThread = new HandlerThread(THREAD_NAME);
        mHandlerThread.start();
        tracker = new EthernetTracker(mContext, mHandlerThread.getThreadHandler(), mFactory, mNetd,
                mDeps);
    }

    @After
    public void cleanUp() throws InterruptedException {
        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    private void initMockResources() {
        doReturn("").when(mDeps).getInterfaceRegexFromResource(eq(mContext));
        doReturn(new String[0]).when(mDeps).getInterfaceConfigFromResource(eq(mContext));
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    /**
     * Test: Creation of various valid static IP configurations
     */
    @Test
    public void createStaticIpConfiguration() {
        // Empty gives default StaticIPConfiguration object
        assertStaticConfiguration(new StaticIpConfiguration(), "");

        // Setting only the IP address properly cascades and assumes defaults
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24")).build(), "ip=192.0.2.10/24");

        final ArrayList<InetAddress> dnsAddresses = new ArrayList<>();
        dnsAddresses.add(InetAddresses.parseNumericAddress("4.4.4.4"));
        dnsAddresses.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        // Setting other fields properly cascades them
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .setDnsServers(dnsAddresses)
                .setGateway(InetAddresses.parseNumericAddress("192.0.2.1"))
                .setDomains("android").build(),
                "ip=192.0.2.10/24 dns=4.4.4.4,8.8.8.8 gateway=192.0.2.1 domains=android");

        // Verify order doesn't matter
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .setDnsServers(dnsAddresses)
                .setGateway(InetAddresses.parseNumericAddress("192.0.2.1"))
                .setDomains("android").build(),
                "domains=android ip=192.0.2.10/24 gateway=192.0.2.1 dns=4.4.4.4,8.8.8.8 ");
    }

    /**
     * Test: Attempt creation of various bad static IP configurations
     */
    @Test
    public void createStaticIpConfiguration_Bad() {
        assertStaticConfigurationFails("ip=192.0.2.1/24 gateway= blah=20.20.20.20");  // Unknown key
        assertStaticConfigurationFails("ip=192.0.2.1");  // mask is missing
        assertStaticConfigurationFails("ip=a.b.c");  // not a valid ip address
        assertStaticConfigurationFails("dns=4.4.4.4,1.2.3.A");  // not valid ip address in dns
        assertStaticConfigurationFails("=");  // Key and value is empty
        assertStaticConfigurationFails("ip=");  // Value is empty
        assertStaticConfigurationFails("ip=192.0.2.1/24 gateway=");  // Gateway is empty
    }

    private void assertStaticConfigurationFails(String config) {
        try {
            EthernetTracker.parseStaticIpConfiguration(config);
            fail("Expected to fail: " + config);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    private void assertStaticConfiguration(StaticIpConfiguration expectedStaticIpConfig,
                String configAsString) {
        final IpConfiguration expectedIpConfiguration = new IpConfiguration();
        expectedIpConfiguration.setIpAssignment(IpAssignment.STATIC);
        expectedIpConfiguration.setProxySettings(ProxySettings.NONE);
        expectedIpConfiguration.setStaticIpConfiguration(expectedStaticIpConfig);

        assertEquals(expectedIpConfiguration,
                EthernetTracker.parseStaticIpConfiguration(configAsString));
    }

    private NetworkCapabilities.Builder makeEthernetCapabilitiesBuilder(boolean clearDefaults) {
        final NetworkCapabilities.Builder builder =
                clearDefaults
                        ? NetworkCapabilities.Builder.withoutDefaultCapabilities()
                        : new NetworkCapabilities.Builder();
        return builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
    }


    @Test
    public void testNetworkCapabilityParsing() {
        final NetworkCapabilities baseNc = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .setLinkUpstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                .setLinkDownstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .build();

        // Empty capabilities always default to the baseNc above.
        EthernetConfigParser p = new EthernetConfigParser("eth0;", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);
        p = new EthernetConfigParser("eth0;", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        // On Android B+, "*" defaults to using DEFAULT_CAPABILITIES.
        p = new EthernetConfigParser("eth0;*;;;;;;", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(DEFAULT_CAPABILITIES);

        // But not so before B.
        p = new EthernetConfigParser("eth0;*", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        p = new EthernetConfigParser("eth0;12,13,14,15;", false /*isAtLeastB*/);
        assertThat(p.mCaps.getCapabilities()).asList().containsAtLeast(12, 13, 14, 15);

        p = new EthernetConfigParser("eth0;12,13,500,abc", false /*isAtLeastB*/);
        // 18, 20, 21 are added by EthernetConfigParser.
        assertThat(p.mCaps.getCapabilities()).asList().containsExactly(12, 13, 18, 20, 21);

        p = new EthernetConfigParser("eth0;1,2,3;;0", false /*isAtLeastB*/);
        assertThat(p.mCaps.getCapabilities()).asList().containsAtLeast(1, 2, 3);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();

        // TRANSPORT_VPN (4) is not allowed.
        p = new EthernetConfigParser("eth0;;;4", false /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).isTrue();
        p = new EthernetConfigParser("eth0;*;;4", true /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).isTrue();

        // invalid capability and transport type
        p = new EthernetConfigParser("eth0;-1,a,1000,,;;-1", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        p = new EthernetConfigParser("eth0;*;;0", false /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();
        p = new EthernetConfigParser("eth0;*;;0", true /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();

        NetworkCapabilities nc = new NetworkCapabilities.Builder(DEFAULT_CAPABILITIES)
                .removeTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        p = new EthernetConfigParser("eth0;*;;0", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(nc);
    }

    @Test
    public void testInterfaceNameParsing() {
        EthernetConfigParser p = new EthernetConfigParser("eth12", false /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("eth12");

        p = new EthernetConfigParser("", true /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("");

        p = new EthernetConfigParser("eth0;12;", true /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("eth0");
    }

    @Test
    public void testIpConfigParsing() {
        // Note that EthernetConfigParser doesn't actually parse the IpConfig (yet).
        final EthernetConfigParser p = new EthernetConfigParser(
                "eth0;1,2,3;ip=192.168.0.10/24 gateway=192.168.0.1 dns=4.4.4.4,8.8.8.8;1",
                false /*isAtLeastB*/);
        assertThat(p.mIpConfig)
                .isEqualTo("ip=192.168.0.10/24 gateway=192.168.0.1 dns=4.4.4.4,8.8.8.8");
    }

    @Test
    public void testCreateEthernetConfigParserThrowsNpeWithNullInput() {
        assertThrows(NullPointerException.class, () -> new EthernetConfigParser(null, false));
    }

    @Test
    public void testUpdateConfiguration() {
        final NetworkCapabilities capabilities = new NetworkCapabilities.Builder().build();
        final LinkAddress linkAddr = new LinkAddress("192.0.2.2/25");
        final StaticIpConfiguration staticIpConfig =
                new StaticIpConfiguration.Builder().setIpAddress(linkAddr).build();
        final IpConfiguration ipConfig =
                new IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build();
        final EthernetCallback listener = new EthernetCallback(null);

        tracker.updateConfiguration(TEST_IFACE, ipConfig, capabilities, listener);
        waitForIdle();

        verify(mFactory).updateInterface(
                eq(TEST_IFACE), eq(ipConfig), eq(capabilities));
    }

    @Test
    public void testIsValidTestInterfaceIsFalseWhenTestInterfacesAreNotIncluded() {
        final String validIfaceName = TEST_TAP_PREFIX + "123";
        tracker.setIncludeTestInterfaces(false);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(validIfaceName);

        assertFalse(isValidTestInterface);
    }

    @Test
    public void testIsValidTestInterfaceIsFalseWhenTestInterfaceNameIsInvalid() {
        final String invalidIfaceName = "123" + TEST_TAP_PREFIX;
        tracker.setIncludeTestInterfaces(true);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(invalidIfaceName);

        assertFalse(isValidTestInterface);
    }

    @Test
    public void testIsValidTestInterfaceIsTrueWhenTestInterfacesIncludedAndValidName() {
        final String validIfaceName = TEST_TAP_PREFIX + "123";
        tracker.setIncludeTestInterfaces(true);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(validIfaceName);

        assertTrue(isValidTestInterface);
    }
}
