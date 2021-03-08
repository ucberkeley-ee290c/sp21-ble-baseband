package baseband

import chisel3._
import chisel3.util._

object BasebandISA {
  // primaryInst values and corresponding instructions

  /* Configure command:
      Configure baseband constants.
      [ Data = X | secondaryInst = <target constant> | primaryInst = 0 ]
      [ additionalData = <value> ]
   */
  val CONFIG_CMD = 0.U

  // secondaryInst values for CONFIG_CMD
  val CONFIG_CRC_SEED = 0.U
  val CONFIG_WHITENING_SEED = 1.U
  val CONFIG_ACCESS_ADDRESS = 2.U
  val CONFIG_CHANNEL_INDEX = 3.U
  val CONFIG_ADDITIONAL_FRAME_SPACE = 4.U
  val CONFIG_LOOPBACK_SELECT = 5.U

  /* Send command:
      Transmit a specified number of PDU header and data bytes
      [ Data = <total bytes> | secondaryInst = X | primaryInst = 1 ]
      [ additionalData = x ]
   */
  val SEND_CMD = 1.U

  /* Send command:
      Place the device into receive mode. If a message is picked up, it will be stored starting at
      the specified storage address.
      [ Data = <total bytes> | secondaryInst = X | primaryInst = 2 ]
      [ additionalData = <storage address> ]
 */
  val RECEIVE_CMD = 2.U

  // Interrupt reason codes
  val INTERRUPT_REASON_INVALID_TX_LENGTH = 0

  def INTERRUPT(reason: Int, message: Int = 0): UInt = {
    Cat(message.U(26.W), reason.U(6.W))
  }
}