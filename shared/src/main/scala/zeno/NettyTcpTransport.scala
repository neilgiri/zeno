package zeno

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelOutboundInvoker
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.ScheduledFuture
import java.net.SocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext
import scala.util.Try

case class NettyTcpAddress(socketAddress: SocketAddress) extends zeno.Address

// TODO(mwhittaker): Make constructor private.
class NettyTcpTimer(
    eventLoop: EventLoopGroup,
    logger: Logger,
    name: String,
    delay: java.time.Duration,
    f: () => Unit
) extends zeno.Timer {
  private var scheduledFuture: Option[ScheduledFuture[Unit]] = None

  override def name(): String = {
    name
  }

  override def start(): Unit = {
    scheduledFuture match {
      case Some(_) =>
        logger.warn(
          s"Attempting to start timer $name, but the timer is already started."
        );
      case None =>
        val callable = new Callable[Unit]() {
          override def call(): Unit = {
            scheduledFuture = None;
            f();
          }
        };
        scheduledFuture = Some(
          eventLoop.schedule(
            callable,
            delay.toNanos(),
            java.util.concurrent.TimeUnit.NANOSECONDS
          )
        );
    }
  }

  override def stop(): Unit = {
    scheduledFuture match {
      case Some(future) => future.cancel(false);
      case None =>
        logger.warn(
          s"Attempting to stop timer $name, but the timer is already stopped."
        );
    }
  }
}

