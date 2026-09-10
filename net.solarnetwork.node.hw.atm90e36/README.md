# ATM90E36 energy meter support (`net.solarnetwork.node.hw.atm90e36`)

A Java 17 / OSGi port of [`scripts/meter-tool-2.py`](../scripts/meter-tool-2.py). It reads and
calibrates the ATM90E36 energy metering IC over SPI, and ships the same `meter-tool` CLI
(`--mode csv` / `--mode calibrate`) with byte-for-byte compatible input and output.

The Linux SPI transport is implemented with **JNA** (Java Native Access): `ioctl(2)` calls
against `/dev/spidevX.Y`, transcribed from `linux/spi/spidev.h`. There is no JNI stub to
compile — JNA itself is a pure-Java OSGi bundle — which is the Java analogue of swapping the
Python `spidev` package for `spidev2`.

## Layout

| Package | Visibility | Contents |
|---|---|---|
| `net.solarnetwork.node.hw.atm90e36` | exported | [`SpiDevice`](src/main/java/net/solarnetwork/node/hw/atm90e36/SpiDevice.java) abstraction, [`SpiException`](src/main/java/net/solarnetwork/node/hw/atm90e36/SpiException.java), and the [`Atm90E36`](src/main/java/net/solarnetwork/node/hw/atm90e36/Atm90E36.java) register-level driver (a direct port of the Python `ATM90E36` class) |
| `net.solarnetwork.node.hw.atm90e36.spi` | bundle-private | [`LinuxSpiDevice`](src/main/java/net/solarnetwork/node/hw/atm90e36/spi/LinuxSpiDevice.java) — the JNA `spidev` implementation — and [`Activator`](src/main/java/net/solarnetwork/node/hw/atm90e36/spi/Activator.java), the bundle activator that unbinds it on stop |
| `net.solarnetwork.node.hw.atm90e36.tool` | bundle-private | [`MeterTool`](src/main/java/net/solarnetwork/node/hw/atm90e36/tool/MeterTool.java) — the CLI (`main`, plus `runCsvMode()` / `runCalibrationMode()`) |

The driver depends only on the `SpiDevice` interface, so it can be reused with a mock transport
(see [`FakeSpiDevice`](src/test/java/net/solarnetwork/node/hw/atm90e36/test/FakeSpiDevice.java))
or a different SPI stack later.

`Atm90E36` (and `SpiDevice`) are `AutoCloseable`; `close()` is idempotent and safe to call from
another thread. `MeterTool` drives one per mode as a try-with-resources resource, plus — in CSV
mode — a shutdown hook, since a SIGINT halts the JVM without unwinding the stack. The driver
itself registers no hooks; that is left to the application (a driver headed for an OSGi bundle
should not touch `Runtime.addShutdownHook`).

## Build

```sh
./gradlew build
```

Produces:

- `build/libs/net.solarnetwork.node.hw.atm90e36-1.0.0.jar` — an OSGi bundle **and** an
  executable jar (`Main-Class` set).
- `build/distributions/meter-tool-1.0.0.{zip,tar}` — a standalone distribution with a launch
  script and the JNA jar bundled (via the `application` plugin).

Java 17 is required (`toolchain { languageVersion = 17 }`); the build was verified with Gradle
9.7.1 and the `biz.aQute.bnd.builder` 7.4.0 plugin.

### Nullability

Every package is `@org.jspecify.annotations.NullMarked` (via `package-info.java`), matching
recent SolarNode bundles such as `net.solarnetwork.node`. All fields and parameters are
therefore non-null by contract — constructor-injected `SpiDevice` / device path, primitive
measurement returns — so no `@Nullable` is used anywhere. `jspecify` is a `compileOnly`
dependency; the annotations have no runtime effect, and the JVM ignores them when the jar is
absent (standalone runs), while OSGi resolves the imported `org.jspecify.annotations` package.

## Run as a standalone tool (like `meter-tool-2.py`)

```sh
./gradlew installDist
# copy build/install/meter-tool to the device, then:
/opt/meter-tool/bin/meter-tool                 # CSV to stdout, one row / 5 s
/opt/meter-tool/bin/meter-tool --mode calibrate # interactive calibration menu
```

Or run straight from Gradle during development:

```sh
./gradlew run --args='--mode csv'
```

CSV output matches the Python tool. One cosmetic difference: the `Timestamp` column is an
ISO-8601 instant with millisecond precision (`2026-09-10T12:34:56.789Z`) rather than the
microsecond precision Python's `datetime` emits.

## Deploy as an OSGi bundle (SolarNode / Equinox)

This bundle imports three packages the framework must provide:

1. Install the **JNA bundle**: `net.java.dev.jna:jna:5.17.0` (exports
   `com.sun.jna`); this bundle imports `com.sun.jna;version="[5.17,6)"`.
2. Install the **JSpecify bundle**: `org.jspecify:jspecify:1.0.0` (exports
   `org.jspecify.annotations`); imported as `org.jspecify.annotations;version="[1.0,2.0)"`.
   SolarNode already ships this.
3. `org.osgi.framework;version="[1.10,2)"` — provided by the framework itself, for the
   `Bundle-Activator`.
4. Install this bundle.
5. Invoke `MeterTool.runCsvMode()` / `runCalibrationMode()` from your own component, or wrap
   them in a Gogo command:

   ```java
   @Component(property = { "osgi.command.scope=metertool",
           "osgi.command.function=csv", "osgi.command.function=calibrate" })
   public class MeterToolCommands {
       public void csv() { MeterTool.runCsvMode(); }
       public void calibrate() { MeterTool.runCalibrationMode(); }
   }
   ```

### Runtime requirements on the device

- The user running SolarNode must be able to open `/dev/spidev0.0` — add it to the `spi`
  group (see the [top-level README](../README.md)).
