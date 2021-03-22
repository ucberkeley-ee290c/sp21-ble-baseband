package modem

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}
import baseband.{BLEBasebandModemParams, BasebandISA, DecoupledLoopback, BasebandConstants}
import chisel3.experimental.FixedPoint

class GFSKModemDigitalIO extends Bundle {
  val tx = Flipped(Decoupled(UInt(1.W)))
  val rx = Decoupled(UInt(1.W))
}

class AnalogTXIO extends Bundle {
  val loFSK = Output(UInt(8.W))
  val pllReady = Input(Bool())
}

class AnalogRXIO(params: BLEBasebandModemParams) extends Bundle {
  val i = new Bundle {
    val data = Input(UInt(params.adcBits.W))
    val valid = Input(Bool())
  }
  val q = new Bundle {
    val data = Input(UInt(params.adcBits.W))
    val valid = Input(Bool())
  }
}

class GFSKModemAnalogIO(params: BLEBasebandModemParams) extends Bundle {
  val tx = new AnalogTXIO
  val rx = new AnalogRXIO(params)
  val loCT = Output(UInt(8.W))
  val pllD = Output(UInt(11.W))
}

class GFSKModemTuningControlIO(val params: BLEBasebandModemParams) extends Bundle {
  val i = new Bundle {
    val AGC = new Bundle {
      val control = new AGCControlIO(params)
      val useAGC = Bool()
    }
  }
  val q = new Bundle {
    val AGC = new Bundle {
      val control = new AGCControlIO(params)
      val useAGC = Bool()
    }
  }
  val DCO = new Bundle {
    val control = new DCOControlIO
    val useDCO = Bool()
  }
}

class GFSKModemTuningIO extends Bundle {
  val trim = new Bundle {
    val g0 = UInt(8.W)
    val g1 = UInt(8.W)
    val g2 = UInt(8.W)
    val g3 = UInt(8.W)
    val g4 = UInt(8.W)
    val g5 = UInt(8.W)
    val g6 = UInt(8.W)
    val g7 = UInt(8.W)
  }
  val mixer = new Bundle {
    val r0 = UInt(4.W)
    val r1 = UInt(4.W)
    val r2 = UInt(4.W)
    val r3 = UInt(4.W)
  }
  val i = new Bundle {
    val vgaAtten = UInt(5.W)
    val filter = new Bundle {
      val r0 = UInt(4.W)
      val r1 = UInt(4.W)
      val r2 = UInt(4.W)
      val r3 = UInt(4.W)
      val r4 = UInt(4.W)
      val r5 = UInt(4.W)
      val r6 = UInt(4.W)
      val r7 = UInt(4.W)
      val r8 = UInt(4.W)
      val r9 = UInt(4.W)
    }
  }
  val q = new Bundle {
    val vgaAtten = UInt(5.W)
    val filter = new Bundle {
      val r0 = UInt(4.W)
      val r1 = UInt(4.W)
      val r2 = UInt(4.W)
      val r3 = UInt(4.W)
      val r4 = UInt(4.W)
      val r5 = UInt(4.W)
      val r6 = UInt(4.W)
      val r7 = UInt(4.W)
      val r8 = UInt(4.W)
      val r9 = UInt(4.W)
    }
  }
  val dac = new Bundle { // Offset correction connects to here
    val t0 = UInt(6.W)
    val t1 = UInt(6.W)
    val t2 = UInt(6.W)
    val t3 = UInt(6.W)
  }
  val enable = new Bundle {
    val rx = UInt(5.W)
  }
}