class NettyTcpTransport(private val logger: Logger)
    extends Transport[NettyTcpTransport] {

  private class CommonHandler(protected val actor: Actor[NettyTcpTransport])
      extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
      val localAddress = NettyTcpAddress(ctx.channel.localAddress)
      val remoteAddress = NettyTcpAddress(ctx.channel.remoteAddress)
      // TODO(mwhittaker): Think about automatically deserializing into a proto.
      msg match {
        case bytes: Array[Byte] => {
          println("CURRENT THREAD NETTY RECEIVE")
          println(Thread.currentThread().getId())
          actor.receiveImpl(remoteAddress, bytes);
        }
        case _ =>
          val err = "A message was received that wasn't of type Array[Byte]. " +
            "This is impossible.";
          logger.error(err);
          throw new IllegalStateException(err);
      }
    }
  }

  private class ServerHandler(actor: Actor[NettyTcpTransport])
      extends CommonHandler(actor) {
    override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
      val localAddress = NettyTcpAddress(ctx.channel.localAddress());
      val remoteAddress = NettyTcpAddress(ctx.channel.remoteAddress());
      registerChannel(localAddress, remoteAddress, ctx.channel());
      ctx.fireChannelRegistered();
    }

    override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
      val localAddress = NettyTcpAddress(ctx.channel.localAddress());
      val remoteAddress = NettyTcpAddress(ctx.channel.remoteAddress());
      unregisterChannel(localAddress, remoteAddress);
      ctx.fireChannelUnregistered();
    }
  }

  private class ClientHandler(
      localAddress: NettyTcpTransport#Address,
      actor: Actor[NettyTcpTransport]
  ) extends CommonHandler(actor) {
    override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
      if (ctx.channel.localAddress() != null &&
          ctx.channel.remoteAddress() != null) {
        val remoteAddress = NettyTcpAddress(ctx.channel.remoteAddress());
        unregisterActor(NettyTcpAddress(ctx.channel.localAddress()));
        unregisterChannel(localAddress, remoteAddress);
        ctx.fireChannelUnregistered();
      } else {
        logger.warn(
          "Unregistering a channel with null source or destination " +
            "addresses. The connection must have failed."
        );
      }
    }
  }

  private class LogFailureFutureListener(message: String)
      extends ChannelFutureListener {
    override def operationComplete(future: ChannelFuture): Unit = {
      if (future.isSuccess()) {
        return;
      }

      if (future.isCancelled()) {
        logger.warn(s"Future was cancelled: $message");
      } else {
        logger.warn(s"Future failed: $message");
        logger.warn(future.cause().getStackTraceString);
      }
    }
  }

  private class CloseOnFailureFutureListener extends ChannelFutureListener {
    override def operationComplete(future: ChannelFuture): Unit = {
      if (!future.isSuccess()) {
        future.channel.close();
      }
    }
  }

  type Address = NettyTcpAddress
  type Timer = NettyTcpTimer

  // TODO(mwhittaker): The netty documentation [1] suggests using two event
  // loop groups: a "boss" group and a "worker" group. According to the
  // documentation, the boss group is responsible for establishing connections
  // while the worker group does the actual message processing. However, when
  // using two event loop groups and printing out thread ids, I found that
  // multiple threads were being used. I'm not exactly why this is happening. I
  // must be misunderstanding the netty API. Using a single event loop, though,
  // seems to solve the problem. With only one event loop, only one thread is
  // used.
  //
  // [1]: https://netty.io/wiki/user-guide-for-4.x.html
  private val eventLoop = new NioEventLoopGroup(1);

  private val actors =
    new HashMap[NettyTcpTransport#Address, Actor[NettyTcpTransport]]();
  private val channels =
    new HashMap[(NettyTcpTransport#Address, NettyTcpTransport#Address), Channel]

  private def registerActor(
      address: NettyTcpTransport#Address,
      actor: Actor[NettyTcpTransport]
  ): Unit = {
    if (actors.contains(address)) {
      // TODO(mwittaker): Replace with a fatal assertion.
      val err =
        s"Attempting to register an actor with address $address, but this " +
          s"transport already has an actor bound to $address.";
      logger.error(err);
      throw new IllegalStateException(err);
    }
    actors.put(address, actor);
  }

  private def unregisterActor(
      address: NettyTcpTransport#Address
  ): Unit = {
    actors.remove(address) match {
      case Some(_) => {}
      case None    =>
        // TODO(mwittaker): Replace with a fatal assertion.
        val err =
          s"Attempting to unregister an actor with address $address, but " +
            s"this transport doesn't have an actor bound to $address.";
        logger.error(err);
        throw new IllegalStateException(err);
    }
  }

  private def registerChannel(
      localAddress: NettyTcpTransport#Address,
      remoteAddress: NettyTcpTransport#Address,
      channel: Channel
  ): Unit = {
    if (channels.contains((localAddress, remoteAddress))) {
      // TODO(mwhittaker): Replace with fatal assertion.
      val err = s"A channel between remote address $remoteAddress and " +
        s"local address $localAddress is being registered, but an existing " +
        s"channel between these addresses already exists.";
      logger.error(err)
      throw new IllegalStateException(err);
    }
    channels.put((localAddress, remoteAddress), channel);
  }

  private def unregisterChannel(
      localAddress: NettyTcpTransport#Address,
      remoteAddress: NettyTcpTransport#Address
  ): Unit = {
    channels.remove((localAddress, remoteAddress)) match {
      case Some(_) => {}
      case None    =>
        // TODO(mwhittaker): Replace with fatal assertion.
        val err =
          s"A channel between remote address $remoteAddress and local " +
            s"address $localAddress is being unregistered, but an existing " +
            s"channel between these addresses doesn not exists.";
        logger.error(err)
        throw new IllegalStateException(err);
    }
  }

  def register(
      address: NettyTcpTransport#Address,
      actor: Actor[NettyTcpTransport]
  ): Unit = {
    registerActor(address, actor);

    new ServerBootstrap()
      .group(eventLoop)
      .channel(classOf[NioServerSocketChannel])
      .option[Integer](ChannelOption.SO_BACKLOG, 128)
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
      .childHandler(new ChannelInitializer[SocketChannel]() {
        @throws(classOf[Exception])
        override def initChannel(channel: SocketChannel): Unit = {
          logger.info(
            s"Server socket on address ${channel.localAddress} established " +
              s"connection with ${channel.remoteAddress}."
          );
          channel.pipeline.addLast(
            "frameDecoder",
            new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
          );
          channel.pipeline.addLast("bytesDecoder", new ByteArrayDecoder());
          channel.pipeline.addLast(new ServerHandler(actor));
          // channel.pipeline.addLast(new LoggingHandler());
          channel.pipeline.addLast("frameEncoder", new LengthFieldPrepender(4));
          channel.pipeline.addLast("bytesEncoder", new ByteArrayEncoder());
        }
      })
      .bind(address.socketAddress)
      .addListeners(
        new LogFailureFutureListener(
          s"A socket could not be bound to ${address.socketAddress}."
        ),
        new CloseOnFailureFutureListener()
      );
  }

  def send(
      src: NettyTcpTransport#Address,
      dst: NettyTcpTransport#Address,
      bytes: Array[Byte]
  ): Unit = {
    val actor = actors.get(src) match {
      case Some(actor) => actor
      case None =>
        logger.warn(
          s"Attempted to send from $src to $dst, but no actor for address " +
            s"$src was found."
        );
        return;
    }

    channels.get((src, dst)) match {
      case Some(channel) => {
        logger.info("Existing channel found for write")
        channel
          .writeAndFlush(bytes)
          .addListener(
            new LogFailureFutureListener(
              s"Unable to send message from $src to $dst."
            )
          )
      }
      case None => {
        new Bootstrap()
          .group(eventLoop)
          .channel(classOf[NioSocketChannel])
          .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
          .handler(new ChannelInitializer[SocketChannel]() {
            @throws(classOf[Exception])
            override def initChannel(channel: SocketChannel) {
              channel.pipeline.addLast(
                "frameDecoder",
                new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
              );
              channel.pipeline
                .addLast("bytesDecoder", new ByteArrayDecoder());
              channel.pipeline.addLast(new ClientHandler(src, actor));
              // channel.pipeline.addLast(new LoggingHandler());
              channel.pipeline
                .addLast("frameEncoder", new LengthFieldPrepender(4));
              channel.pipeline
                .addLast("bytesEncoder", new ByteArrayEncoder());
            }
          })
          .connect(dst.socketAddress)
          .addListeners(
            new LogFailureFutureListener(s"Unable to connect to $dst."),
            new CloseOnFailureFutureListener(),
            new ChannelFutureListener() {
              override def operationComplete(future: ChannelFuture): Unit = {
                if (!future.isSuccess()) {
                  return;
                }

                logger.info(
                  s"Client socket on address " +
                    s"${future.channel.localAddress} established " +
                    s"connection with ${future.channel.remoteAddress}."
                );

                registerActor(
                  NettyTcpAddress(future.channel.localAddress),
                  actor
                );
                registerChannel(src, dst, future.channel);

                future.channel
                  .writeAndFlush(bytes)
                  .addListener(
                    new LogFailureFutureListener(
                      s"Unable to send message from $src to $dst."
                    )
                  );
              }
            }
          );
      }
    }
  }

  def timer(
      address: NettyTcpTransport#Address,
      name: String,
      delay: java.time.Duration,
      f: () => Unit
  ): NettyTcpTransport#Timer = {
    new NettyTcpTimer(eventLoop, logger, name, delay, f)
  }

  override def executionContext(): ExecutionContext = {
    scala.concurrent.ExecutionContext.fromExecutorService(eventLoop)
  }
}
