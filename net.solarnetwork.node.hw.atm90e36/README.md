# ATM90E36 energy meter support (`net.solarnetwork.node.hw.atm90e36`)

A SolarNode OSGi bundle for reading and configuring the Atmel/Microchip ATM90E36 poly-phase
energy metering IC over SPI. The
[`net.solarnetwork.node.datum.atm90e36`](../net.solarnetwork.node.datum.atm90e36) plugin builds on
it to capture datum.

## SPI protocol

The chip is accessed in SPI mode 3 (CPOL=1, CPHA=1), 8 bits per word, at 200 kHz (the chip
allows up to 1.2 MHz). Per datasheet §4.2.1 each transaction is 32 clocks, all MSB first: the
access type bit (1 = read, 0 = write), a 15-bit register address of which the chip decodes only
the lower 10 bits, then the 16-bit register value. For a read the chip drives the value on `SDO`
during the last 16 clocks.

Each transaction is framed by chip-select and accesses exactly one register (*"The SPI
read/write transaction is CS-low defined. Each transaction can only access one register."*).

## Layout

| Package | Visibility | Contents |
|---|---|---|
| `net.solarnetwork.node.hw.atm90e36` | exported | [`Atm90E36`](src/net/solarnetwork/node/hw/atm90e36/Atm90E36.java), the register-level driver; [`Atm90E36Config`](src/net/solarnetwork/node/hw/atm90e36/Atm90E36Config.java), its metering configuration; and [`Atm90E36Register`](src/net/solarnetwork/node/hw/atm90e36/Atm90E36Register.java), the register enumeration |
| `net.solarnetwork.node.hw.atm90e36.tool` | bundle-private | [`MeterTool`](src/net/solarnetwork/node/hw/atm90e36/tool/MeterTool.java), a command-line tool for reading and calibrating the chip |

The SPI transport comes from the `net.solarnetwork.node.hw.linux.spi` bundle: the `SpiDevice`
interface, `SpiDeviceFactory.spiDeviceFor(bus, chipSelect)`, and the JNA (Java Native Access)
`JnaSpiDevice` implementation, which issues `ioctl(2)` calls against `/dev/spidevX.Y`. The
driver depends only on the `SpiDevice` interface, so the tests run it against an in-memory
[`FakeSpiDevice`](../net.solarnetwork.node.hw.atm90e36.test/src/net/solarnetwork/node/hw/atm90e36/test/FakeSpiDevice.java).

`Atm90E36` is `AutoCloseable`; `close()` is idempotent and safe to call from another thread.
The driver registers no shutdown hooks, leaving that to the application: `MeterTool` uses
try-with-resources, plus a shutdown hook in CSV mode, since a SIGINT halts the JVM without
unwinding the stack.

## Configuration

The chip keeps its configuration in volatile registers, so a configuration survives a restart
of SolarNode but not a power cycle of the chip. `Atm90E36` separates attaching to the chip from
configuring it:

- `open()` opens the SPI device without writing to the chip.
- `isConfigured()` reports whether the chip has been configured since it powered up (its
  `ConfigStart` register no longer holds the power-on `6886H`).
- `configure(Atm90E36Config)` writes the configuration, calibration, harmonic and
  measurement-adjustment register blocks. **It issues a software reset first, which clears the
  accumulated energy registers.**
- `configureIfNeeded(Atm90E36Config)` configures only when the chip is unconfigured or holds
  different settings, per `matchesConfiguration(Atm90E36Config)`.
- `refreshConfig()` reads the configuration back from the chip.

A default `Atm90E36Config` matches the chip's power-on values: `MMode0` `0087H` (3-phase
4-wire, 50 Hz, current transformers, all three phases counted into the all-phase totals) and
`MMode1` `0000H` (1X PGA gain). The chip totals power and energy as
`PT = PA*EnPA + PB*EnPB + PC*EnPC`, so `Atm90E36Config.setSummedPhases()` should name the phases
that are actually connected.

## Measurements

| Register | Scaling |
|---|---|
| `Urms[A-C]` | 1 LSB = 0.01 V |
| `Irms[A-C]` | 1 LSB = 0.001 A |
| `Pmean`, `Qmean`, `Smean` | two's complement `MSB` register, 1 LSB = 1 W (var, VA) per phase and 4 W for the total; only the upper 8 bits of the matching `LSB` register are valid, each worth 1/256 of that |
| `PFmean` | signed, 1 LSB = 0.001 |
| `Freq` | 1 LSB = 0.01 Hz |

