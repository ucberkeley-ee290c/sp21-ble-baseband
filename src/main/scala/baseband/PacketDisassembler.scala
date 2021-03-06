/* Based on (with permission) the Fall 2018 290C BLE Baseband
   https://github.com/ucberkeley-ee290c/fa18-ble/
 */
package baseband

import chisel3._
import chisel3.util._

object PDAControlInputCommands {
  val START_CMD = 0.U
  val DEBUG_CMD = 1.U
  val EXIT_CMD = 2.U
}

class PDAControlInputBundle extends Bundle {
  val command = UInt(2.W)
  val aa = UInt(32.W)
}

class PDAInputIO extends Bundle {
  val control = Flipped(Valid(new PDAControlInputBundle))
  val preambleDetected = Input(Bool())
  val data = Flipped(Decoupled(UInt(1.W)))
}

class PDAControlOutputBundle extends Bundle {
  val length = UInt(8.W)
  val flag_aa = Bool()
  val flag_crc = Bool()
  val done = Bool() // Treat this as a "valid" when done is high we guarantee the other signals have their correct value
  val busy = Bool()
}

class PDAOutputIO extends Bundle {
  val control = Output(new PDAControlOutputBundle)
  val data = Decoupled(UInt(8.W))
}

class PacketDisassemblerIO extends Bundle {
  val in = new PDAInputIO
  val out = new PDAOutputIO
  val constants = Input(new BasebandConstants)
  val state = Output(UInt(log2Ceil(7+1).W))
}

class PacketDisassembler extends Module {

  def stateUpdate(currentState: UInt, nextState: UInt, length: UInt, counter: UInt, counterByte: UInt, condition: Bool)= {
    val stateOut = Wire(UInt(3.W))
    val counterOut = Wire(UInt(8.W))
    val counterByteOut = Wire(UInt(3.W))
    counterOut := counter
    counterByteOut := counterByte

    when(counter === length - 1.U && counterByte === 7.U && condition) {
      stateOut := nextState
      counterOut := 0.U
      counterByteOut := 0.U
    }.otherwise {
      stateOut := currentState
      when(condition) {
        when(counterByte === 7.U) {
          counterOut := counter + 1.U
          counterByteOut := 0.U
        }.otherwise {
          counterByteOut := counterByte + 1.U
        }
      }
    }
    (stateOut, counterOut, counterByteOut)
  }

  val io = IO(new PacketDisassemblerIO)

  val s_idle :: s_preamble :: s_preamble_debug :: s_aa :: s_pdu_header :: s_pdu_payload :: s_crc :: Nil = Enum(7)
  val state = RegInit(s_idle)

  io.state := state

  val aa = Reg(UInt(32.W))

  val counter = RegInit(0.U(8.W)) //counter for bytes in packet
  val counter_byte = RegInit(0.U(3.W)) //counter for bits in bytes

  //packet status
  val length = RegInit(0.U(8.W))
  val done = RegInit(false.B)
  val busy = RegInit(false.B)
  val flag_aa = RegInit(false.B)
  val flag_crc = RegInit(false.B)

  //Preamble
  val preamble0 = "b10101010".U
  val preamble1 = "b01010101".U
  val preamble = Mux(aa(0), preamble1, preamble0)

  //Handshake Parameters
  val out_valid = RegInit(false.B)
  val in_ready = RegInit(false.B)

  //data registers
  val data = RegInit(VecInit(Seq.fill(8)(false.B)))

  //crc
  val crc_reset = state === s_idle
  val crc_data = Wire(UInt(1.W))
  val crc_valid = Wire(Bool())
  val crc_result = Wire(UInt(24.W))
  val crc_seed = io.constants.crcSeed

  //whitening
  val dewhite_reset = state === s_idle
  val dewhite_data = Wire(UInt(1.W))
  val dewhite_valid = Wire(Bool())
  val dewhite_result = Wire(UInt(1.W))
  val dewhite_seed = Cat(Reverse(io.constants.channelIndex), 1.U(1.W)) // TODO: Verify this
  // TODO: Make sure to change the WHITENER TOO!


  //output function
  io.out.data.bits := data.asUInt

  io.out.control.length := length
  io.out.control.flag_aa := flag_aa
  io.out.control.flag_crc := flag_crc
  io.out.control.done := done

  io.out.data.valid := out_valid
  io.in.data.ready := in_ready

  io.out.control.busy := busy

  val out_fire = io.out.data.fire()
  val in_fire = io.in.data.fire()


