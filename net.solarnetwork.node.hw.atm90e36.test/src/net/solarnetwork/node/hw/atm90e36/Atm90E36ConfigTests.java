/* ==================================================================
 * Atm90E36ConfigTests.java - 11/09/2026
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import java.util.EnumSet;
import org.junit.Test;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.CurrentSensor;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.DigitalPgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.PgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.SumMethod;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Wiring;

/**
 * Unit tests for {@link Atm90E36Config}, checking that the field-level
 * properties compose into the {@code MMode0} and {@code MMode1} register words
 * documented in the ATM90E36 datasheet and its application note.
 *
 * @author matt
 * @version 1.0
 */
public class Atm90E36ConfigTests {

	@Test
	public void defaultsMatchThePowerOnRegisterValues() {
		Atm90E36Config config = new Atm90E36Config();

		then(config.meteringMode()).as("MMode0 reset value").isEqualTo(0x0087);
		then(config.pgaGainMode()).as("MMode1 reset value").isEqualTo(0x0000);
	}

	@Test
	public void threePhaseThreeWireMatchesApplicationNote() {
		// GIVEN application note section 4.2.3 (b): 3P3W, 50Hz -> 0185H
		Atm90E36Config config = new Atm90E36Config();

		// WHEN
		config.setWiring(Wiring.THREE_PHASE_THREE_WIRE);
		config.setSummedPhases(EnumSet.of(Phase.A, Phase.C));

		// THEN
		then(config.meteringMode()).isEqualTo(0x0185);
	}

	@Test
	public void northAmericanSplitPhaseMatchesTheKnownWord() {
		// GIVEN the 4485 value the Arduino libraries use for 60Hz split phase
		Atm90E36Config config = new Atm90E36Config();

		// WHEN
		config.setWiring(Wiring.THREE_PHASE_THREE_WIRE);
		config.setLineFrequency(LineFrequency.HZ_60);
		config.setSummedPhases(EnumSet.of(Phase.A, Phase.C));

		// THEN
		then(config.meteringMode()).isEqualTo(4485);
	}

	@Test
	public void eachMeteringModeFieldSetsItsOwnBit() {
		Atm90E36Config config = new Atm90E36Config();
		config.setSummedPhases(EnumSet.noneOf(Phase.class));
		config.setCf2ReactiveEnergy(false);

		then(config.meteringMode()).as("every field cleared").isEqualTo(0x0000);

		config.setCurrentChannelsSwapped(true);
		then(config.meteringMode()).as("I1I3Swap").isEqualTo(1 << 13);

		config.setCurrentChannelsSwapped(false);
		config.setLineFrequency(LineFrequency.HZ_60);
		then(config.meteringMode()).as("Freq60Hz").isEqualTo(1 << 12);

		config.setLineFrequency(LineFrequency.HZ_50);
		config.setHighPassFilterEnabled(false);
		then(config.meteringMode()).as("HPFOff is inverted").isEqualTo(1 << 11);

		config.setHighPassFilterEnabled(true);
		config.setCurrentSensor(CurrentSensor.ROGOWSKI_COIL);
		then(config.meteringMode()).as("didtEn").isEqualTo(1 << 10);

		config.setCurrentSensor(CurrentSensor.CURRENT_TRANSFORMER);
		config.setHighResolutionEnergy(true);
		then(config.meteringMode()).as("001LSB").isEqualTo(1 << 9);

		config.setHighResolutionEnergy(false);
		config.setWiring(Wiring.THREE_PHASE_THREE_WIRE);
		then(config.meteringMode()).as("3P3W").isEqualTo(1 << 8);

		config.setWiring(Wiring.THREE_PHASE_FOUR_WIRE);
		config.setCf2ReactiveEnergy(true);
		then(config.meteringMode()).as("CF2varh").isEqualTo(1 << 7);

		config.setCf2ReactiveEnergy(false);
		config.setApparentEnergyVectorSum(true);
		then(config.meteringMode()).as("CF2ESV").isEqualTo(1 << 6);

		config.setApparentEnergyVectorSum(false);
		config.setReactivePowerSum(SumMethod.ABSOLUTE);
		then(config.meteringMode()).as("ABSEnQ").isEqualTo(1 << 4);

		config.setReactivePowerSum(SumMethod.ARITHMETIC);
		config.setActivePowerSum(SumMethod.ABSOLUTE);
		then(config.meteringMode()).as("ABSEnP").isEqualTo(1 << 3);
	}

