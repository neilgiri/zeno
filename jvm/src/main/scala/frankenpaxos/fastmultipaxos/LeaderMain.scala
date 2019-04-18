package frankenpaxos.fastmultipaxos

import ThriftySystem.Flags.thriftySystemTypeRead
import frankenpaxos.Actor
import frankenpaxos.NettyTcpAddress
import frankenpaxos.NettyTcpTransport
import frankenpaxos.PrintLogger
import frankenpaxos.statemachine
import frankenpaxos.statemachine.Flags.stateMachineTypeRead
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import java.io.File
import scala.concurrent.duration

object LeaderMain extends App {
  case class Flags(
      // Basic flags.
      index: Int = -1,
      configFilename: File = new File("."),
      stateMachineType: statemachine.StateMachineType = statemachine.TRegister,
      // Monitoring.
      prometheusHost: String = "0.0.0.0",
      prometheusPort: Int = 8009,
      // Options.
      leaderOptions: LeaderOptions = LeaderOptions.default
  )

  val parser = new scopt.OptionParser[Flags]("") {
    help("help")

    // Basic flags.
    opt[Int]('i', "index")
      .required()
      .valueName("<index>")
      .action((x, f) => f.copy(index = x))
      .text("Leader index")

    opt[File]('c', "config")
      .required()
      .valueName("<file>")
      .action((x, f) => f.copy(configFilename = x))
      .text("Configuration file.")

    opt[statemachine.StateMachineType]('s', "state_machine")
      .valueName(statemachine.Flags.valueName)
      .action((x, f) => f.copy(stateMachineType = x))
      .text(s"State machine type (default: ${Flags().stateMachineType})")

    // Monitoring.
    opt[String]("prometheus_host")
      .valueName("<host>")
      .action((x, f) => f.copy(prometheusHost = x))
      .text(s"Prometheus hostname (default: ${Flags().prometheusHost})")

    opt[Int]("prometheus_port")
      .valueName("<port>")
      .action((x, f) => f.copy(prometheusPort = x))
      .text(
        s"Prometheus port; -1 to disable (default: ${Flags().prometheusPort})"
      )

    // Options.
    opt[ThriftySystem.Flags.ThriftySystemType]("options.thriftySystem")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            thriftySystem = ThriftySystem.Flags.make(x)
          )
        )
      })
      .valueName(ThriftySystem.Flags.valueName)

    opt[duration.Duration]("options.resendPhase1asTimerPeriod")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            resendPhase1asTimerPeriod = java.time.Duration.ofNanos(x.toNanos)
          )
        )
      })

    opt[duration.Duration]("options.resendPhase2asTimerPeriod")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            resendPhase2asTimerPeriod = java.time.Duration.ofNanos(x.toNanos)
          )
        )
      })

    opt[duration.Duration]("options.election.pingPeriod")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            leaderElectionOptions = f.leaderOptions.leaderElectionOptions.copy(
              pingPeriod = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.election.noPingTimeoutMin")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            leaderElectionOptions = f.leaderOptions.leaderElectionOptions.copy(
              noPingTimeoutMin = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.election.noPingTimeoutMax")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            leaderElectionOptions = f.leaderOptions.leaderElectionOptions.copy(
              noPingTimeoutMax = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.election.notEnoughVotesTimeoutMin")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            leaderElectionOptions = f.leaderOptions.leaderElectionOptions.copy(
              notEnoughVotesTimeoutMin = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.election.notEnoughVotesTimeoutMax")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            leaderElectionOptions = f.leaderOptions.leaderElectionOptions.copy(
              notEnoughVotesTimeoutMax = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.heartbeat.failPeriod")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            heartbeatOptions = f.leaderOptions.heartbeatOptions.copy(
              failPeriod = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[duration.Duration]("options.heartbeat.successPeriod")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            heartbeatOptions = f.leaderOptions.heartbeatOptions.copy(
              successPeriod = java.time.Duration.ofNanos(x.toNanos)
            )
          )
        )
      })

    opt[Int]("options.heartbeat.numRetries")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            heartbeatOptions = f.leaderOptions.heartbeatOptions.copy(
              numRetries = x
            )
          )
        )
      })

    opt[Double]("options.heartbeat.networkDelayAlpha")
      .action((x, f) => {
        f.copy(
          leaderOptions = f.leaderOptions.copy(
            heartbeatOptions = f.leaderOptions.heartbeatOptions.copy(
              networkDelayAlpha = x
            )
          )
        )
      })
  }

  val flags: Flags = parser.parse(args, Flags()) match {
    case Some(flags) =>
      flags
    case None =>
      throw new IllegalArgumentException("Could not parse flags.")
  }

  val logger = new PrintLogger()
  val transport = new NettyTcpTransport(logger)
  val config = ConfigUtil.fromFile(flags.configFilename.getAbsolutePath())
  val address = config.leaderAddresses(flags.index)
  val stateMachine = statemachine.Flags.make(flags.stateMachineType)
  val server = new Leader[NettyTcpTransport](address,
                                             transport,
                                             logger,
                                             config,
                                             stateMachine,
                                             flags.leaderOptions)

  if (flags.prometheusPort != -1) {
    DefaultExports.initialize()
    val prometheusServer =
      new HTTPServer(flags.prometheusHost, flags.prometheusPort)
    logger.info(
      s"Prometheus server running on ${flags.prometheusHost}:" +
        s"${flags.prometheusPort}"
    )
  } else {
    logger.info(
      s"Prometheus server not running because a port of -1 was given."
    )
  }
}