  when (state === s_idle) {
    in_ready := false.B
    out_valid := false.B
    data := 0.U(8.W).asBools()

    length := 0.U
    flag_aa := false.B
    flag_crc := false.B
    done := false.B

    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.START_CMD ||
      io.in.control.bits.command === PDAControlInputCommands.DEBUG_CMD)) {
      aa := io.in.control.bits.aa
      counter := 0.U
      counter_byte := 0.U
      state := Mux(io.in.control.bits.command === PDAControlInputCommands.START_CMD, s_preamble, s_preamble_debug)
    }
  }.elsewhen(state === s_preamble){
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      when(io.in.preambleDetected) {
        busy := true.B
        state := s_aa
        counter := 0.U
        counter_byte := 0.U
        data := 0.U(8.W).asBools()
      }.elsewhen(io.in.control.fire() & io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD) { // Support pre-match exit
        in_ready := false.B
        out_valid := false.B
        data := 0.U(8.W).asBools()

        length := 0.U
        flag_aa := false.B
        flag_crc := false.B
      }
    }
  }.elsewhen (state === s_preamble_debug) {
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      in_ready := true.B
      out_valid := false.B

      when(in_fire) {
        data(7) := io.in.data.bits.asBool()
        for (i <- 0 to 6) { //value shifting
          data(i) := data(i + 1)
        }
      }

      // If we match on this cycle (pre-shift) the next bit will be AA but received in state preamble
      // This is why we try to match on a lookahead of the future data value
      when(Cat(io.in.data.bits, data.asUInt().apply(7, 1)) === preamble) {
        busy := true.B
        state := s_aa
        counter := 0.U
        counter_byte := 0.U
        data := 0.U(8.W).asBools()
      }
    }
  }.elsewhen (state === s_aa) {
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      val (stateOut, counterOut, counterByteOut) = stateUpdate(s_aa, s_pdu_header, 4.U, counter, counter_byte, in_fire)
      state := stateOut
      counter := counterOut
      counter_byte := counterByteOut

      in_ready := true.B
      out_valid := false.B

      when(in_fire) {
        data(counter_byte) := io.in.data.bits.asBool()
        when(counter_byte === 7.U) {
          // AA Flag
          val nextData = Cat(io.in.data.bits.asBool(), data.asUInt().apply(6, 0))
          for (i <- 0 to 3) {
            when(counter === i.U) {
              flag_aa := nextData =/= aa(8 * (i + 1) - 1, 8 * i) | flag_aa
            }
          }
        }
      }
    }
  }.elsewhen (state === s_pdu_header) {
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      when(in_fire) {
        data(counter_byte) := dewhite_result.asBool()
      }

      when(counter === 1.U && out_fire) {
        counter := 0.U
        counter_byte := 0.U
        in_ready := true.B
        out_valid := false.B

        when(data.asUInt() =/= 0.U) {
          state := s_pdu_payload
        }.otherwise { /// If we have a zero width PDU skip payload body
          state := s_crc
        }

        // Capture length field of PDU header
        length := data.asUInt()
      }.otherwise {
        when(out_fire) {
          counter := counter + 1.U
          in_ready := true.B
          out_valid := false.B
        }
        when(in_fire) {
          counter_byte := Mux(counter_byte === 7.U, 0.U, counter_byte + 1.U)

          when(counter_byte === 7.U) {
            in_ready := false.B
            out_valid := true.B
          }
        }
      }
    }
  }.elsewhen (state === s_pdu_payload) {
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      when(in_fire) {
        data(counter_byte) := dewhite_result.asBool()
      }

      when(counter === length - 1.U && out_fire) {
        state := s_crc
        counter := 0.U
        counter_byte := 0.U
        in_ready := true.B
        out_valid := false.B

      }.otherwise {
        when(out_fire) {
          counter := counter + 1.U
          in_ready := true.B
          out_valid := false.B
        }

        when(in_fire) {
          counter_byte := Mux(counter_byte === 7.U, 0.U, counter_byte + 1.U)
          when(counter_byte === 7.U) {
            in_ready := false.B
            out_valid := true.B
          }
        }
      }
    }
  }.elsewhen (state === s_crc) {
    when (io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
      in_ready := false.B
      out_valid := false.B

      length := 0.U
      flag_aa := false.B
      flag_crc := false.B

      counter := 0.U
      counter_byte := 0.U

      done := true.B
      busy := false.B
      data := 0.U(8.W).asBools()
      state := s_idle
    }.otherwise {
      // TODO: Remember the CRC is sent with ~*MSB*~ first, verify this
      val (stateOut, counterOut, counterByteOut) = stateUpdate(s_crc, s_idle, 3.U, counter, counter_byte, in_fire)
      state := stateOut
      counter := counterOut
      counter_byte := counterByteOut

      out_valid := false.B

      when(in_fire) {
        data(counter_byte) := dewhite_result.asBool() // TODO: This has been fixed, the CRC is also whitened
        when(counter_byte === 7.U) {
          // CRC Flag
          val nextData = Cat(dewhite_result.asBool(), data.asUInt().apply(6, 0))
          for (i <- 0 to 2) {
            when(counter === i.U) {
              flag_crc := nextData =/= crc_result(8 * (i + 1) - 1, 8 * i) | flag_crc
            }
          }
        }
      }

      when(stateOut === s_idle) {
        done := true.B
        busy := false.B
        data := 0.U(8.W).asBools()
      }
    }
  }.otherwise {
    state := s_idle
  }

  // CRC
  when (state === s_pdu_header || state === s_pdu_payload) { //check corner cases
    crc_data := dewhite_result
    crc_valid := in_fire
  }.otherwise {
    crc_data := 0.U
    crc_valid := false.B
  }


  // Dewhitening
  when (state === s_pdu_header || state === s_pdu_payload || state === s_crc) { //check corner cases
    dewhite_data  := io.in.data.bits
    dewhite_valid := in_fire
  }.otherwise {
    dewhite_data  := 0.U
    dewhite_valid := false.B
  }

  //crc instantiate
  val crc = Module(new CRC)

  crc.io.init := crc_reset
  crc.io.operand.bits := crc_data
  crc.io.operand.valid := crc_valid
  crc_result := crc.io.result
  crc.io.seed := crc_seed

  //whitening instantiate
  val white = Module(new Whitening)

  white.io.init := dewhite_reset
  white.io.operand.bits := dewhite_data
  white.io.operand.valid := dewhite_valid
  dewhite_result := white.io.result.bits
  white.io.seed := dewhite_seed

}