package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.regmapper.{HasRegMap, RegField, RegisterWriteIO}
import freechips.rocketchip.tilelink.{TLIdentityNode, TLRegBundle, TLRegModule, TLRegisterRouter}

import ee290cdma._

case class BLEBasebandModemParams (
  address: BigInt = 0x8000,
  paddrBits: Int = 32,
  maxReadSize: Int = 258,
  cmdQueueDepth: Int = 4,
  modemQueueDepth: Int = 128)

case object BLEBasebandModemKey extends Field[Option[BLEBasebandModemParams]](None)

class BLEBasebandModemAnalogIO extends Bundle {
  val modemClock = Input(Clock())
  val offChipMode = Output(Bool())
  val data = new GFSKModemAnalogIO
  val tuning = Output(new GFSKModemTuningIO)
}

class BLEBasebandModemCommand extends Bundle {
  val inst = new Bundle {
    val primaryInst = UInt(4.W)
    val secondaryInst = UInt(4.W)
    val data = UInt(24.W)
  }
  val additionalData = UInt(32.W)
}

class BLEBasebandModemStatus extends Bundle {
  val status0 = UInt(32.W)
  val status1 = UInt(32.W)
  val status2 = UInt(32.W)
  val status3 = UInt(32.W)
  val status4 = UInt(32.W)
}
class BLEBasebandModemBackendIO extends Bundle {
  val cmd = Decoupled(new BLEBasebandModemCommand)
  val status = Input(new BLEBasebandModemStatus)
  val interrupt = Input(Bool())
}

trait BLEBasebandModemFrontendBundle extends Bundle {
  val back = new BLEBasebandModemBackendIO
  val tuning = Output(new GFSKModemTuningIO)
}

trait BLEBasebandModemFrontendModule extends HasRegMap {
  val io: BLEBasebandModemFrontendBundle

  // Instruction from processor
  val inst = Wire(new DecoupledIO(UInt(32.W)))
  val additionalData = Reg(UInt(32.W))

  // Writing to the instruction triggers the command to be valid.
  // So if you wish to set data you write that first then write inst
  inst.ready := io.back.cmd.ready
  io.back.cmd.bits.additionalData := additionalData
  io.back.cmd.bits.inst.primaryInst := inst.bits(3, 0)
  io.back.cmd.bits.inst.secondaryInst := inst.bits(7, 4)
  io.back.cmd.bits.inst.data := inst.bits(31, 8)
  io.back.cmd.valid := inst.valid

  // Status regs
  val status0 = RegInit(0.U(32.W))
  val status1 = RegInit(0.U(32.W))
  val status2 = RegInit(0.U(32.W))
  val status3 = RegInit(0.U(32.W))
  val status4 = RegInit(0.U(32.W))

  status0 := io.back.status.status0
  status1 := io.back.status.status1
  status2 := io.back.status.status2
  status3 := io.back.status.status3
  status4 := io.back.status.status4

  // Tuning bits store
  val trim_g0 = RegInit(0.U(8.W))
  val trim_g1 = RegInit(0.U(8.W))
  val trim_g2 = RegInit(0.U(8.W))
  val trim_g3 = RegInit(0.U(8.W))
  val trim_g4 = RegInit(0.U(8.W))
  val trim_g5 = RegInit(0.U(8.W))
  val trim_g6 = RegInit(0.U(8.W))
  val trim_g7 = RegInit(0.U(8.W))

  val mixer_r0 = RegInit(0.U(4.W))
  val mixer_r1 = RegInit(0.U(4.W))
  val mixer_r2 = RegInit(0.U(4.W))
  val mixer_r3 = RegInit(0.U(4.W))

  val i_vgaAtten = RegInit(0.U(5.W))
  val i_filter_r0 = RegInit(0.U(4.W))
  val i_filter_r1 = RegInit(0.U(4.W))
  val i_filter_r2 = RegInit(0.U(4.W))
  val i_filter_r3 = RegInit(0.U(4.W))
  val i_filter_r4 = RegInit(0.U(4.W))
  val i_filter_r5 = RegInit(0.U(4.W))
  val i_filter_r6 = RegInit(0.U(4.W))
  val i_filter_r7 = RegInit(0.U(4.W))
  val i_filter_r8 = RegInit(0.U(4.W))
  val i_filter_r9 = RegInit(0.U(4.W))

