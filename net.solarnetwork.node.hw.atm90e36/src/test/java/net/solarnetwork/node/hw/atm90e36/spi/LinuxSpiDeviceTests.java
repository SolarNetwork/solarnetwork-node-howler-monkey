/* ==================================================================
 * LinuxSpiDeviceTests.java - 10/09/2026
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.hw.atm90e36.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Verify the {@code _IOR}/{@code _IOW} encoding against the constant values that
 * {@code linux/spi/spidev.h} expands to on arm/arm64 (asm-generic ioctl.h).
 */
class LinuxSpiDeviceTests {

	@Test
	void spiIocWrMode32() {
		assertEquals(0x40046b05L, LinuxSpiDevice.SPI_IOC_WR_MODE32);
	}

	@Test
	void spiIocWrMaxSpeedHz() {
		assertEquals(0x40046b04L, LinuxSpiDevice.SPI_IOC_WR_MAX_SPEED_HZ);
	}

	@Test
	void spiIocWrBitsPerWord() {
		assertEquals(0x40016b03L, LinuxSpiDevice.SPI_IOC_WR_BITS_PER_WORD);
	}

	@Test
	void spiIocMessageForOneTransfer() {
		// _IOW('k', 0, char[32]) == 0x40206b00
		assertEquals(0x40206b00L, LinuxSpiDevice.spiIocMessage(1));
	}

	@Test
	void spiIocMessageForThreeTransfers() {
		// _IOW('k', 0, char[96])
		assertEquals(0x40606b00L, LinuxSpiDevice.spiIocMessage(3));
	}

	@Test
	void spiIocMessageForSixteenTransfers() {
		// _IOW('k', 0, char[512]) -- the batched CSV read
		assertEquals(0x42006b00L, LinuxSpiDevice.spiIocMessage(16));
	}

	@Test
	void transferStructIs32Bytes() {
		assertEquals(32, LinuxSpiDevice.SPI_IOC_TRANSFER_SIZE);
	}

}