The energy registers are read-to-clear, so `readEnergy()` returns the energy accumulated since
the previous read (or since `configure()`); callers accumulate it themselves.

## MeterTool

`MeterTool` is a command-line tool with three modes:

- **CSV** (the default) prints a row of voltage, current, power, power factor and frequency
  every 5 seconds. It only reads from the chip, and warns on standard error if the chip is not
  configured.
- **Calibrate** (`--mode calibrate`) configures the chip, then presents an interactive menu for
  viewing measurements and working out voltage and current gains. By default it applies a
  3-phase 4-wire, 50 Hz configuration with all three phases summed (`MMode0` `0087H`) and 2X
  PGA gain on the phase current channels; options override each setting (see `--help`).
- **Read** (`--read [REG ...]`) prints raw register values and exits, without configuring the
  chip. Registers are given by name (as in `Atm90E36Register`, ignoring case), hex or decimal;
  with none it prints those useful for diagnosing power readings:

  ```
  0x0033 (MMode0)   : 0x0087
  0x0035 (PStartTh) : 0x1D4C
  0x00B0 (PmeanT)   : 0xFFF3
  0x00C0 (PmeanTLSB): 0x1400
  ...
  ```

## Batched register reads

Each CSV row needs 16 register reads. Rather than 16 separate `ioctl` calls,
`Atm90E36.readMeasurements()` builds the 16 read frames once and submits them through
`SpiDevice.batch(byte[][] txFrames, int settleMicros)`, which `JnaSpiDevice` issues as **one**
`SPI_IOC_MESSAGE(16)` `ioctl`. `readEnergy()` batches the energy registers the same way.

Because the chip accesses one register per chip-select cycle, the message is 16 discrete
transfers with `cs_change = 1` on all but the last, so the kernel toggles chip-select between
them, and a 10 µs settle delay after each. The datasheet only requires a minimum chip-select
high time (`tCSH`) of `2T + 10 ns`, so the delay is conservative headroom.

The returned `Batch` is reusable: its `transfer()` re-runs the same frames without
re-allocating native buffers. `Atm90E36` creates its measurement and energy batches on first
use and releases them in `close()`, so a polling loop allocates little more than the returned
records. If a particular SPI controller or device-tree chip-select configuration mishandles
`cs_change` within a message, `JnaSpiDevice.setMultiTransfer(false)` falls back to one `ioctl`
per frame, with the same results. **The batched path has not yet been validated on hardware**;
the unit tests exercise it only through `FakeSpiDevice`.

## Nullability

Every package is `@org.jspecify.annotations.NullMarked` (via `package-info.java`), matching
recent SolarNode bundles, so fields, parameters and return values are non-null unless annotated
`@Nullable`, as for the lazily created SPI batches and the `Atm90E36Register.forAddress()` /
`forName()` lookups.

## Deployment

The bundle imports:

- `net.solarnetwork.node.hw.linux.spi`, from the `net.solarnetwork.node.hw.linux-spi` bundle,
  which in turn imports JNA (`com.sun.jna`)
- `net.solarnetwork.domain` and `net.solarnetwork.util`, from `net.solarnetwork.common`
- `org.jspecify.annotations` and `org.slf4j`

On the device:

- The user running SolarNode must be able to open `/dev/spidev0.0`; see
  [OS setup](../README.md#os-setup) for enabling SPI and adding the user to the `spi` group.
- JNA unpacks its native `libjnidispatch.so` to a temporary directory and loads it from there.
  If `/tmp` is mounted `noexec`, point JNA elsewhere with `-Djna.tmpdir=/var/tmp/jna` (writable,
  exec), or install the OS `libjna-java` / `libjnidispatch-java` package and set
  `-Djna.nosys=false`.

## Tests

The tests live in the
[`net.solarnetwork.node.hw.atm90e36.test`](../net.solarnetwork.node.hw.atm90e36.test) fragment and
run host-side, with no hardware. `FakeSpiDevice` emulates the chip's SPI framing, read-to-clear
energy registers and software reset, so the tests cover the MSB-first register framing, the
measurement scaling (including register values captured from a real meter), the configuration
and checksum sequence, `configureIfNeeded()`, the batched measurement and energy reads, and
`MeterTool`'s option parsing, CSV rows and register output. The `ioctl` transfer paths
themselves can only be exercised on a real device.

## License

GPL-2.0-or-later, matching the [`SolarNetwork/solarnetwork-node`](https://github.com/SolarNetwork/solarnetwork-node)
project and this repository's [LICENSE](../LICENSE). Every source file carries the standard
SolarNetwork GPLv2 header.
