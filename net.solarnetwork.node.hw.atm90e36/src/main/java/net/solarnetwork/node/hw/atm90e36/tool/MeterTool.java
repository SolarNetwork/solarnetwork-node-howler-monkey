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

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.solarnetwork.node.hw.atm90e36.Atm90E36;
import net.solarnetwork.node.hw.atm90e36.spi.LinuxSpiDevice;

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
	private static final int LINE_FREQ = 500;
	private static final int PGA_GAIN = 21;
	private static final int VOLTAGE_GAIN = 50000;
	private static final int CURRENT_GAIN = 32498;

	private static final String BAR = "=".repeat(60);

	static final String CSV_HEADER = String.join(",", "Timestamp", "Voltage_A", "Voltage_B",
			"Voltage_C", "Current_A", "Current_B", "Current_C", "Power_A", "Power_B", "Power_C",
			"Power_Total", "PF_Total", "Frequency");

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
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
				StandardCharsets.UTF_8));

		String mode = "csv";
		for ( int i = 0; i < args.length; i++ ) {
			String a = args[i];
			if ( "--mode".equals(a) && i + 1 < args.length ) {
				mode = args[++i];
			} else if ( a.startsWith("--mode=") ) {
				mode = a.substring("--mode=".length());
			} else if ( "-h".equals(a) || "--help".equals(a) ) {
				printUsage(System.out);
				return;
			} else {
				System.err.println("Unknown argument: " + a);
				printUsage(System.err);
				System.exit(2);
			}
		}

		switch ( mode ) {
			case "calibrate":
				runCalibrationMode();
				break;

			case "csv":
				runCsvMode();
				break;

			default:
				System.err.println("Invalid mode: " + mode + " (choose 'csv' or 'calibrate')");
				printUsage(System.err);
				System.exit(2);
		}
	}

	private static void printUsage(PrintStream out) {
		out.println("usage: meter-tool [--mode {calibrate,csv}]");
		out.println();
		out.println("ATM90E36 Energy Monitor");
		out.println();
		out.println("  --mode {calibrate,csv}  Working mode (default: csv)");
	}

	// ========================================================================
	// MODE: CALIBRATION
	// ========================================================================

	/** Run the interactive calibration menu. */
	public static void runCalibrationMode() {
		PrintStream out = System.out;
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

		out.println(BAR);
		out.println("ATM90E36 Calibration Mode");
		out.println(BAR);
		out.println();

		Atm90E36 eic = new Atm90E36(new LinuxSpiDevice(SPI_BUS, SPI_DEVICE), LINE_FREQ, PGA_GAIN,
				VOLTAGE_GAIN, CURRENT_GAIN, CURRENT_GAIN, CURRENT_GAIN);

		try {
			out.println("Device initializing...");
			sleep(2000);
			eic.begin();
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
					out.println(String.format(Locale.ROOT, "%n⚡ Voltage: A=%.2fV  B=%.2fV  C=%.2fV",
							va, vb, vc));

					double ia = eic.getLineCurrentA();
					double ib = eic.getLineCurrentB();
					double ic = eic.getLineCurrentC();
					out.println(String.format(Locale.ROOT, "🔌 Current: A=%.3fA  B=%.3fA  C=%.3fA",
							ia, ib, ic));

					double pa = eic.getActivePowerA();
					double pt = eic.getTotalActivePower();
					out.println(String.format(Locale.ROOT, "💡 Power: A=%.2fW  Total=%.2fW", pa,
							pt));

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

						out.println(String.format(Locale.ROOT, "%n✅ Recommended voltage gain: %d",
								newGain));
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
					out.println("Line Frequency: " + eic.getLineFreq());
					out.println("PGA Gain: " + eic.getPgaGain());
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
			eic.close();
			out.println("Connection closed.");
		}
	}

	// ========================================================================
	// MODE: CSV LOGGER
	// ========================================================================

	/** Stream measurements to standard output as CSV, one row every 5 seconds. */
	public static void runCsvMode() {
		final PrintStream out = System.out;
		final Atm90E36 eic = new Atm90E36(new LinuxSpiDevice(SPI_BUS, SPI_DEVICE), LINE_FREQ, PGA_GAIN,
				VOLTAGE_GAIN, CURRENT_GAIN, CURRENT_GAIN, CURRENT_GAIN);
		final AtomicBoolean running = new AtomicBoolean(true);

		Thread shutdown = new Thread(() -> {
			running.set(false);
			eic.close();
		}, "meter-tool-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdown);

		try {
			eic.begin();
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
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		} catch ( Exception e ) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
		} finally {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdown);
			} catch ( IllegalStateException alreadyShuttingDown ) {
				// ignore
			}
			eic.close();
		}
	}

	/**
	 * Build one CSV data row, matching the column order and numeric precision of
	 * {@code meter-tool-2.py}.
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
