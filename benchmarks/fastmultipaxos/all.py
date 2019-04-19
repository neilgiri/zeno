from .fastmultipaxos import *


def _main(args) -> None:
    inputs = [
        Input(
            net_name='SingleSwitchNet',
            f=1,
            num_clients=num_clients,
            num_threads_per_client=1,
            round_system_type=round_system_type,

            duration_seconds=20,
            timeout_seconds=120,
            client_lag_seconds=5,
            profiled=args.profile,
            monitored=args.monitor,
            prometheus_scrape_interval_ms=200,

            acceptor = AcceptorOptions()._replace(
                wait_period_ms = wait_period_ms,
                wait_stagger_ms = wait_stagger_ms,
            ),

            leader = LeaderOptions()._replace(
                thrifty_system = ThriftySystemType.NOT_THRIFTY,
            ),

            client = ClientOptions()._replace(
                repropose_period_ms=repropose_period_ms,
            ),
        )
        for num_clients in [1, 10, 20, 30]
        for round_system_type in [
            RoundSystemType.CLASSIC_ROUND_ROBIN.name,
            RoundSystemType.MIXED_ROUND_ROBIN.name
        ]
        for (wait_period_ms, wait_stagger_ms) in (
            [(0., 0.)]
            if round_system_type == RoundSystemType.CLASSIC_ROUND_ROBIN.name
            else [(0.01, 0.), (0.1, 0.), (1, 0.), (10, 0.)]
        )
        for repropose_period_ms in [max(50, wait_period_ms * 2)]
    ] * 3

    def make_net(input) -> FastMultiPaxosNet:
        return SingleSwitchNet(
            f=input.f,
            num_clients=input.num_clients,
            rs_type = RoundSystemType[input.round_system_type]
        )

    run_suite(args, inputs, make_net)


if __name__ == '__main__':
    _main(get_parser().parse_args())
