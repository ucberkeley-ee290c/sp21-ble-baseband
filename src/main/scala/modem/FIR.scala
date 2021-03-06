//// See LICENSE for license details.
//
package modem

import chisel3._
import chisel3.util._
import dsptools.numbers._
import chisel3.experimental.FixedPoint

object FIRCodes {
  val NONE = 0.U
  val RX_HILBERT_FILTER = 1.U
  val RX_BANDPASS_F0 = 2.U
  val RX_BANDPASS_F1 = 3.U
  val RX_ENVELOPE = 4.U
  val TX_GAUSSIAN = 5.U
}

class FIRCoefficientChangeCommand extends Bundle {
  /* LUT Command: used to write to a LUT within the modem.
    [31:10 - Value to be written to LUT | 9:4 - address in LUT | 3:0 - LUT index]
   */
  val FIR = UInt(4.W)
  val change = new FIRCoefficientChange
}

class FIRCoefficientChange extends Bundle {
  val coeff = UInt(6.W)
  val value = UInt(22.W)
}

class GenericFIRCellBundle(genIn:FixedPoint, genOut:FixedPoint) extends Bundle {
  val data: FixedPoint = genIn.cloneType
  val carry: FixedPoint = genOut.cloneType

  override def cloneType: this.type = GenericFIRCellBundle(genIn, genOut).asInstanceOf[this.type]
}
object GenericFIRCellBundle {
  def apply(genIn:FixedPoint, genOut:FixedPoint): GenericFIRCellBundle = new GenericFIRCellBundle(genIn, genOut)
}

class GenericFIRCellIO(genIn:FixedPoint, genOut:FixedPoint, c:FixedPoint) extends Bundle {
  val coeff = Input(c.cloneType)
  val in = Flipped(Decoupled(GenericFIRCellBundle(genIn, genOut)))
  val out = Decoupled(GenericFIRCellBundle(genIn, genOut))
}
object GenericFIRCellIO {
  def apply(genIn:FixedPoint, genOut:FixedPoint, c:FixedPoint): GenericFIRCellIO = new GenericFIRCellIO(genIn, genOut, c)
}

class GenericFIRBundle(proto: FixedPoint) extends Bundle {
  val data: FixedPoint = proto.cloneType

  override def cloneType: this.type = GenericFIRBundle(proto).asInstanceOf[this.type]
}
object GenericFIRBundle {
  def apply(proto: FixedPoint): GenericFIRBundle = new GenericFIRBundle(proto)
}

class GenericFIRIO(genIn:FixedPoint, genOut:FixedPoint) extends Bundle {
  val in = Flipped(Decoupled(GenericFIRBundle(genIn)))
  val out = Decoupled(GenericFIRBundle(genOut))
  val coeff = Flipped(Valid(new FIRCoefficientChange))
}
object GenericFIRIO {
  def apply(genIn:FixedPoint, genOut:FixedPoint): GenericFIRIO = new GenericFIRIO(genIn, genOut)
}

class GenericFIR(genIn:FixedPoint, genOut:FixedPoint, coeffs: Seq[FixedPoint]) extends Module {
  val io = IO(GenericFIRIO(genIn, genOut))

  // Construct a vector of genericFIRDirectCells
  val directCells = Seq.tabulate(coeffs.length){ i: Int => Module(new GenericFIRDirectCell(genIn, genOut, coeffs(i))).io }

  val coeffRegs = RegInit(VecInit(coeffs))

  when (io.coeff.fire()) {
    coeffRegs(io.coeff.bits.coeff) := io.coeff.bits.value(coeffs.head.getWidth - 1, 0).asFixedPoint(coeffs.head.binaryPoint)
  }

  // Construct the direct FIR chain
  for ((cell, i) <- directCells.zip(coeffs.indices)) {
    cell.coeff := coeffRegs(i)
  }

  // Connect input to first cell
  directCells.head.in.bits.data := io.in.bits.data
  directCells.head.in.bits.carry := Ring[FixedPoint].zero
  directCells.head.in.valid := io.in.valid
  io.in.ready := directCells.head.in.ready

  // Connect adjacent cells
  // Note that .tail() returns a collection that consists of all
  // elements in the inital collection minus the first one.
  // This means that we zip together directCells[0, n] and
  // directCells[1, n]. However, since zip ignores unmatched elements,
  // the resulting zip is (directCells[0], directCells[1]) ...
  // (directCells[n-1], directCells[n])
  for ((current, next) <- directCells.zip(directCells.tail)) {
    next.in.bits := current.out.bits
    next.in.valid := current.out.valid
    current.out.ready := next.in.ready
  }

  // Connect output to last cell
  io.out.bits.data := directCells.last.out.bits.carry
  directCells.last.out.ready := io.out.ready
  io.out.valid := directCells.last.out.valid

}

// A generic FIR direct cell used to construct a larger direct FIR chain
//
//   in ----- [z^-1]-- out
//	        |
//   coeff ----[*]
//	        |
//   carryIn --[+]-- carryOut
//
// DOC include start: GenericFIRDirectCell chisel
class GenericFIRDirectCell(genIn: FixedPoint, genOut: FixedPoint, c: FixedPoint) extends Module {
  val io = IO(GenericFIRCellIO(genIn, genOut, c))

