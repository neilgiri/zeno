package frankenpaxos.fastmultipaxos

// Every Fast Paxos instance is associated with a set of integer-valued rounds.
// For every round r, we must asign r a unique leader, and we must classify r
// as a classic round or fast round. Moreover, every leader must be assigned an
// infinite number of rounds. A RoundSystem is such an assignment. Note that
// the name "round system" is a play on quorum systems [1].
//
// [1]: http://vukolic.com/QuorumsOrigin.pdf
sealed trait RoundType
case object ClassicRound extends RoundType
case object FastRound extends RoundType

trait RoundSystem {
  // Rounds are integer-valued. It is also common for rounds to be of the form
  // (a, i) where a is the address of a leader and i is an integer. Here, we
  // let rounds be integers to keep things simple.
  type Round = Int

  // A RoundSystem with n leaders assumes each leader is given a unique index
  // in the range [0, n).
  type LeaderIndex = Int

  // The number of leaders that this round system is designed for.
  def numLeaders(): Int

  // The leader of round `round`.
  def leader(round: Round): LeaderIndex

  // The type of round `round`.
  def roundType(round: Round): RoundType

  // The smallest classic round for `leaderIndex` greater than `round`. Every
  // leader is required to have an infinite number of classic rounds, so
  // nextClassicRound will always return a round. If round is less than 0, then
  // nextClassicRound returns the first classic round for `leaderIndex`.
  def nextClassicRound(leaderIndex: LeaderIndex, round: Round): Round

  // The smallest fast round for `leaderIndex` greater than `round`. Every
  // leader is required to have an infinite number of classic rounds, but it is
  // NOT guaranteed to have an infinite number of fast rounds. Thus,
  // nextFastRound only optionally returns a fast round. If round is less than
  // 0, then nextFastRound returns the first fast round for `leaderIndex`.
  def nextFastRound(leaderIndex: LeaderIndex, round: Round): Option[Round]
}

object RoundSystem {
  // A ClassicRoundRobin round system assigns classic rounds to leaders
  // round-robin. There are no fast rounds. Here's an example with n = 3:
  //
  //                       | Round | Leader | Round Type |
  //                       +-------+--------+------------+
  //                       | 0     | 0      | classic    |
  //                       | 1     | 1      | classic    |
  //                       | 2     | 2      | classic    |
  //                       | 3     | 0      | classic    |
  //                       | 4     | 1      | classic    |
  //                       | 5     | 2      | classic    |
  //                       | 6     | 0      | classic    |
  class ClassicRoundRobin(private val n: Int) extends RoundSystem {
    override def toString(): String = s"ClassicRoundRobin($n)"
    override def numLeaders(): Int = n
    override def leader(round: Round): LeaderIndex = round % n
    override def roundType(round: Round): RoundType = ClassicRound

    override def nextClassicRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Round = {
      if (round < 0) {
        leaderIndex
      } else {
        val smallestMultipleOfN = n * (round / n)
        val offset = leaderIndex % n
        if (smallestMultipleOfN + offset > round) {
          smallestMultipleOfN + offset
        } else {
          smallestMultipleOfN + n + offset
        }
      }
    }

    override def nextFastRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Option[Round] = None
  }

  // A RoundZeroFast round system assigns rounds round-robin. Round 0 is fast,
  // and all other rounds are classic. Here's an example with n = 3:
  //
  //                       | Round | Leader | Round Type |
  //                       +-------+--------+------------+
  //                       | 0     | 0      | fast       |
  //                       | 1     | 1      | classic    |
  //                       | 2     | 2      | classic    |
  //                       | 3     | 0      | classic    |
  //                       | 4     | 1      | classic    |
  //                       | 5     | 2      | classic    |
  //                       | 6     | 0      | classic    |
  class RoundZeroFast(private val n: Int) extends RoundSystem {
    override def toString(): String = s"RoundZeroFast($n)"
    override def numLeaders(): Int = n
    override def leader(round: Round): LeaderIndex = round % n

    override def roundType(round: Round): RoundType = {
      if (round == 0) FastRound else ClassicRound
    }

    override def nextClassicRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Round = {
      if (leaderIndex == 0 && round < 0) {
        n
      } else {
        new ClassicRoundRobin(n).nextClassicRound(leaderIndex, round)
      }
    }

    override def nextFastRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Option[Round] = {
      if (leaderIndex == 0 && round < 0) Some(0) else None
    }
  }

  // A MixedRoundRobin round system assigns pairs of contiguous fast and
  // classic rounds round-robin. Here's an example with n = 3:
  //
  //                       | Round | Leader | Round Type |
  //                       +-------+--------+------------+
  //                       | 0     | 0      | fast       |
  //                       | 1     | 0      | classic    |
  //                       | 2     | 1      | fast       |
  //                       | 3     | 1      | classic    |
  //                       | 4     | 2      | fast       |
  //                       | 5     | 2      | classic    |
  //                       | 6     | 0      | fast       |
  //                       | 7     | 0      | classic    |
  //                       | 8     | 1      | fast       |
  //                       | 9     | 1      | classic    |
  class MixedRoundRobin(private val n: Int) extends RoundSystem {
    override def toString(): String = s"MixedRoundRobin($n)"
    override def numLeaders(): Int = n
    override def leader(round: Round): LeaderIndex = (round / 2) % n

    override def roundType(round: Round): RoundType = {
      if (round % 2 == 0) FastRound else ClassicRound
    }

    override def nextClassicRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Round = {
      // If round is a fast round of leaderIndex, then the next classic round
      // is the next round. Otherwise, the next classic round is the round
      // after the next fast round.
      if (round / 2 % n == leaderIndex && round % 2 == 0) {
        round + 1
      } else {
        nextFastRound(leaderIndex, round).get + 1
      }
    }

    override def nextFastRound(
        leaderIndex: LeaderIndex,
        round: Round
    ): Option[Round] = {
      if (round < 0) {
        Some(leaderIndex * 2)
      } else {
        Some(
          new ClassicRoundRobin(n).nextClassicRound(leaderIndex, round / 2) * 2
        )
      }
    }
  }
}
