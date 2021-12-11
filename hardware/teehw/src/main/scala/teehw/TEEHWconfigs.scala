package uec.teehardware

import chisel3._
import chisel3.util.log2Up
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import sifive.blocks.devices.gpio._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.uart._
import sifive.blocks.devices.i2c._
import uec.teehardware.devices.aes._
import uec.teehardware.devices.ed25519._
import uec.teehardware.devices.sha3._
import uec.teehardware.devices.usb11hs._
import uec.teehardware.devices.random._
import uec.teehardware.devices.opentitan.aes._
import uec.teehardware.devices.opentitan.alert._
import uec.teehardware.devices.opentitan.hmac._
import uec.teehardware.devices.opentitan.otp_ctrl._
import boom.common._
import testchipip.{SerialTLAttachKey, SerialTLAttachParams, SerialTLKey, SerialTLParams}
import freechips.rocketchip.util.BooleanToAugmentedBoolean
import uec.teehardware.devices.clockctrl.{ClockCtrlParams, PeripheryClockCtrlKey}
import uec.teehardware.devices.opentitan.nmi_gen._
import uec.teehardware.ibex._


// ***************** ISA Configs (ISACONF) ********************
class RV64GC extends Config((site, here, up) => {
  case XLen => 64
})

class RV64IMAC extends Config((site, here, up) => {
  case XLen => 64
  case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem), site) map {
    case r: RocketTileAttachParams => r.copy(tileParams = r.tileParams.copy(core = r.tileParams.core.copy(fpu = None)))
    case b: BoomTileAttachParams => b.copy(
      tileParams = b.tileParams.copy(
        core = b.tileParams.core.copy(
          fpu = None,
          issueParams = b.tileParams.core.issueParams.filter(_.iqType != IQT_FP.litValue))))
    case other => other
  }
  // Do it also in this key.. just because
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(fpu = None))
  }
})

// ************ Hybrid core configurations (HYBRID) **************

//Only Rocket: 2 cores
class Rocket extends Config(
  new WithNBigCores(2))
class RocketReduced extends Config(
  new WithSmallCacheBigCore(2))
class Rocket4 extends Config(
  new WithNBigCores(4))
class Rocket8 extends Config(
  new WithNBigCores(8))

// Ibex only (For microcontrollers)
class Ibex extends Config(
  new WithNIbexCores(1) )

// Rocket just for small configs
class RocketSmall extends Config(
  new WithNSmallCores(1, Some(0))
)
class RocketMicro extends With1TinyCore

// Rocket Very Small Cache
class MicroCached extends Config ((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(
      btb = None,
      dcache = r.dcache map {d =>
        d.copy(
          nSets = 64, // 2Kb cache
          nWays = 1,
          nTLBSets = 1,
          nTLBWays = 4,
          nMSHRs = 0
        )
      },
      icache = r.icache map {i =>
        i.copy(
          nSets = 64, // 2Kb cache
          nWays = 1,
          nTLBSets = 1,
          nTLBWays = 4,
        )})
  }}
)

// Microcontroller with only scratchpad
class Micro extends Config ((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy( dcache = r.dcache map { d =>
      d.copy(
        nSets = 64, // 4Kb scratchpad
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 4,
        nMSHRs = 0,
        scratch = Some(0x80000000L)
      )},
      icache = r.icache map {i =>
        i.copy(
          nSets = 32, // 2Kb cache
          nWays = 1,
          nTLBSets = 1,
          nTLBWays = 4,
        )})
  }}
)

// Non-secure Ibex (Without Isolation)
class Ibex2RocketNonSecure extends Config(
    new WithNBigCores(2) ++
    new WithNIbexCores(1))

// Non-secure Ibex (Without Isolation) but reduced
class Ibex2RocketNonSecureReduced extends Config(
    new WithSmallCacheBigCore(2) ++
    new WithNIbexCores(1))

// ************ BootROM configuration (BOOTSRC) **************
class BOOTROM extends Config((site, here, up) => {
  case MaskROMLocated(InSubsystem) => Seq(
    MaskROMParams(address = BigInt(0x20000000), depth = 4096, name = "BootROM"))
  case TEEHWResetVector => 0x20000000
  case PeripherySPIFlashKey => List() // disable SPIFlash
})

