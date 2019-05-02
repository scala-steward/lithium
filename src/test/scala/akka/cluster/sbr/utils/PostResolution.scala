package akka.cluster.sbr.utils

import akka.cluster.Member
import akka.cluster.sbr._
import cats.Monoid
import cats.implicits._

/**
 * Represents the partitions after running the decision.
 */
final case class PostResolution(partitions: List[Set[Member]]) {

  /**
   * True if there are not enough partitions for a split-brain
   * or that all the partitions have the same members.
   */
  lazy val noSplitBrain: Boolean = {
    val nonEmptyPartitions = partitions.filter(_.nonEmpty)
    nonEmptyPartitions.size <= 1 || nonEmptyPartitions.tail
      .foldLeft((true, nonEmptyPartitions.head)) {
        case ((b, expectedPartition), partition) => (expectedPartition == partition && b, expectedPartition)
      }
      ._1
  }
}

object PostResolution {
  def fromDecision(worldView: WorldView)(decision: StrategyDecision): PostResolution =
    decision match {
      case DownReachable(_) => PostResolution(List(Set.empty))
      case DownSelf(_)      => PostResolution(List(Set.empty))

      case DownThese(decision1, decision2) =>
        val v = fromDecision(worldView)(decision1) |+| fromDecision(worldView)(decision2)
        v.copy(partitions = List(v.partitions.head.union(v.partitions.last)))

      case decision =>
        PostResolution(List(worldView.nodes.map(_.member) -- decision.nodesToDown.map(_.member)))
    }

  implicit val remainingPartitionsMonoid: Monoid[PostResolution] = new Monoid[PostResolution] {
    override def empty: PostResolution = PostResolution(List.empty)

    override def combine(x: PostResolution, y: PostResolution): PostResolution =
      PostResolution(x.partitions ++ y.partitions)
  }
}
