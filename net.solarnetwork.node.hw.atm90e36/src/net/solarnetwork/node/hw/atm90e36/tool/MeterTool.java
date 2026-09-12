/* ==================================================================
 * MeterTool.java - 10/09/2026
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

import static net.solarnetwork.node.hw.linux.spi.SpiDeviceFactory.spiDeviceFor;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.solarnetwork.node.hw.atm90e36.Atm90E36;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.CurrentSensor;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.PgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.SumMethod;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Wiring;

/**
 * Command-line tool for reading and calibrating the ATM90E36 chip, a Java port
 * of {@code meter-tool-2.py} retaining the same CLI input and output.
 *
 * <pre>
 *   meter-tool [--mode csv|calibrate]
 * </pre>
 *
 * <p>
 * With no arguments (or {@code --mode csv}) it prints a CSV stream of
 * measurements to standard output, one row every 5 seconds, until interrupted.
 * With {@code --mode calibrate} it presents the interactive calibration menu.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class MeterTool {

	private static final int SPI_BUS = 0;
	private static final int SPI_DEVICE = 0;
	private static final int VOLTAGE_GAIN = 50000;
	private static final int CURRENT_GAIN = 32498;

	private static final String BAR = "=".repeat(60);

	static final String CSV_HEADER = String.join(",", "Timestamp", "Voltage_A", "Voltage_B", "Voltage_C",
			"Current_A", "Current_B", "Current_C", "Power_A", "Power_B", "Power_C", "Power_Total",
			"PF_Total", "Frequency");

	private MeterTool() {
		// not instantiable
	}

	/**
	 * Program entry point.
	 *
	 * @param args
	 *        the command-line arguments
	 */
	public static void main(String[] args) {
		// force UTF-8 stdout so the calibration menu's symbols render regardless
		// of the platform default charset
		System.setOut(
				new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

		Map<String, String> options;
		try {
			options = parseArgs(args);
		} catch ( IllegalArgumentException e ) {
			System.err.println(e.getMessage());
			printUsage(System.err);
			System.exit(2);
			return;
		}
		if ( options.containsKey("help") ) {
			printUsage(System.out);
			return;
		}

		final Atm90E36Config config;
		try {
			config = meterConfig(options);
		} catch ( IllegalArgumentException e ) {
			System.err.println(e.getMessage());
			printUsage(System.err);
			System.exit(2);
			return;
		}

		String mode = options.getOrDefault("mode", "csv");
		switch (mode) {
			case "calibrate":
				runCalibrationMode(config);
				break;

			case "csv":
				runCsvMode(config);
				break;

			default:
				System.err.println("Invalid mode: %s (choose 'csv' or 'calibrate')".formatted(mode));
				printUsage(System.err);
				System.exit(2);
		}
	}

	/** The options {@link #parseArgs(String[])} accepts a value for. */
	private static final Set<String> VALUED_OPTIONS = Set.of("mode", "line-frequency", "wiring",
			"phases", "current-sensor", "pga-gain", "meter-constant", "voltage-gain", "current-gain");

	/**
	 * Parse {@code --name value} and {@code --name=value} options.
	 *
	 * @param args
	 *        the raw command-line arguments
	 * @return the parsed options
	 * @throws IllegalArgumentException
	 *         if an argument is not recognised, or a valued option has no value
	 */
	static Map<String, String> parseArgs(String[] args) {
		Map<String, String> options = new LinkedHashMap<>();
		for ( int i = 0; i < args.length; i++ ) {
			String a = args[i];
			if ( "-h".equals(a) || "--help".equals(a) ) {
				options.put("help", "");
				continue;
			}
			if ( !a.startsWith("--") ) {
				throw new IllegalArgumentException("Unknown argument: " + a);
			}
			String name = a.substring(2);
			String value = null;
			int eq = name.indexOf('=');
			if ( eq >= 0 ) {
				value = name.substring(eq + 1);
				name = name.substring(0, eq);
			}
			if ( !VALUED_OPTIONS.contains(name) ) {
				throw new IllegalArgumentException("Unknown argument: " + a);
			}
			if ( value == null ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException("Missing value for --" + name);
				}
				value = args[++i];
			}
			options.put(name, value);
		}
		return options;
	}

	/**
	 * Create the meter configuration this tool runs with.
	 *
	 * <p>
	 * Every value here depends on how the chip is wired to the service being
	 * metered, which varies per deployment, so each is overridable from the
	 * command line. The defaults reproduce the {@code MMode0} word
	 * {@code meter-tool-2.py} used ({@code 500}), less its bit 5, which the
	 * datasheet reserves.
	 * </p>
	 *
	 * <p>
	 * Note in particular {@code --phases}. The chip totals its registers as
	 * {@code PT = PA*EnPA + PB*EnPB + PC*EnPC}, so only the phases named here
	 * reach {@code PmeanT} and the total energy registers. A three-wire service
	 * is measured by two elements, A and C, which is what the application
	 * note's recommended 3P3W configuration selects; the {@code a} default
	 * carried over from the Python tool totals one element only, and on a
	 * two-element service under-reports by between half and all of the load,
	 * depending on power factor.
	 * </p>
	 *
	 * @param args
	 *        the parsed command-line options
	 * @return a new configuration
	 */
	static Atm90E36Config meterConfig(Map<String, String> args) {
		Atm90E36Config config = new Atm90E36Config();
		config.setLineFrequency(
				"60".equals(args.getOrDefault("line-frequency", "50")) ? LineFrequency.HZ_60
						: LineFrequency.HZ_50);
		config.setWiring(
				"3p4w".equals(args.getOrDefault("wiring", "3p3w")) ? Wiring.THREE_PHASE_FOUR_WIRE
						: Wiring.THREE_PHASE_THREE_WIRE);
		config.setCurrentSensor("rogowski".equals(args.getOrDefault("current-sensor", "ct"))
				? CurrentSensor.ROGOWSKI_COIL
				: CurrentSensor.CURRENT_TRANSFORMER);
		config.setSummedPhases(parsePhases(args.getOrDefault("phases", "a")));
		config.setPhaseCurrentPgaGain(parsePgaGain(args.getOrDefault("pga-gain", "2")));
		config.setMeterConstant(parseInt(args, "meter-constant", Atm90E36Config.DEFAULT_METER_CONSTANT));
		config.setVoltageGain(parseInt(args, "voltage-gain", VOLTAGE_GAIN));
		config.setCurrentGain(parseInt(args, "current-gain", CURRENT_GAIN));

		// carried over from meter-tool-2.py's MMode0 value of 500
		config.setApparentEnergyVectorSum(true);
		config.setReactivePowerSum(SumMethod.ABSOLUTE);
		return config;
	}

	/** Parse a phase list such as {@code ac} into a set. */
	static Set<Phase> parsePhases(String value) {
		Set<Phase> phases = EnumSet.noneOf(Phase.class);
		for ( char c : value.toLowerCase(Locale.ROOT).toCharArray() ) {
			switch (c) {
				case 'a' -> phases.add(Phase.A);
				case 'b' -> phases.add(Phase.B);
				case 'c' -> phases.add(Phase.C);
				case ',', ' ' -> {
					// separators are allowed, so "a,c" works too
				}
				default -> throw new IllegalArgumentException(
						"Invalid phase '%c' in --phases %s (expected a, b and/or c)".formatted(c,
								value));
			}
		}
		return phases;
	}

	/** Parse an analog PGA gain value. */
	static PgaGain parsePgaGain(String value) {
		return switch (value) {
			case "1" -> PgaGain.X1;
			case "2" -> PgaGain.X2;
			case "4" -> PgaGain.X4;
			default -> throw new IllegalArgumentException(
					"Invalid --pga-gain %s (expected 1, 2 or 4)".formatted(value));
		};
	}

	private static int parseInt(Map<String, String> args, String name, int defaultValue) {
		String value = args.get(name);
		if ( value == null ) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value);
		} catch ( NumberFormatException e ) {
			throw new IllegalArgumentException(
					"Invalid --%s %s (expected a number)".formatted(name, value));
		}
	}

	private static void printUsage(PrintStream out) {
		out.print("""
				usage: meter-tool [--mode {calibrate,csv}] [options]

				ATM90E36 Energy Monitor

				  --mode {calibrate,csv}       Working mode (default: csv)

				Metering configuration; these depend on how the chip is wired to the
				service being metered, so they vary per deployment:

				  --line-frequency {50,60}     Grid frequency, Hz (default: 50)
				  --wiring {3p4w,3p3w}         Connection type (default: 3p3w)
				  --phases <abc>               Phases counted into the all-phase totals
				                               a four-wire service uses 'abc'.
				  --current-sensor {ct,rogowski}
				                               Current sampling (default: ct)
				  --pga-gain {1,2,4}           Analog gain on the phase current channels
				                               (default: 2)
				  --meter-constant <imp/kWh>   Meter constant (default: %d)
				  --voltage-gain <n>           Voltage RMS gain (default: %d)
				  --current-gain <n>           Current RMS gain (default: %d)
				""".formatted(Atm90E36Config.DEFAULT_METER_CONSTANT, VOLTAGE_GAIN, CURRENT_GAIN));
	}

	// ========================================================================
	// MODE: CALIBRATION
	// ========================================================================

	/**
	 * Run the interactive calibration menu.
	 *
	 * @param config
	 *        the configuration to apply to the chip
	 */
	public static void runCalibrationMode(Atm90E36Config config) {
		PrintStream out = System.out;
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

		out.println(BAR);
		out.println("ATM90E36 Calibration Mode");
		out.println(BAR);
		out.println();

		try (Atm90E36 eic = new Atm90E36(spiDeviceFor(SPI_BUS, SPI_DEVICE), config)) {
			out.println("Device initializing...");
			sleep(2000);
			eic.open();
			eic.configure(config);
			sleep(1000);

			while ( true ) {
				out.println();
				out.println(BAR);
				out.println("CALIBRATION MENU");
				out.println(BAR);
				out.println("1. Show current measurements");
				out.println("2. Voltage calibration");
				out.println("3. Current calibration (Phase A)");
				out.println("4. Show calibration values");
				out.println("0. Exit");
				out.println();

				out.print("Your choice (0-4): ");
				out.flush();
				String choice = in.readLine();
				if ( choice == null ) {
					break;
				}
				choice = choice.trim();

				if ( "0".equals(choice) ) {
					break;
				} else if ( "1".equals(choice) ) {
					out.println();
					out.println(BAR);
					out.println("CURRENT MEASUREMENTS");
					out.println(BAR);

					double va = eic.getLineVoltageA();
					double vb = eic.getLineVoltageB();
					double vc = eic.getLineVoltageC();
					out.println(String.format(Locale.ROOT, "%n⚡ Voltage: A=%.2fV  B=%.2fV  C=%.2fV", va,
							vb, vc));

					double ia = eic.getLineCurrentA();
					double ib = eic.getLineCurrentB();
					double ic = eic.getLineCurrentC();
					out.println(String.format(Locale.ROOT, "🔌 Current: A=%.3fA  B=%.3fA  C=%.3fA", ia,
							ib, ic));

					double pa = eic.getActivePowerA();
					double pt = eic.getTotalActivePower();
					out.println(String.format(Locale.ROOT, "💡 Power: A=%.2fW  Total=%.2fW", pa, pt));

					double freq = eic.getFrequency();
					out.println(String.format(Locale.ROOT, "🌊 Frequency: %.2f Hz", freq));

				} else if ( "2".equals(choice) ) {
					out.println();
					out.println(BAR);
					out.println("VOLTAGE CALIBRATION");
					out.println(BAR);

					double measuredVa = eic.getLineVoltageA();
					out.println(String.format(Locale.ROOT, "%nATM90E36 reading: %.2f V", measuredVa));

					out.print("Voltage measured with multimeter (V): ");
					out.flush();
					String line = in.readLine();
					try {
						double actualVa = Double.parseDouble(line == null ? "" : line.trim());
						int currentGain = eic.getVoltageGain();
						int newGain = (int) ((actualVa / measuredVa) * currentGain);

						out.println(
								String.format(Locale.ROOT, "%n✅ Recommended voltage gain: %d", newGain));
						out.println("Use in code: voltage_gain=" + newGain);
					} catch ( NumberFormatException e ) {
						out.println("❌ Invalid input!");
					}

				} else if ( "3".equals(choice) ) {
					out.println();
					out.println(BAR);
					out.println("CURRENT CALIBRATION - PHASE A");
					out.println(BAR);

					double measuredIa = eic.getLineCurrentA();
					out.println(String.format(Locale.ROOT, "%nATM90E36 reading: %.3f A", measuredIa));

					if ( measuredIa < 0.1 ) {
						out.println("⚠️  Current is too low! Connect a higher load.");
					} else {
						out.print("Current measured by ammeter (A): ");
						out.flush();
						String line = in.readLine();
						try {
							double actualIa = Double.parseDouble(line == null ? "" : line.trim());
							int currentGain = eic.getCurrentGainA();
							int newGain = (int) ((actualIa / measuredIa) * currentGain);

							out.println(String.format(Locale.ROOT, "%n✅ Recommended current gain: %d",
									newGain));
							out.println("Use in code: current_gain_a=" + newGain);
						} catch ( NumberFormatException e ) {
							out.println("❌ Invalid input!");
						}
					}

				} else if ( "4".equals(choice) ) {
					out.println();
					out.println(BAR);
					out.println("CURRENT CALIBRATION VALUES");
					out.println(BAR);
					out.println("Voltage Gain: " + eic.getVoltageGain());
					out.println("Current Gain A: " + eic.getCurrentGainA());
					out.println("Current Gain B: " + eic.getCurrentGainB());
					out.println("Current Gain C: " + eic.getCurrentGainC());
					out.printf("Metering Mode: 0x%04X%n", eic.getMeteringMode());
					out.printf("PGA Gain Mode: 0x%04X%n", eic.getPgaGainMode());
				} else {
					out.println("❌ Invalid selection!");
				}

				out.print(System.lineSeparator() + "Press Enter to continue...");
				out.flush();
				in.readLine();
			}
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			out.println();
			out.println();
			out.println("Cancelled.");
		} catch ( Exception e ) {
			out.println();
			out.println("❌ Error: " + e.getMessage());
			e.printStackTrace();
		} finally {
			out.println("Connection closed.");
		}
	}

	// ========================================================================
	// MODE: CSV LOGGER
	// ========================================================================

	/**
	 * Stream measurements to standard output as CSV, one row every 5 seconds.
	 *
	 * @param config
	 *        the configuration to apply to the chip if it needs configuring
	 */
	// the shutdown hook deliberately closes the try-with-resources 'eic'
	@SuppressWarnings("try")
	public static void runCsvMode(Atm90E36Config config) {
		final PrintStream out = System.out;
		final AtomicBoolean running = new AtomicBoolean(true);

		try (Atm90E36 eic = new Atm90E36(spiDeviceFor(SPI_BUS, SPI_DEVICE), config)) {

			// On SIGINT the JVM halts without unwinding the stack, so the
			// try-with-resources close above would not run; close from a
			// shutdown hook too (Atm90E36.close() is idempotent).
			Thread shutdown = new Thread(() -> {
				running.set(false);
				eic.close();
			}, "meter-tool-shutdown");
			Runtime.getRuntime().addShutdownHook(shutdown);

			try {
				eic.open();
				if ( eic.configureIfNeeded(config) ) {
					out.println("Chip was unconfigured; configuration applied.");
				} else {
					out.println("Chip already configured; leaving it and its energy registers alone.");
				}
				sleep(2000);

				out.println(CSV_HEADER);
				out.flush();

				while ( running.get() ) {
					out.println(csvRow(eic, Instant.now()));
					out.flush();
					if ( out.checkError() ) {
						// stdout closed (e.g. piped into `head`): stop quietly
						return;
					}

					sleep(5000);
				}
			} finally {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdown);
				} catch ( IllegalStateException alreadyShuttingDown ) {
					// ignore
				}
			}
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		} catch ( Exception e ) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Build one CSV data row, matching the column order and numeric precision
	 * of {@code meter-tool-2.py}.
	 *
	 * <p>
	 * All 13 measurement columns come from a single batched SPI read
	 * ({@link Atm90E36#readMeasurements()}).
	 * </p>
	 *
	 * @param eic
	 *        the meter to read
	 * @param timestamp
	 *        the row timestamp (rendered as an ISO-8601 instant, e.g.
	 *        {@code 2026-09-10T12:34:56.789Z})
	 * @return the CSV row (no trailing newline)
	 */
	static String csvRow(Atm90E36 eic, Instant timestamp) {
		Atm90E36.Measurements m = eic.readMeasurements();
		return String.join(",", DateTimeFormatter.ISO_INSTANT.format(timestamp), fmt2(m.voltageA()),
				fmt2(m.voltageB()), fmt2(m.voltageC()), fmt3(m.currentA()), fmt3(m.currentB()),
				fmt3(m.currentC()), fmt2(m.powerA()), fmt2(m.powerB()), fmt2(m.powerC()),
				fmt2(m.powerTotal()), fmt3(m.powerFactorTotal()), fmt2(m.frequency()));
	}

	private static String fmt2(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static String fmt3(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static void sleep(long millis) throws InterruptedException {
		Thread.sleep(millis);
	}

}