class QSPI extends Config((site, here, up) => {
  case MaskROMLocated(InSubsystem) => Seq( //move BootROM back to 0x10000
    MaskROMParams(address = 0x10000, depth = 4096, name = "BootROM")) //smallest allowed depth is 16
  case TEEHWResetVector => 0x10040
  case PeripherySPIFlashKey => List(
    SPIFlashParams(fAddress = 0x20000000, rAddress = 0x64005000, defaultSampleDel = 3))
  // Now, the PeripherySPIKey will have the SPI. Both the MMC(0) and the FLASH(1) may be here.
  // We need to out only 1 element (Considered the MMC only) if QSPI is here.
  case PeripherySPIKey => up(PeripherySPIKey).slice(0, 1)
})

// ************ Chip Peripherals (PERIPHERALS) ************
class TEEHWPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)),
    //SPIParams(rAddress = BigInt(0x64005000L)) // TODO: Add this for OTP in the release
  )
  case PeripheryGPIOKey => List(
    GPIOParams(address = BigInt(0x64002000L), width = 16))
  case GPIOInKey => 8
  // TEEHW devices
  case PeripherySHA3Key => List(
    SHA3Params(address = BigInt(0x64003000L)))
  case Peripheryed25519Key => List(
    ed25519Params(address = BigInt(0x64004000L)))
  case PeripheryI2CKey => List(
    I2CParams(address = 0x64006000))
  case PeripheryAESKey => List(
    AESParams(address = BigInt(0x64007000L)))
//  case PeripheryUSB11HSKey => List(
//    USB11HSParams(address = BigInt(0x64008000L)))
  case PeripheryRandomKey => List(
    RandomParams(address = BigInt(0x64009000L), impl = 1))
  case PeripheryClockCtrlKey => List(
    ClockCtrlParams(address = BigInt(0x64010000L)))
  // OpenTitan devices
  case PeripheryAESOTKey => List()
  case PeripheryHMACKey => List()
  case PeripheryOTPCtrlKey => List()
  case PeripheryAlertKey =>
    AlertParams(address = BigInt(0x64100000L))
  case PeripheryNmiGenKey =>
    NmiGenParams(address = BigInt(0x64200000L))
})

class TLS13Peripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)),
    //SPIParams(rAddress = BigInt(0x64005000L)) // TODO: Add this for OTP in the release
  )
  case PeripheryGPIOKey => List(
    GPIOParams(address = BigInt(0x64002000L), width = 16))
  case GPIOInKey => 8
  // TEEHW devices
  case PeripherySHA3Key => List()
  case Peripheryed25519Key => List(
    ed25519Params(address = BigInt(0x64004000L)))
  case PeripheryI2CKey => List(
    I2CParams(address = 0x64006000))
  case PeripheryAESKey => List()
  case PeripheryUSB11HSKey => List(
    USB11HSParams(address = BigInt(0x64008000L)))
  case PeripheryRandomKey => List(
    RandomParams(address = BigInt(0x64009000L), impl = 1))
  // OpenTitan devices
  case PeripheryAESOTKey => List()
  case PeripheryHMACKey => List()
  case PeripheryOTPCtrlKey => List()
  case PeripheryAlertKey =>
    AlertParams(address = BigInt(0x64100000L))
  case PeripheryNmiGenKey =>
    NmiGenParams(address = BigInt(0x64200000L))
})

class OpenTitanPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)))
  case PeripheryGPIOKey => List(
    GPIOParams(address = BigInt(0x64002000L), width = 16))
  case GPIOInKey => 8
  // TEEHW devices
  case PeripherySHA3Key => List()
  case Peripheryed25519Key => List()
  case PeripheryI2CKey => List()
  case PeripheryAESKey => List()
  case PeripheryUSB11HSKey => List()
  case PeripheryRandomKey => List()
  // OpenTitan devices
  case PeripheryAESOTKey => List(
    AESOTParams(address = BigInt(0x6400A000L)))
  case PeripheryHMACKey => List(
    HMACParams(address = BigInt(0x6400B000L)))
  case PeripheryOTPCtrlKey => List(
    OTPCtrlParams(address = BigInt(0x6400C000L)))
  case PeripheryAlertKey =>
    AlertParams(address = BigInt(0x64100000L))
  case PeripheryNmiGenKey =>
    NmiGenParams(address = BigInt(0x64200000L))

})

class TEEHWAndOpenTitanPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)))
  case PeripheryGPIOKey => List(
    GPIOParams(address = BigInt(0x64002000L), width = 16))
  case GPIOInKey => 8
  // TEEHW devices
  case PeripherySHA3Key => List(
    SHA3Params(address = BigInt(0x64003000L)))
  case Peripheryed25519Key => List(
    ed25519Params(address = BigInt(0x64004000L)))
  case PeripheryI2CKey => List(
    I2CParams(address = 0x64006000))
  case PeripheryAESKey => List(
    AESParams(address = BigInt(0x64007000L)))
  case PeripheryUSB11HSKey => List(
    USB11HSParams(address = BigInt(0x64008000L)))
  case PeripheryRandomKey => List(
    RandomParams(address = BigInt(0x64009000L), impl = 1))
  // OpenTitan devices
  case PeripheryAESOTKey => List(
    AESOTParams(address = BigInt(0x6400A000L)))
  case PeripheryHMACKey => List(
    HMACParams(address = BigInt(0x6400B000L)))
  case PeripheryOTPCtrlKey => List(
    OTPCtrlParams(address = BigInt(0x6400C000L)))
  case PeripheryAlertKey =>
    AlertParams(address = BigInt(0x64100000L))
  case PeripheryNmiGenKey =>
    NmiGenParams(address = BigInt(0x64200000L))

})

class NoSecurityPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)))
  case PeripheryGPIOKey => List(
    GPIOParams(address = BigInt(0x64002000L), width = 16))
  case GPIOInKey => 8
  case PeripherySHA3Key => List()
  case Peripheryed25519Key => List()
  case PeripheryI2CKey => List()
  case PeripheryAESKey => List(
    AESParams(address = BigInt(0x64007000L)))
  case PeripheryUSB11HSKey => List()
  case PeripheryRandomKey => List()
  case PeripheryAESOTKey => List()
  case PeripheryHMACKey => List()
  case PeripheryAlertKey =>
    AlertParams(address = BigInt(0x64100000L))
  case PeripheryNmiGenKey =>
    NmiGenParams(address = BigInt(0x64200000L))

})

// *************** Bus configuration (MBUS) ******************

class MBus32 extends Config((site, here, up) => {
  case ExtMem => Some(MemoryPortParams(MasterPortParams(
    base = x"0_8000_0000",
    size = x"0_4000_0000",
    beatBytes = 4,
    idBits = 4), 1))
  case ExtSerMem => None
})

class MBus64 extends Config((site, here, up) => {
  case ExtMem => Some(MemoryPortParams(MasterPortParams(
    base = x"0_8000_0000",
    size = x"0_4000_0000",
    beatBytes = 8,
    idBits = 4), 1))
  case ExtSerMem => None
})

class SBus8 extends Config((site, here, up) => {
  case ExtMem => None
  case ExtSerMem => Some(MemorySerialPortParams(MasterPortParams(
    base = x"0_8000_0000",
    size = x"0_4000_0000",
    beatBytes = 4,
    idBits = 4), 1, 8))
})

class SBus16 extends Config((site, here, up) => {
  case ExtMem => None
  case ExtSerMem => Some(MemorySerialPortParams(MasterPortParams(
    base = x"0_8000_0000",
    size = x"0_4000_0000",
    beatBytes = 4,
    idBits = 4), 1, 16))
})

class MBusNone extends Config((site, here, up) => {
  case ExtMem => None
  case ExtSerMem => None
})

// *************** Bus configuration (EXTBUS) ******************

class EBus8 extends Config((site, here, up) => {
  case ExtSerBus => Some(MemorySerialPortParams(MasterPortParams(
    base = x"0_6401_0000",
    size = x"0_0002_0000",
    beatBytes = 4,
    idBits = 4), 1, 8))
  case ExtBusKey => ExtBusParams(4, 64)
})

class EBus16 extends Config((site, here, up) => {
  case ExtSerBus => Some(MemorySerialPortParams(MasterPortParams(
    base = x"0_6401_0000",
    size = x"0_0002_0000",
    beatBytes = 4,
    idBits = 4), 1, 16))
  case ExtBusKey => ExtBusParams(4, 64)
})