	@Test
	public void summedPhasesMapToTheEnPBits() {
		Atm90E36Config config = new Atm90E36Config();
		config.setCf2ReactiveEnergy(false);

		config.setSummedPhases(EnumSet.of(Phase.A));
		then(config.meteringMode()).as("EnPA").isEqualTo(1 << 2);

		config.setSummedPhases(EnumSet.of(Phase.B));
		then(config.meteringMode()).as("EnPB").isEqualTo(1 << 1);

		config.setSummedPhases(EnumSet.of(Phase.C));
		then(config.meteringMode()).as("EnPC").isEqualTo(1 << 0);

		config.setSummedPhases(EnumSet.allOf(Phase.class));
		then(config.meteringMode()).as("all three phases").isEqualTo(0b111);
	}

	@Test
	public void phaseCurrentPgaGainMatchesTheKnownWords() {
		Atm90E36Config config = new Atm90E36Config();

		config.setPhaseCurrentPgaGain(PgaGain.X2);
		then(config.pgaGainMode()).as("2X on I1/I2/I3, for 100A CTs").isEqualTo(21);

		config.setPhaseCurrentPgaGain(PgaGain.X4);
		then(config.pgaGainMode()).as("4X on I1/I2/I3, for 200A CTs").isEqualTo(42);
	}

	@Test
	public void phaseCurrentPgaGainLeavesNeutralAlone() {
		Atm90E36Config config = new Atm90E36Config();
		config.setPhaseCurrentPgaGain(PgaGain.X4);

		then(config.getCurrentGainI4()).as("I4 untouched").isEqualTo(PgaGain.X1);
	}

	@Test
	public void eachPgaChannelUsesItsOwnField() {
		Atm90E36Config config = new Atm90E36Config();

		config.setCurrentGainI1(PgaGain.X2);
		then(config.pgaGainMode()).as("I1 at [1:0]").isEqualTo(0b01);

		config.setCurrentGainI1(PgaGain.X1);
		config.setCurrentGainI2(PgaGain.X4);
		then(config.pgaGainMode()).as("I2 at [3:2]").isEqualTo(0b10 << 2);

		config.setCurrentGainI2(PgaGain.X1);
		config.setCurrentGainI3(PgaGain.X2);
		then(config.pgaGainMode()).as("I3 at [5:4]").isEqualTo(0b01 << 4);

		config.setCurrentGainI3(PgaGain.X1);
		config.setCurrentGainI4(PgaGain.X4);
		then(config.pgaGainMode()).as("I4 at [7:6]").isEqualTo(0b10 << 6);

		config.setCurrentGainI4(PgaGain.X1);
		config.setVoltageGainV1(PgaGain.X2);
		then(config.pgaGainMode()).as("V1 at [9:8]").isEqualTo(0b01 << 8);

		config.setVoltageGainV1(PgaGain.X1);
		config.setVoltageGainV2(PgaGain.X2);
		then(config.pgaGainMode()).as("V2 at [11:10]").isEqualTo(0b01 << 10);

		config.setVoltageGainV2(PgaGain.X1);
		config.setVoltageGainV3(PgaGain.X4);
		then(config.pgaGainMode()).as("V3 at [13:12]").isEqualTo(0b10 << 12);

		config.setVoltageGainV3(PgaGain.X1);
		config.setDigitalPgaGain(DigitalPgaGain.X8);
		then(config.pgaGainMode()).as("DPGA_GAIN at [15:14]").isEqualTo(0b11 << 14);
	}