  val q_vgaAtten = RegInit(0.U(5.W))
  val q_filter_r0 = RegInit(0.U(4.W))
  val q_filter_r1 = RegInit(0.U(4.W))
  val q_filter_r2 = RegInit(0.U(4.W))
  val q_filter_r3 = RegInit(0.U(4.W))
  val q_filter_r4 = RegInit(0.U(4.W))
  val q_filter_r5 = RegInit(0.U(4.W))
  val q_filter_r6 = RegInit(0.U(4.W))
  val q_filter_r7 = RegInit(0.U(4.W))
  val q_filter_r8 = RegInit(0.U(4.W))
  val q_filter_r9 = RegInit(0.U(4.W))

  io.tuning.trim.g0 := trim_g0
  io.tuning.trim.g1 := trim_g1
  io.tuning.trim.g2 := trim_g2
  io.tuning.trim.g3 := trim_g3
  io.tuning.trim.g4 := trim_g4
  io.tuning.trim.g5 := trim_g5
  io.tuning.trim.g6 := trim_g6
  io.tuning.trim.g7 := trim_g7

  io.tuning.mixer.r0 := mixer_r0
  io.tuning.mixer.r1 := mixer_r1
  io.tuning.mixer.r2 := mixer_r2
  io.tuning.mixer.r3 := mixer_r3

  io.tuning.i.vgaAtten := i_vgaAtten
  io.tuning.i.filter.r0 := i_filter_r0
  io.tuning.i.filter.r0 := i_filter_r1
  io.tuning.i.filter.r0 := i_filter_r2
  io.tuning.i.filter.r0 := i_filter_r3
  io.tuning.i.filter.r0 := i_filter_r4
  io.tuning.i.filter.r0 := i_filter_r5
  io.tuning.i.filter.r0 := i_filter_r6
  io.tuning.i.filter.r0 := i_filter_r7
  io.tuning.i.filter.r0 := i_filter_r8
  io.tuning.i.filter.r0 := i_filter_r9

  io.tuning.q.vgaAtten := q_vgaAtten
  io.tuning.q.filter.r0 := q_filter_r0
  io.tuning.q.filter.r0 := q_filter_r1
  io.tuning.q.filter.r0 := q_filter_r2
  io.tuning.q.filter.r0 := q_filter_r3
  io.tuning.q.filter.r0 := q_filter_r4
  io.tuning.q.filter.r0 := q_filter_r5
  io.tuning.q.filter.r0 := q_filter_r6
  io.tuning.q.filter.r0 := q_filter_r7
  io.tuning.q.filter.r0 := q_filter_r8
  io.tuning.q.filter.r0 := q_filter_r9


  interrupts(0) := io.back.interrupt

  regmap(
    0x00 -> Seq(RegField.w(32, inst)), // Command start
    0x04 -> Seq(RegField.w(32, additionalData)),
    0x08 -> Seq(RegField.r(32, status0)), // Status start
    0x0C -> Seq(RegField.r(32, status1)),
    0x10 -> Seq(RegField.r(32, status2)),
    0x14 -> Seq(RegField.r(32, status3)),
    0x18 -> Seq(RegField.r(32, status4)),
    0x1C -> Seq(RegField(8, trim_g0)), // Tuning start
    0x1D -> Seq(RegField(8, trim_g1)),
    0x1E -> Seq(RegField(8, trim_g2)),
    0x1F -> Seq(RegField(8, trim_g3)),
    0x20 -> Seq(RegField(8, trim_g4)),
    0x21 -> Seq(RegField(8, trim_g5)),
    0x22 -> Seq(RegField(8, trim_g6)),
    0x23 -> Seq(RegField(8, trim_g7)),
    0x24 -> Seq(
      RegField(4, mixer_r0),
      RegField(4, mixer_r1)),
    0x25 -> Seq(
      RegField(4, mixer_r2),
      RegField(4, mixer_r3)),
    0x26 -> Seq(RegField(5, i_vgaAtten)),
    0x27 -> Seq(
      RegField(4, i_filter_r0),
      RegField(4, i_filter_r1)),
    0x28 -> Seq(
      RegField(4, i_filter_r2),
      RegField(4, i_filter_r3)),
    0x29 -> Seq(
      RegField(4, i_filter_r4),
      RegField(4, i_filter_r5)),
    0x2A -> Seq(
      RegField(4, i_filter_r6),
      RegField(4, i_filter_r7)),
    0x2B -> Seq(
      RegField(4, i_filter_r8),
      RegField(4, i_filter_r9)),
    0x2C -> Seq(RegField(5, q_vgaAtten)),
    0x2D -> Seq(
      RegField(4, q_filter_r0),
      RegField(4, q_filter_r1)),
    0x2E -> Seq(
      RegField(4, q_filter_r2),
      RegField(4, q_filter_r3)),
    0x2F -> Seq(
      RegField(4, q_filter_r4),
      RegField(4, q_filter_r5)),
    0x30 -> Seq(
      RegField(4, q_filter_r6),
      RegField(4, q_filter_r7)),
    0x31 -> Seq(
      RegField(4, q_filter_r8),
      RegField(4, q_filter_r9)),
  )
}

