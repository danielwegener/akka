/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.InetSocketAddress
import java.nio.channels.{ SelectionKey, DatagramChannel }
import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.io.Udp.{ CommandFailed, Send }
import akka.io.SelectionHandler._

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[io] trait WithUdpSend {
  me: Actor with ActorLogging ⇒

  private var pendingSend: Send = null
  private var pendingCommander: ActorRef = null
  // If send fails first, we allow a second go after selected writable, but no more. This flag signals that
  // pending send was already tried once.
  private var retriedSend = false
  private def hasWritePending = pendingSend ne null

  def channel: DatagramChannel
  def udp: UdpExt
  val settings = udp.settings

  import settings._

  def sendHandlers(registration: ChannelRegistration): Receive = {
    case send: Send if hasWritePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender() ! CommandFailed(send)

    case send: Send if send.payload.isEmpty ⇒
      if (send.wantsAck)
        sender() ! send.ack

    case send: Send ⇒
      pendingSend = send
      pendingCommander = sender()
      try {
        if (send.target.isUnresolved) {
          Dns.resolve(send.target.getHostName)(context.system, self) match {
            case Some(r) ⇒
              pendingSend = pendingSend.copy(target = new InetSocketAddress(r.addr, pendingSend.target.getPort))
              doSend(registration)
            case None ⇒
              log.debug("Failure while sending UDP datagram to remote address [{}]: Host is unresolved",
                pendingSend.target)
              pendingCommander ! CommandFailed(pendingSend)
              resetPending()
          }
        } else {
          doSend(registration)
        }
      } catch {
        case NonFatal(e) ⇒
          log.debug("Failure while sending UDP datagram to remote address [{}]: {}", pendingSend.target, e)
          pendingCommander ! CommandFailed(pendingSend)
          resetPending()
      }

    case ChannelWritable ⇒ if (hasWritePending)
      try {
        doSend(registration)
      } catch {
        case NonFatal(e) ⇒
          log.debug("Failure while sending UDP datagram to remote address [{}]: {}", pendingSend.target, e)
          pendingCommander ! CommandFailed(pendingSend)
          resetPending()
      }
  }

  @inline
  private def resetPending():Unit = {
    retriedSend = false
    pendingSend = null
    pendingCommander = null
  }

  private def doSend(registration: ChannelRegistration): Unit = {
    val buffer = udp.bufferPool.acquire()
    try {
      buffer.clear()
      pendingSend.payload.copyToBuffer(buffer)
      buffer.flip()
      val writtenBytes = channel.send(buffer, pendingSend.target)
      if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

      // Datagram channel either sends the whole message, or nothing
      if (writtenBytes == 0) {
        if (retriedSend) {
          pendingCommander ! CommandFailed(pendingSend)
          retriedSend = false
          pendingSend = null
          pendingCommander = null
        } else {
          registration.enableInterest(SelectionKey.OP_WRITE)
          retriedSend = true
        }
      } else {
        if (pendingSend.wantsAck) pendingCommander ! pendingSend.ack
        retriedSend = false
        pendingSend = null
        pendingCommander = null
      }
    } finally {
      udp.bufferPool.release(buffer)
    }
  }
}