	@Test
	public void meteringModeRoundTripsThroughItsFields() {
		Atm90E36Config config = new Atm90E36Config();

		// 0x3FDF is every non-reserved bit set
		for ( int word : new int[] { 0x0000, 0x0087, 0x0185, 0x1185, 0x1087, 0x01D4, 0x3FDF } ) {
			config.setMeteringMode(word);
			then(config.meteringMode()).as("MMode0 0x%04X survives a round trip", word).isEqualTo(word);
		}
	}

	@Test
	public void pgaGainModeRoundTripsThroughItsFields() {
		Atm90E36Config config = new Atm90E36Config();

		for ( int word : new int[] { 0x0000, 21, 42, 0x1249, 0x8000, 0xA2A2 } ) {
			config.setPgaGainMode(word);
			then(config.pgaGainMode()).as("MMode1 0x%04X survives a round trip", word).isEqualTo(word);
		}
	}

	@Test
	public void setMeteringModeDecodesEachField() {
		// GIVEN 0x146F: 60Hz, Rogowski, 3P4W, vector-sum apparent, absolute P
		Atm90E36Config config = new Atm90E36Config();

		// WHEN
		config.setMeteringMode(0x146F);

		// THEN
		then(config.getLineFrequency()).as("Freq60Hz").isEqualTo(LineFrequency.HZ_60);
		then(config.getCurrentSensor()).as("didtEn").isEqualTo(CurrentSensor.ROGOWSKI_COIL);
		then(config.getWiring()).as("3P3W clear").isEqualTo(Wiring.THREE_PHASE_FOUR_WIRE);
		then(config.isApparentEnergyVectorSum()).as("CF2ESV").isTrue();
		then(config.isCf2ReactiveEnergy()).as("CF2varh clear").isFalse();
		then(config.getActivePowerSum()).as("ABSEnP").isEqualTo(SumMethod.ABSOLUTE);
		then(config.getReactivePowerSum()).as("ABSEnQ clear").isEqualTo(SumMethod.ARITHMETIC);
		then(config.getSummedPhases()).as("all three phases counted").containsExactly(Phase.A, Phase.B,
				Phase.C);
		then(config.isHighPassFilterEnabled()).as("HPFOff clear means enabled").isTrue();
	}

	@Test
	public void setPgaGainModeDecodesEachChannel() {
		Atm90E36Config config = new Atm90E36Config();

		config.setPgaGainMode(21);

		then(config.getCurrentGainI1()).as("I1").isEqualTo(PgaGain.X2);
		then(config.getCurrentGainI2()).as("I2").isEqualTo(PgaGain.X2);
		then(config.getCurrentGainI3()).as("I3").isEqualTo(PgaGain.X2);
		then(config.getCurrentGainI4()).as("I4").isEqualTo(PgaGain.X1);
		then(config.getDigitalPgaGain()).as("DPGA").isEqualTo(DigitalPgaGain.X1);
	}

	@Test
	public void setMeteringModeDropsTheReservedBits() {
		Atm90E36Config config = new Atm90E36Config();

		// both of the ported values set reserved bit 5, which no property holds
		config.setMeteringMode(500);
		then(config.meteringMode()).as("500 loses reserved bit 5").isEqualTo(0x01D4);

		config.setMeteringMode(5231);
		then(config.meteringMode()).as("5231 loses reserved bit 5").isEqualTo(0x144F);
	}

	@Test
	public void setPgaGainModeRejectsTheReservedEncoding() {
		Atm90E36Config config = new Atm90E36Config();

		thenThrownBy(() -> config.setPgaGainMode(0x03)).as("11 is documented N/A")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void summedPhasesSetterCopiesRatherThanAliases() {
		// GIVEN
		Atm90E36Config config = new Atm90E36Config();
		EnumSet<Phase> phases = EnumSet.of(Phase.A);

		// WHEN
		config.setSummedPhases(phases);
		phases.add(Phase.B);

		// THEN
		then(config.getSummedPhases()).as("the caller's set was copied").containsExactly(Phase.A);
	}

}
