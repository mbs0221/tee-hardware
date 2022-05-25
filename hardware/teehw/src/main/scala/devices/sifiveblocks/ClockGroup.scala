package uec.teehardware.devices.sifiveblocks

import chipsalliance.rocketchip.config.Field
import chisel3._
import chisel3.util.HasBlackBoxResource
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.util.ResetCatchAndSync
import uec.teehardware.TEEHWBaseSubsystem

// Frequency
case object FreqKeyMHz extends Field[Double](100.0)

trait HasTEEHWClockGroup {
  this: BaseSubsystem =>
  // The clock resource. This is just for put in the DTS the tlclock
  // TODO: Now the clock is derived from the bus that is connected
  // TODO: We need a way now to extract that one in the makefiles
  // Letting this ONLY for compatibility
  val tlclock = new FixedClockResource("tlclk", p(FreqKeyMHz))

  // NOTE: I do not know how this work yet. Now the clock is VERY important, for knowing where the
  // clock domains came from. You can assign it to different nodes, and create new ones.
  // Eventually, this will create even their own dts for reference purposes.
  // so, you DEFINITELY need to define your clocks from now on. This will be assigned to asyncClockGroupsNode
  // and the "SubsystemDriveAsyncClockGroupsKey" key needs to be None'd to avoid default clocks
  // There should be a easier way, but right now also the Sifive peripherals and the TEE peripherals
  // uses all of that. So, there is no way.
  // (Code analyzed from: Clocks.scala:61, inside chipyard)
  // (Code analyzed from: ClockGroup.scala:63, inside rocketChip. And yes... I know I can just do a SimpleGroup.)

  // PRC domains:
  // The final analysis of the clock domains is just accumulated in the asyncClockGroupsNode
  // Everytime a clock is needed, the node just gets populated using "clockNode := (...) := asyncClockGroupsNode"
  // This means all the solicited clocks are going to be accumulated in the asyncClockGroupsNode
  // We can use the clock aggregator (ClockGroupAggregator) which will take a single clock and a reset, then
  // replicate it for all asyncClockGroupsNode. This requires a ClockGroup with only 1 group.
  // Then we iterate them using node.out.unzip. Unfortunately, will not have names.
  // NOTE2: The names are now extracted using the clocksAggregator out node. It contains all the names.

  // Create the ClockGroupSource (only 1...)
  val clockGroup = ClockGroupSourceNode(List.fill(1) { ClockGroupSourceParameters() })
  // Create the Aggregator. This will just take the SourceNode, then just replicate it in a Nexus
  val clocksAggregator = LazyModule(new ClockGroupAggregator("allClocks")).node
  // Connect it to the asyncClockGroupsNode, with the aggregator
  asyncClockGroupsNode :*= clocksAggregator := clockGroup
}

trait HasTEEHWClockGroupModuleImp extends LazyModuleImp {
  val outer: HasTEEHWClockGroup

  // Extract the number of clocks. According to the clockGroup definition, there is only one clockGroup
  val numClocks: Int = outer.clockGroup.out.map(_._1.member.data.size).sum

  // Create the actual port
  //val aclocks = IO(Vec(numClocks, Flipped(new ClockBundle(ClockBundleParameters()))))
  val aclocks = IO(Vec(numClocks, Input(Clock())))

  // Information of the clocks
  val extclocks = outer.clockGroup.out.flatMap(_._1.member.data)
  val namedclocks = outer.clocksAggregator.out.flatMap(_._1.member.elements).map(A => A._1)

  // Connect the clocks in the hardware
  (extclocks zip aclocks).foreach{ case (o, ai) =>
    o.clock := ai
    o.reset := ResetCatchAndSync(ai, reset.asBool, 5)
  }
}
