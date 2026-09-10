/* ==================================================================
 * Atm90E36Tests.java - 10/09/2026
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

package net.solarnetwork.node.hw.atm90e36;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import net.solarnetwork.node.hw.atm90e36.test.FakeSpiDevice;

/**
 * Unit tests for {@link Atm90E36} measurement scaling and SPI framing, using a
 * {@link FakeSpiDevice} so no hardware is required. Expected values are the same
 * as those produced by {@code meter-tool-2.py}.
 */
class Atm90E36Tests {

	// register addresses (from the ATM90E36 datasheet / meter-tool-2.py)
	private static final int UrmsA = 0xD9;
	private static final int IrmsA = 0xDD;
	private static final int PmeanA = 0xB1;
	private static final int PmeanALSB = 0xC1;
	private static final int PFmeanT = 0xBC;
	private static final int Freq = 0xF8;
	private static final int MMode0 = 0x33;
	private static final int MMode1 = 0x34;
	private static final int UgainA = 0x61;
	private static final int IgainA = 0x62;
	private static final int CSZero = 0x3B;

	@Test
	void readFramingMatchesSpidevWireFormat() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(Freq, 5000);
		Atm90E36 eic = new Atm90E36(fake);

		double f = eic.getFrequency();

		assertEquals(50.0, f, 1e-9);
		// address 0xF8 | read-bit 0x8000 -> 0x80F8, byte-swapped to 0xF880
		assertArrayEquals(new byte[] { (byte) 0xF8, (byte) 0x80, 0, 0 }, fake.txFrames.get(0));
	}

	@Test
	void readRegisterRoundTrip() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0x42, 0xABCD);

		int value = new Atm90E36(fake).readRegister(0x42);

		assertEquals(0xABCD, value);
		// 0x42 | 0x8000 -> 0x8042, byte-swapped to 0x4280
		assertArrayEquals(new byte[] { (byte) 0x42, (byte) 0x80, 0, 0 }, fake.txFrames.get(0));
	}

	@Test
	void writeRegisterFraming() {
		FakeSpiDevice fake = new FakeSpiDevice();

		new Atm90E36(fake).writeRegister(0x33, 0x01F4); // MMode0 <- 500

		// address 0x33 -> byte-swapped 0x3300; value 0x01F4 -> byte-swapped 0xF401
		assertArrayEquals(new byte[] { (byte) 0x33, 0x00, (byte) 0xF4, 0x01 }, fake.txFrames.get(0));
		assertEquals(1, fake.writes.size());
		assertEquals(0x33, fake.writes.get(0).address);
		assertEquals(0x01F4, fake.writes.get(0).value);
	}

	@Test
	void lineVoltageScaling() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(UrmsA, 65427);
		assertEquals(654.27, new Atm90E36(fake).getLineVoltageA(), 1e-9);
	}

	@Test
	void lineCurrentScaling() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(IrmsA, 65427);
		assertEquals(65.427, new Atm90E36(fake).getLineCurrentA(), 1e-9);
	}

	@Test
	void activePowerPositive() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PmeanA, 5);
		fake.registers.put(PmeanALSB, 0);
		// (5 * 65536 + 0) * 0.00032
		assertEquals(104.8576, new Atm90E36(fake).getActivePowerA(), 1e-9);
	}

	@Test
	void activePowerNegativeTwosComplement() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PmeanA, 0xFFFF); // -1
		fake.registers.put(PmeanALSB, 0);
		assertEquals(-20.97152, new Atm90E36(fake).getActivePowerA(), 1e-9);
	}

	@Test
	void powerFactorNegative() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PFmeanT, 0xFC18); // -1000
		assertEquals(-1.0, new Atm90E36(fake).getTotalPowerFactor(), 1e-9);
	}

	@Test
	void beginConfiguresBusAndPushesCalibration() {
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 eic = new Atm90E36(fake); // defaults: 500 / 21 / 50000 / 32498

		eic.begin();

		assertTrue(fake.open);
		assertEquals(3, fake.mode);
		assertEquals(200000, fake.maxSpeedHz);
		assertEquals(8, fake.bitsPerWord);

		assertEquals(500, fake.registers.get(MMode0));
		assertEquals(21, fake.registers.get(MMode1));
		assertEquals(50000, fake.registers.get(UgainA));
		assertEquals(32498, fake.registers.get(IgainA));

		// CSZero holds the XOR of the CONFIG-block values written before it
		int expected = 0x5678 ^ 0x0861 ^ 0xC468 ^ 500 ^ 21 ^ 0x1D4C ^ 0x1D4C ^ 0x1D4C ^ 0x02EE
				^ 0x02EE ^ 0x02EE;
		assertEquals(expected & 0xFFFF, fake.registers.get(CSZero));
	}

}
