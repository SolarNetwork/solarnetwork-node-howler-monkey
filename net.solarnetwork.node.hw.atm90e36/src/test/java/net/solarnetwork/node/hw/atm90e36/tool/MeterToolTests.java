/* ==================================================================
 * MeterToolTests.java - 10/09/2026
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

package net.solarnetwork.node.hw.atm90e36.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import net.solarnetwork.node.hw.atm90e36.Atm90E36;
import net.solarnetwork.node.hw.atm90e36.test.FakeSpiDevice;

/**
 * Tests for the CSV output format, which must match {@code meter-tool-2.py}.
 */
class MeterToolTests {

	@Test
	void csvHeader() {
		assertEquals("Timestamp,Voltage_A,Voltage_B,Voltage_C,Current_A,Current_B,Current_C,"
				+ "Power_A,Power_B,Power_C,Power_Total,PF_Total,Frequency", MeterTool.CSV_HEADER);
	}

	@Test
	void csvRowFormatting() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0xD9, 65427); // UrmsA  -> 654.27
		fake.registers.put(0xDD, 65427); // IrmsA  -> 65.427
		fake.registers.put(0xB1, 5); // PmeanA  -> 104.8576
		fake.registers.put(0xC1, 0); // PmeanA LSB
		fake.registers.put(0xB0, 5); // PmeanT  -> 104.8576
		fake.registers.put(0xC0, 0); // PmeanT LSB
		fake.registers.put(0xBC, 0x03E8); // PFmeanT -> 1.000
		fake.registers.put(0xF8, 6001); // Freq -> 60.01

		Atm90E36 eic = new Atm90E36(fake);
		String row = MeterTool.csvRow(eic, Instant.parse("2026-09-10T12:34:56Z"));

		assertEquals("2026-09-10T12:34:56Z,654.27,0.00,0.00,65.427,0.000,0.000,"
				+ "104.86,0.00,0.00,104.86,1.000,60.01", row);
	}

}