// *************** PCI Configuration (PCIE) ******************
class WPCIe extends Config((site, here, up) => {
  case IncludePCIe => true
})

class WoPCIe extends Config((site, here, up) => {
  case IncludePCIe => false
})

// *************** DDR Clock configurations (DDRCLK) ******************
class WSepaDDRClk extends Config((site, here, up) => {
  case DDRPortOther => true
})

class WoSepaDDRClk extends Config((site, here, up) => {
  case DDRPortOther => false
})

class WSepaMBusClk extends Config((site, here, up) => {
  case DDRPortOther => false // Just for measure
  case SbusToMbusXTypeKey => AsynchronousCrossing() // The MBus clock will be separated
})

class WExposeClk extends Config((site, here, up) => {
  case ExposeClocks => true
})

// *************** Board Config (BOARD) ***************
class DE4Config extends Config((new WithIbexSynthesizedNoICache).alter((site,here,up) => {
  case FreqKeyMHz => 50.0
  case QSPICardMHz => 1.0
  case SDCardMHz => 5.0
  /* DE4 is not support PCIe (yet) */
  case IncludePCIe => false
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Altera", impl = 0)
  }
  /* The DDR memory supports 128 transactions. This is to avoid modifying chipyard*/
  case MemoryBusKey => up(MemoryBusKey).copy(blockBytes = 64)
}))

class TR4Config extends Config((new WithIbexSynthesizedNoICache).alter((site,here,up) => {
  case FreqKeyMHz => 50.0
  case QSPICardMHz => 1.0
  case SDCardMHz => 5.0
  /* TR4 is not support PCIe (yet) */
  case IncludePCIe => false
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Altera", impl = 0)
  }
  /* The DDR memory supports 64 transactions. This is to avoid modifying chipyard*/
  case MemoryBusKey => up(MemoryBusKey).copy(blockBytes = 64)
}))

class TR5Config extends Config((new WithIbexSynthesizedNoICache).alter((site,here,up) => {
  case FreqKeyMHz => 50.0
  case QSPICardMHz => 1.0
  case SDCardMHz => 5.0
  /* TR4 is not support PCIe (yet) */
  case IncludePCIe => false
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Altera", impl = 0)
  }
  /* The DDR memory supports 64 transactions. This is to avoid modifying chipyard*/
  case MemoryBusKey => up(MemoryBusKey).copy(blockBytes = 64)
  case ExtMem => up(ExtMem).map{ext => ext.copy(master = ext.master.copy(size = x"0_8000_0000"))}
  case ExtSerMem => up(ExtSerMem).map{ext => ext.copy(master = ext.master.copy(size = x"0_8000_0000"))}
}))

class VC707Config extends Config((site,here,up) => {
  case FreqKeyMHz => 50.0
  /* Force to use BootROM because VC707 doesn't have enough GPIOs for QSPI */
  case MaskROMLocated(InSubsystem) => Seq(
    MaskROMParams(address = BigInt(0x20000000), depth = 0x4000, name = "BootROM"))
  case TEEHWResetVector => 0x20000000
  case PeripherySPIFlashKey => List() // disable SPIFlash
  case PeripherySPIKey => up(PeripherySPIKey).slice(0, 1) // Disable SPIFlash, even if is the backup
  /* Force to disable USB1.1, because there are no pins */
  case PeripheryUSB11HSKey => List()
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Xilinx")
  }
  /* The DDR memory supports 128 transactions. This is to avoid modifying chipyard*/
  case MemoryBusKey => up(MemoryBusKey).copy(blockBytes = 128)
})

class VCU118Config extends Config((site,here,up) => {
  case FreqKeyMHz => 50.0
  case SDCardMHz => 5.0
  case QSPICardMHz => 1.0
  /* Force to disable USB1.1, because there are no pins */
  case PeripheryUSB11HSKey => List()
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Xilinx")
  }
  case PeripheryGPIOKey => up(PeripheryGPIOKey).map(_.copy(width = 12)) // Only 12
  case GPIOInKey => 4
    // *********** PCI Support ************
    // We are going to support the PCIe using XDMA
    // The connections are similar, but the definitions are not
    // In the case we detect the up(IncludePCIe), we enable the DMAPCIe
    // From there, is just a matter of actually disabling always the IncludePCIe,
    // as this one just enables the VC707 version
    // TODO: Do a cleaner approach
  case XDMAPCIe => up(IncludePCIe).option(sifive.fpgashells.ip.xilinx.xdma.XDMAParams(
    name = "fmc_xdma", location = "X0Y3", lanes = 4,
    bars = Seq(AddressSet(0x40000000L, 0x1FFFFFFFL)),
    control = 0x2000000000L,
    bases = Seq(0x40000000L),
    gen = 3
  ))
  case IncludePCIe => false
  /* The DDR memory supports 256*8 transactions. This is to avoid modifying chipyard*/
  case MemoryBusKey => up(MemoryBusKey).copy(blockBytes = 256*8)
})

