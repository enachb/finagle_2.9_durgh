package com.twitter.finagle.channel

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.nio.channels.NotYetConnectedException

import org.jboss.netty.channel._
import org.jboss.netty.channel.local.LocalAddress

import com.twitter.finagle.util.{Ok, Error, Cancelled, Serialized}
import com.twitter.finagle.util.Conversions._

class TooManyDicksOnTheDanceFloorException extends Exception

class BrokeredChannel(
  factory: BrokeredChannelFactory,
  pipeline: ChannelPipeline,
  sink: ChannelSink)
  extends AbstractChannel(null/* parent */, factory, pipeline, sink)
{
  val config = new DefaultChannelConfig
  private val localAddress = new LocalAddress(LocalAddress.EPHEMERAL)
  @volatile private var broker: Option[Broker] = None
  private var waitingForReply: Option[ReplyFuture] = None

  val serializer = new Serialized

  protected[channel] def realConnect(broker: Broker, future: ChannelFuture) = serializer {
    this.broker = Some(broker)
    future.setSuccess()
    Channels.fireChannelConnected(this, broker)
    Channels.fireChannelBound(this, broker)
  }

  protected[channel] def realClose(future: ChannelFuture) = serializer {
    for (reply <- waitingForReply)
      reply.cancel()

    waitingForReply = None

    setClosed()
    Channels.fireChannelClosed(this)
    if (broker.isDefined) {
      Channels.fireChannelDisconnected(this)
      Channels.fireChannelUnbound(this)
      broker = None
    }
  }

  protected[channel] def realWrite(e: MessageEvent): Unit = serializer {
    broker match {
      case Some(broker) if !waitingForReply.isDefined =>
        val replyFuture = broker.dispatch(e)
        waitingForReply = Some(replyFuture)

        e.getFuture() { serializer {
          case Ok(_) if this.isOpen =>
            Channels.fireWriteComplete(this, 1)
          case Error(cause) if isOpen =>
            Channels.fireExceptionCaught(this, cause)
          case _ => ()
        }}

        proxyMessages(replyFuture)

      case Some(_) if waitingForReply.isDefined =>
        Channels.fireExceptionCaught(this, new TooManyDicksOnTheDanceFloorException)

      case _ =>
        e.getFuture.setFailure(new NotYetConnectedException)
    }

    ()
  }

  def proxyMessages(replyFuture: ReplyFuture): Unit =
    replyFuture { state => serializer {
      if (!isOpen)  // ignore closed channels.
        return

      state match {
        case Ok(_) =>
          replyFuture.getReply match {
            case Reply.Done(message) =>
              Channels.fireMessageReceived(this, message)
              waitingForReply = None
            case Reply.More(message, next) =>
              Channels.fireMessageReceived(this, message)
              waitingForReply = Some(next)
              proxyMessages(next)
          }

        case Error(cause) =>
          Channels.fireExceptionCaught(this, cause)
          waitingForReply = None

        case Cancelled =>
          // XXXTODO
      }
    }}

  def getRemoteAddress = broker.getOrElse(null)
  def getLocalAddress = if (broker.isDefined) localAddress else null

  def isConnected = broker.isDefined
  def isBound = broker.isDefined
  def getConfig = config
}