- JNA unpacks its native `libjnidispatch.so` to a temp directory and `dlopen`s it. If `/tmp`
  is mounted `noexec`, point JNA elsewhere with `-Djna.tmpdir=/var/tmp/jna` (writable, exec)
  or install the OS `libjna-java` / `libjnidispatch-java` package and set
  `-Djna.nosys=false`.

### JNA native binding lifecycle

`LinuxSpiDevice` binds libc with JNA **direct mapping** (`Native.register`), which allocates a
native handle per mapped method. The [`Activator`](src/main/java/net/solarnetwork/node/hw/atm90e36/spi/Activator.java)
calls `LinuxSpiDevice.unregisterNativeMethods()` on bundle stop so that binding class and its
handles are freed promptly rather than lingering until GC across a reinstall. (If you drive
`LinuxSpiDevice` from your own activator or DS component instead, drop the `Bundle-Activator`
header and call `unregisterNativeMethods()` from your own stop path.)

Multiple bundles binding libc is fine — `Native.register` is per-class, and JNA `dlopen`s
libc once and shares it — **provided they all resolve `com.sun.jna` to a single JNA bundle**.
Two JNA bundles means two `libjnidispatch` loads and an `UnsatisfiedLinkError`. The bundle's
real floor is JNA 5.12 (`Memory` is `Closeable`), so `bnd.bnd` could widen
`com.sun.jna;version="[5.17,6)"` to `[5.12,6)` to share with older consumers such as
`io.helins:linux-common` (JNA 5.7 — would need bumping).

## SPI details

`LinuxSpiDevice` opens the device `O_RDWR | O_CLOEXEC` and issues:

| ioctl | value | purpose |
|---|---|---|
| `SPI_IOC_WR_MODE32` | `0x40046B05` | SPI mode 3 (CPOL=1, CPHA=1) |
| `SPI_IOC_WR_BITS_PER_WORD` | `0x40016B03` | 8 |
| `SPI_IOC_WR_MAX_SPEED_HZ` | `0x40046B04` | 200000 |
| `SPI_IOC_MESSAGE(N)` | `_IOW('k',0,char[N*32])` | `N` full-duplex transfers in one call |

Each `transfer(byte[])` maps to a single `spi_ioc_transfer` (32 bytes, `speed_hz = 0` so the
bus default applies) — the same semantics as `spidev`'s `xfer2` and `spidev2`'s `transfer`.
The ioctl request codes are verified against the header constants in
[`LinuxSpiDeviceTests`](src/test/java/net/solarnetwork/node/hw/atm90e36/spi/LinuxSpiDeviceTests.java).

### Batched register reads

The CSV path is the primary use case, and each row needs 16 register reads. Rather than 16
`ioctl` calls (each with its own JNA dispatch and `~10 µs` settle sleep),
[`Atm90E36.readMeasurements()`](src/main/java/net/solarnetwork/node/hw/atm90e36/Atm90E36.java)
builds all 16 read frames once and submits them as **one** `SPI_IOC_MESSAGE(16)` `ioctl`.

The ATM90E36 SPI protocol accesses exactly one register per chip-select cycle (datasheet
§4.2.1: *"The SPI read/write transaction is CS-low defined. Each transaction can only access
one register."*), so the batch is 16 discrete transfers with `cs_change = 1` set on all but
the last — the kernel toggles CS between them, and a `delay_usecs = 10` hold is applied after
each. The datasheet's only inter-transaction requirement is `tCSH` (min CS-high) of `2T + 10 ns`,
so the `10 µs` hold is conservative headroom, not a hard requirement; the max SCLK is 1.2 MHz
(we use 200 kHz, matching the validated Python path).

**Reusable batch.** For callers that poll frequently, `SpiDevice.batch(byte[][] txFrames, int
settleMicros)` returns an `AutoCloseable` `Batch` whose `transfer()` re-runs the same frames
without re-allocating the three native buffers `LinuxSpiDevice` needs per `SPI_IOC_MESSAGE`
(transmit block, receive block, `spi_ioc_transfer[]` array — the last populated only once).
`Atm90E36` creates its measurement `Batch` on first `readMeasurements()` and reuses it for
every call thereafter, releasing it in `close()`; so a 5 Hz (or faster) sampling loop
allocates only the returned `Measurements` record per tick. The one-shot
`SpiDevice.transfer(byte[][], int)` is now just `try (var b = batch(...)) { return b.transfer(); }`.

This removes ~15 system calls and all of the per-read thread-sleep jitter per CSV row. If a
particular SPI controller or `cs-gpios` device-tree setup mishandles `cs_change` inside a
message, call `LinuxSpiDevice.setMultiTransfer(false)` to fall back to one `ioctl` per frame
(the `SpiDevice` default behaviour); `readMeasurements()` then still returns the same result.
**This batched path needs on-hardware validation** — the unit tests exercise it only through
`FakeSpiDevice`.

## Tests

```sh
./gradlew test
```

Host-side only (no hardware): `FakeSpiDevice` emulates the ATM90E36 SPI framing, so the tests
cover the register byte-swapping, `readRegister` / `writeRegister` framing, the measurement
scaling factors, the `begin()` configuration / checksum sequence, the batched
`readMeasurements()` (16 frames, values matching the individual accessors, one `Batch` reused
across calls and released on `close()`), and the CSV formatting. The `ioctl` transfer paths
themselves — single and batched — can only be exercised on a real device.

## License

GPL-2.0-or-later, matching the [`SolarNetwork/solarnetwork-node`](https://github.com/SolarNetwork/solarnetwork-node)
project and this repository's [LICENSE](../LICENSE). Every source file carries the standard
SolarNetwork GPLv2 header.