class GFSKModem(params: BLEBasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val digital = new GFSKModemDigitalIO
    val analog = new GFSKModemAnalogIO(params)
    val lutCmd = Flipped(Decoupled(new GFSKModemLUTCommand))
    val tuning = new Bundle {
      val data = new Bundle {
        val i = new Bundle {
          val vgaAtten = Output(UInt(5.W))
        }
        val q = new Bundle {
          val vgaAtten = Output(UInt(5.W))
        }
      }
      val control = Input(new GFSKModemTuningControlIO(params))
    }
    val constants = Input(new BasebandConstants)
  })

  val modemLUTs = Reg(new GFSKModemLUTs)

  // Manage SW set LUTs
  io.lutCmd.ready := true.B // TODO: either refactor to a valid only, or set ready based on controller state

  when (io.lutCmd.fire()) { // Write an entry into the LUTs for the LO
    val lut = io.lutCmd.bits.lut
    val address = io.lutCmd.bits.address
    val value = io.lutCmd.bits.value

    switch(lut) {
      is(GFSKModemLUTCodes.LOFSK) {
        modemLUTs.LOFSK(address) := value(7, 0)
      }
      is(GFSKModemLUTCodes.LOCT) {
        modemLUTs.LOCT(address) := value(7, 0)
      }
      is(GFSKModemLUTCodes.AGCI) {
        modemLUTs.AGCI(address) := value(4, 0)
      }
      is(GFSKModemLUTCodes.AGCQ) {
        modemLUTs.AGCQ(address) := value(4, 0)
      }
    }
  }

  val tx = Module(new GFSKTX())
  val rx = Module(new GFSKRX(params))

  val txQueue = Queue(io.digital.tx, params.modemQueueDepth)
  tx.io.digital.in <> txQueue

  val preModemLoopback = Module(new DecoupledLoopback(UInt(1.W)))
  preModemLoopback.io.select := true.B
  preModemLoopback.io.left.in <> txQueue
  tx.io.digital.in <> preModemLoopback.io.left.out
  preModemLoopback.io.right.in <> rx.io.digital.out

  val rxQueue = Queue(preModemLoopback.io.right.out, params.modemQueueDepth)
  io.digital.rx <> rxQueue

  val iQueue = Module(new AsyncQueue(UInt(params.adcBits.W), AsyncQueueParams(depth = params.adcQueueDepth)))
  iQueue.io.enq_clock := io.analog.rx.i.valid.asClock()
  iQueue.io.enq_reset := reset.asBool()
  iQueue.io.deq_clock := clock
  iQueue.io.deq_reset := reset.asBool()

  // TODO: Refactor RX incoming to be ready valid on I and Q bits

  val qQueue = Module(new AsyncQueue(UInt(params.adcBits.W), AsyncQueueParams(depth = params.adcQueueDepth)))
  qQueue.io.enq_clock := io.analog.rx.q.valid.asClock()
  qQueue.io.enq_reset := reset.asBool()
  qQueue.io.deq_clock := clock
  qQueue.io.deq_reset := reset.asBool()

  iQueue.io.enq.bits := io.analog.rx.i.data
  iQueue.io.enq.valid := true.B // TODO: Change this to be based on the modem state = RX
  rx.io.analog.i <> iQueue.io.deq

  qQueue.io.enq.bits := io.analog.rx.q.data
  qQueue.io.enq.valid := true.B // TODO: Change this to be based on the modem state = RX
  rx.io.analog.q <> qQueue.io.deq

  // AGC
  val iAGC = Module(new AGC(params))
  iAGC.io.control := io.tuning.control.i.AGC.control
  iAGC.io.adcIn.valid := iQueue.io.deq.valid
  iAGC.io.adcIn.bits := iQueue.io.deq.bits

  io.tuning.data.i.vgaAtten := modemLUTs.AGCI(iAGC.io.vgaLUTIndex)

  val qAGC = Module(new AGC(params))
  qAGC.io.control := io.tuning.control.q.AGC.control
  qAGC.io.adcIn.valid := iQueue.io.deq.valid
  qAGC.io.adcIn.bits := iQueue.io.deq.bits

  io.tuning.data.q.vgaAtten := modemLUTs.AGCQ(qAGC.io.vgaLUTIndex)

  // DCO
  val dco = Module(new DCO(params))
  dco.io.control := io.tuning.control.DCO.control
  dco.io.adcIn.valid := iQueue.io.deq.valid // TODO: How do we drive the 4 different DCO from 1 LUT
  dco.io.adcIn.bits := iQueue.io.deq.valid // TODO: Do we only run DCO on I or should we run on an I and Q average?

  // LUT defined outputs
  io.analog.pllD := DontCare
  io.analog.loCT := modemLUTs.LOCT(io.constants.channelIndex)
  io.analog.tx.loFSK := modemLUTs.LOFSK(tx.io.analog.gfskIndex)
}