  // Registers to delay the input and the valid to propagate with calculations
  val hasNewData = RegInit(0.U)
  val inputReg = Reg(genIn.cloneType)

  // Passthrough ready
  io.in.ready := io.out.ready

  // When a new transaction is ready on the input, we will have new data to output
  // next cycle. Take this data in
  when (io.in.fire()) {
    hasNewData := 1.U
    inputReg := io.in.bits.data
  }

  // We should output data when our cell has new data to output and is ready to
  // receive new data. This insures that every cell in the chain passes its data
  // on at the same time
  io.out.valid := hasNewData & io.in.fire()
  io.out.bits.data := inputReg

  // Compute carry
  // This uses the ring implementation for + and *, i.e.
  // (a * b) maps to (Ring[T].prod(a, b)) for whicever T you use
  io.out.bits.carry := inputReg * io.coeff + io.in.bits.carry
}

class FixedPointFIRTransposeCellOutputIO(genIn: FixedPoint, genOut: FixedPoint) extends Bundle {
  val sample = Decoupled(genIn.cloneType)
  val sum = Decoupled(genOut.cloneType)

  override def cloneType: this.type = (new FixedPointFIRTransposeCellOutputIO(genIn, genOut)).asInstanceOf[this.type]
}

class FixedPointFIRTransposeCellIO(genIn:FixedPoint, genOut:FixedPoint, c:FixedPoint) extends Bundle {
  val coeff = Input(c.cloneType)
  val in = Flipped(new FixedPointFIRTransposeCellOutputIO(genIn, genOut))
  val out = new FixedPointFIRTransposeCellOutputIO(genIn, genOut)
}

class FixedPointFIRTransposeCell(genIn: FixedPoint, genOut: FixedPoint, c: FixedPoint) extends Module {
  val io = IO(new FixedPointFIRTransposeCellIO(genIn, genOut, c))

  val sumValid = RegInit(false.B)
  val sumReg = Reg(genOut.cloneType)

  when(io.in.sample.fire() & io.in.sum.fire()) {
    sumReg :=  io.in.sample.bits * io.coeff + io.in.sum.bits
    sumValid := true.B
  }

  // Sample signals
  io.in.sample.ready := io.out.sample.ready
  io.out.sample.valid := io.in.sample.valid
  io.out.sample.bits := io.in.sample.bits

  // Sum signals
  io.in.sum.ready := io.out.sum.ready
  io.out.sum.valid := sumValid & io.in.sum.valid
  io.out.sum.bits := sumReg
}

class FixedPointTransposeFIRIO(genIn: FixedPoint, genOut: FixedPoint, coeffIn: FixedPoint, n: Int) extends Bundle {
  val in = Flipped(Decoupled(genIn.cloneType))
  val out = Decoupled(genOut.cloneType)
  val coeff = Input(Vec(n, coeffIn))
}

class FixedPointTransposeFIR(genIn: FixedPoint, genOut: FixedPoint, coeffIn: FixedPoint, coeffsN: Int) extends Module {
  val io = IO(new FixedPointTransposeFIRIO(genIn, genOut, coeffIn, coeffsN))

  val transposeCells = Seq.tabulate(coeffsN){ i: Int => Module(new FixedPointFIRTransposeCell(genIn, genOut, coeffIn)).io }

  transposeCells.zip(io.coeff.indices.reverse) // Reverse coeff order for transpose FIR
    .foreach { case (cell, i) =>
      cell.coeff := io.coeff(i)
    }

  transposeCells.head.in.sample <> io.in

  transposeCells.head.in.sum.valid := io.in.valid
  transposeCells.head.in.sum.bits := Ring[FixedPoint].zero

  transposeCells.zip(transposeCells.tail)
    .foreach{ case (current, next ) =>
      next.in.sample <> current.out.sample
      next.in.sum <> current.out.sum
    }

  transposeCells.last.out.sample.ready := io.out.ready

  io.out <> transposeCells.last.out.sum

}

class ConstantFixedPointTransposeFIRIO(genIn: FixedPoint, genOut: FixedPoint) extends Bundle {
  val in = Flipped(Decoupled(genIn.cloneType))
  val out = Decoupled(genOut.cloneType)
}

class ConstantFixedPointTransposeFIR(genIn: FixedPoint, genOut: FixedPoint, coeffs: Seq[FixedPoint]) extends Module {
  val io = IO(new ConstantFixedPointTransposeFIRIO(genIn, genOut))

  val transposeCells = Seq.tabulate(coeffs.length){ i: Int => Module(new FixedPointFIRTransposeCell(genIn, genOut, coeffs(i))).io }

  transposeCells.zip(coeffs.reverse) // Reverse coeff order for transpose FIR
    .foreach { case (cell, coeff) =>
      cell.coeff := coeff
    }

  transposeCells.head.in.sample <> io.in

  transposeCells.head.in.sum.valid := io.in.valid
  transposeCells.head.in.sum.bits := Ring[FixedPoint].zero

  transposeCells.zip(transposeCells.tail)
    .foreach{ case (current, next ) =>
      next.in.sample <> current.out.sample
      next.in.sum <> current.out.sum
    }

  transposeCells.last.out.sample.ready := io.out.ready

  io.out <> transposeCells.last.out.sum

}