class ArtyA7Config extends Config((site,here,up) => {
  case FreqKeyMHz => 50.0
  case PeripheryUSB11HSKey => List()
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {r =>
    r.copy(board = "Xilinx")
  }
    // Transform all ExtMem and ExtSerMem into 256MB
  case ExtMem => up(ExtMem).map{ mem =>
    mem.copy(mem.master.copy(size = x"0_1000_0000"))
  }
  case ExtSerMem => up(ExtSerMem).map{ mem =>
    mem.copy(mem.master.copy(size = x"0_1000_0000"))
  }

  // Not supported
  case ExtSerBus => None
})

// ***************** The simulation flag *****************
class WithSimulation extends Config((site, here, up) => {
  // Frequency is always 100.0 MHz in simulation mode, independent of the board
  case FreqKeyMHz => 100.0
  // Force the DMI to NOT be JTAG
  //case ExportDebug => up(ExportDebug, site).copy(protocols = Set(DMI))
  // Force also the Serial interface
  case SerialTLKey => Some(SerialTLParams(
    memParams = MasterPortParams(
      base = BigInt("10000000", 16),
      size = BigInt("00001000", 16),
      beatBytes = site(MemoryBusKey).beatBytes,
      idBits = 4
    ),
    width = 4
  ))
  case SerialTLAttachKey => SerialTLAttachParams()
  /* Force to use QSPI-scenario because then the XIP will be put in the BootROM */
  /* Simulation needs the hang function in the XIP */
  case MaskROMLocated(InSubsystem) => Seq(
    MaskROMParams(address = BigInt(0x10000), depth = 4096, name = "BootROM"))
  case TEEHWResetVector => 0x10040 // The hang vector in this case, to support the Serial load
  // DDRPortOther is unsupported
  case DDRPortOther => false
  // USB11HS has problems compiling on verilator.
  case PeripheryUSB11HSKey => List()
  // Random only should include the TRNG version
  case PeripheryRandomKey => up(PeripheryRandomKey, site) map {case r => r.copy(impl = 0) }
})

class Explanation extends Config(
  // Now, here is an explanation of how it works
  // 1. The f(_,_,_) will find the first case encountered from Up-to-Down (Config.scala:95)
  //    if not found, will go to the tail. And the tail are the ones you put with the ++ operator
  // 2. The f(site, here, up) function is named for PartialParameters (Config.scala:88)
  //    this will get the configuration up to this point. Actually "up" is the tail I talk you before
  //    so, is a little wrong calling that "up". If you access "up", will get the tail attached to
  //    the current scope.
  // 3. In lame terms, the first found will be always the top one, then start to search another in the
  //    subsequent tails (or ups), meaning top -> bottom is the way it search
  // 4. In lame terms, up() will always get the next tail, so will search anything that is ++'ed at that point
  // 5. Finally, in the Rocket Chip options (GeneratorUtils.scala:21), the underscore stuff will do the
  //    same exact thing as this class, if you feed it with:
  //    VC707Config_MBus64_WoSepaDDRClk_WoPCIe_BOOTROM_Rocket_TEEHWPeripherals_RV64GC
  // 6. In even lamer terms:
  //    a) Anything that CREATES the case and defaults it, should be on last
  //    b) Anything that MODIFIES the case by accessing up(), should be higher (or earlier)
  //    c) Anything that wants to FORCE the case, should be even higher (Like WithSimulation, or the Boards)
  new VC707Config ++
    new RV64GC ++
    new Rocket ++
    new MBus64 ++
    new WoSepaDDRClk ++
    new WoPCIe ++
    new BOOTROM ++
    new TEEHWPeripherals ++
    new ChipConfig
)
