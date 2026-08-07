/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.net.proto;

import java.net.Inet4Address;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The banner's address list.  Environment-tolerant on purpose: an isolated CI
 * host may legitimately answer with nothing - what is pinned is what may NEVER
 * appear, whatever the host: a loopback address (the banner tells an operator
 * where OTHER machines reach the bench) and anything that is not plain IPv4.
 */
class NetInterfacesTest {

    @Test
    void theBannerAddressesAreIpv4AndNeverLoopback() {
        for (InetAddress address : NetInterfaces.listenAddresses()) {
            assertTrue(address instanceof Inet4Address,
                    "an operator pastes bench URLs of the 192.168.x.x kind - "
                            + "IPv6 needs bracket-and-zone escaping: " + address);
            assertFalse(address.isLoopbackAddress(),
                    "127.x is where the server IS, not where a client finds it");
        }
    }
}
