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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import net.solarnetwork.node.hw.atm90e36.Atm90E36;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.PgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Wiring;
import net.solarnetwork.node.hw.atm90e36.test.FakeSpiDevice;

/**
 * Tests for the CSV output format.
 *
 * @author matt
 * @version 1.0
 */
public class MeterToolTests {

	@Test
	public void csvHeader() {
		then(MeterTool.CSV_HEADER)
				.isEqualTo("Timestamp,Voltage_A,Voltage_B,Voltage_C,Current_A,Current_B,Current_C,"
						+ "Power_A,Power_B,Power_C,Power_Total,PF_Total,Frequency");
	}

	@Test
	public void meterConfigDefaultsAreThreePhaseFourWireAllPhases() {
		Atm90E36Config config = MeterTool.meterConfig(Map.of());

		then(config.getWiring()).as("wiring").isEqualTo(Wiring.THREE_PHASE_FOUR_WIRE);
		then(config.getLineFrequency()).as("line frequency").isEqualTo(LineFrequency.HZ_50);
		then(config.getSummedPhases()).as("all phases summed").containsExactly(Phase.A, Phase.B,
				Phase.C);
		then(config.meteringMode()).as("MMode0, the app note's 3P4W 50Hz value").isEqualTo(0x0087);
		then(config.pgaGainMode()).as("MMode1").isEqualTo(21);
		then(config.getVoltageGain()).as("voltage gain").isEqualTo(50000);
		then(config.getCurrentGainA()).as("current gain A").isEqualTo(32498);
		then(config.getCurrentGainB()).as("current gain B").isEqualTo(32498);
		then(config.getCurrentGainC()).as("current gain C").isEqualTo(32498);
	}

	@Test
	public void meterConfigAppliesCommandLineOverrides() {
		// GIVEN a two-element 60Hz three-wire deployment
		Map<String, String> args = Map.of("line-frequency", "60", "wiring", "3p3w", "phases", "ac",
				"pga-gain", "4", "meter-constant", "1600", "voltage-gain", "12345", "current-gain",
				"23456");

		// WHEN
		Atm90E36Config config = MeterTool.meterConfig(args);

		// THEN
		then(config.getLineFrequency()).as("line frequency").isEqualTo(LineFrequency.HZ_60);
		then(config.getWiring()).as("wiring").isEqualTo(Wiring.THREE_PHASE_THREE_WIRE);
		then(config.getSummedPhases()).as("both measuring elements counted").containsExactly(Phase.A,
				Phase.C);
		then(config.getCurrentGainI1()).as("PGA gain").isEqualTo(PgaGain.X4);
		then(config.getMeterConstant()).as("meter constant").isEqualTo(1600);
		then(config.getVoltageGain()).as("voltage gain").isEqualTo(12345);
		then(config.getCurrentGainC()).as("current gain").isEqualTo(23456);
	}

	@Test
	public void meterConfigForAFourWireDeployment() {
		Atm90E36Config config = MeterTool
				.meterConfig(Map.of("wiring", "3p4w", "phases", "abc", "line-frequency", "60"));

		then(config.getWiring()).isEqualTo(Wiring.THREE_PHASE_FOUR_WIRE);
		then(config.getSummedPhases()).containsExactly(Phase.A, Phase.B, Phase.C);
		then(config.meteringMode()).as("MMode0 b8 clear, b12 set, all EnP bits set").isEqualTo(0x1087);
	}

	@Test
	public void parseArgsReadTakesZeroOrMoreRegisters() {
		then(MeterTool.parseArgs(new String[] { "--read" })).as("no registers").containsEntry("read",
				"");
		then(MeterTool.parseArgs(new String[] { "--read", "MMode0", "0xB0", "--mode", "csv" }))
				.as("registers up to the next option").containsEntry("read", "MMode0,0xB0")
				.containsEntry("mode", "csv");
		then(MeterTool.parseArgs(new String[] { "--read=MMode0,176" })).as("inline registers")
				.containsEntry("read", "MMode0,176");
	}

	@Test
	public void parseRegistersAcceptsNamesAndNumbers() {
		then(MeterTool.parseRegisters("pmeant, 0xB1 177,0XC0")).containsExactly(0xB0, 0xB1, 0xB1, 0xC0);
	}

	@Test
	public void parseRegistersDefaultsToTheDiagnosticSet() {
		then(MeterTool.parseRegisters("")).containsExactly(0x33, 0x35, 0x38, 0xD9, 0xDD, 0xB0, 0xC0,
				0xB1, 0xC1, 0xB5, 0xBD, 0x95);
	}

	@Test
	public void parseRegistersRejectsUnknownAndOutOfRange() {
		thenThrownBy(() -> MeterTool.parseRegisters("bogus"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bogus");
		thenThrownBy(() -> MeterTool.parseRegisters("0x8000"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
	}

	@Test
	public void registerLinesAlignValuesAndOnlyRead() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0x33, 0x0087);
		fake.registers.put(0xC0, 0x1400);

		// WHEN
		List<String> lines = MeterTool.registerLines(new Atm90E36(fake), List.of(0x33, 0xC0, 0x180));

		// THEN
		then(lines).as("labels padded to the widest, with unknown addresses unnamed").containsExactly(
				"0x0033 (MMode0)   : 0x0087", //
				"0x00C0 (PmeanTLSB): 0x1400", //
				"0x0180            : 0x0000");
		then(fake.writes).as("reading registers writes nothing").isEmpty();
	}

	@Test
	public void parseArgsAcceptsBothOptionForms() {
		then(MeterTool.parseArgs(new String[] { "--mode", "csv", "--phases=ac" }))
				.containsEntry("mode", "csv").containsEntry("phases", "ac");
	}

	@Test
	public void parseArgsRejectsUnknownAndIncompleteOptions() {
		thenThrownBy(() -> MeterTool.parseArgs(new String[] { "--nope", "1" }))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown argument");
		thenThrownBy(() -> MeterTool.parseArgs(new String[] { "--phases" }))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing value");
	}

	@Test
	public void parsePhasesRejectsGarbage() {
		thenThrownBy(() -> MeterTool.parsePhases("axc")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid phase");
	}

	@Test
	public void csvRowFormatting() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0xD9, 65427); // UrmsA  -> 654.27
		fake.registers.put(0xDD, 65427); // IrmsA  -> 65.427
		fake.registers.put(0xB1, 5); // PmeanA  -> 5 W
		fake.registers.put(0xC1, 0); // PmeanA LSB
		fake.registers.put(0xB0, 5); // PmeanT  -> 5 * 4 W = 20 W
		fake.registers.put(0xC0, 0); // PmeanT LSB
		fake.registers.put(0xBC, 0x03E8); // PFmeanT -> 1.000
		fake.registers.put(0xF8, 6001); // Freq -> 60.01

		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		String row = MeterTool.csvRow(eic, Instant.parse("2026-09-10T12:34:56Z"));

		// THEN
		then(row).isEqualTo("2026-09-10T12:34:56Z,654.27,0.00,0.00,65.427,0.000,0.000,"
				+ "5.00,0.00,0.00,20.00,1.000,60.01");
	}

}