class BLEBasebandModemFrontend(params: BLEBasebandModemParams, beatBytes: Int)(implicit p: Parameters)
  extends TLRegisterRouter(
    params.address, "baseband", Seq("ucbbar, riscv"),
    beatBytes = beatBytes, interrupts = 1)( // TODO: Interrupts and compatible list
      new TLRegBundle(params, _) with BLEBasebandModemFrontendBundle)(
      new TLRegModule(params, _, _) with BLEBasebandModemFrontendModule)

class BLEBasebandModem(params: BLEBasebandModemParams, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val dma = LazyModule(new EE290CDMA(beatBytes, params.maxReadSize, "baseband"))

  val mmio = TLIdentityNode()
  val mem = dma.id_node

  val basebandFrontend = LazyModule(new BLEBasebandModemFrontend(params, beatBytes))
  val intnode = basebandFrontend.intnode

  basebandFrontend.node := mmio

  lazy val module = new BLEBasebandModemImp(params, beatBytes,this)
}

class BLEBasebandModemImp(params: BLEBasebandModemParams, beatBytes: Int, outer: BLEBasebandModem)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val io = dontTouch(IO(new BLEBasebandModemAnalogIO))

  import outer._

  basebandFrontend.module.io.back.interrupt := false.B

  val cmdQueue = Queue(basebandFrontend.module.io.back.cmd, params.cmdQueueDepth)

  cmdQueue.ready := false.B

  //val controller = Module(new Controller(params.paddrBits, beatBytes))

  //controller.io.cmd <> cmdQueue
}

//class BLEBasebandModem(params: BLEBasebandModemParams)(implicit p: Parameters) extends LazyRoCC(opcodes = opcodes) {
//  val beatBytes = p(SystemBusKey).beatBytes
//
//  val dma = new EE290CDMA(beatBytes, 258, "baseband")
//
//  override lazy val module = new BLEBasebandModemImp(this)
//  val tlNode = dma.id_node
//}
//
//class BLEBasebandModemImp(outer: BLEBasebandModem) extends LazyRoCCModuleImp(outer) with HasCoreParameters {
//  val modemIO = IO(new Bundle {
//    val modemClock = Input(Clock())
//    val analog = new GFSKModemAnalogIO
//  })
//
//  import outer.beatBytes
//
//  val interruptServicer = new InterruptServicer
//  interruptServicer.io.cmd.in <> io.cmd
//  io.resp <> interruptServicer.io.interrupt.resp
//
//  val cmdQueue = Queue(interruptServicer.io.cmd.out, 8) // TODO: should queue depth be a config?
//
//  val controller = new Controller(paddrBits, beatBytes)
//  controller.io.cmd <> cmdQueue
//
//  val baseband = new Baseband(paddrBits, beatBytes)
//  baseband.io.control <> controller.io.basebandControl
//
//  val basebandLoopback = new DecoupledLoopback(UInt(1.W))
//  basebandLoopback.io.select := controller.io.constants.loopbackSelect(0) // TODO: define an object that contains macros for loopback bits
//  basebandLoopback.io.left.in <> baseband.io.modem.tx
//  baseband.io.modem.rx <> basebandLoopback.io.left.out
//
////  val modem = new GFSKModem
////  basebandLoopback.io.right.in <> modem.io.baseband.rx
////  modem.io.baseband.tx <> basebandLoopback.io.right.out
////
////  modem.io.analog <> modemIO.analog
////  modem.io.modemClock := modemIO.modemClock
//}

