package akka.cluster.sbr

import akka.actor.Address
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.cluster.sbr.SBRFailureDetector.{IndirectlyConnected, Reachable, SBRReachability, Unreachable}
import akka.cluster.{Member, MemberStatus, UniqueAddress, Reachability => _}
import cats.Order
import cats.data.{NonEmptyMap, NonEmptySet}
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen._
import shapeless.tag
import shapeless.tag.@@

import scala.collection.immutable.{SortedMap, SortedSet}

object ArbitraryInstances extends ArbitraryInstances

trait ArbitraryInstances extends ArbitraryInstances0 {
  sealed trait JoiningTag
  type JoiningMember = Member @@ JoiningTag

  sealed trait WeaklyUpTag
  type WeaklyUpMember = Member @@ WeaklyUpTag

  sealed trait UpTag
  type UpMember = Member @@ UpTag

  sealed trait LeavingTag
  type LeavingMember = Member @@ LeavingTag

  sealed trait ExitingTag
  type ExitingMember = Member @@ ExitingTag

  sealed trait DownTag
  type DownMember = Member @@ DownTag

  sealed trait RemovedTag
  type RemovedMember = Member @@ RemovedTag

  sealed trait HealthyTag
  type HealthyWorldView = WorldView @@ HealthyTag

  sealed trait UpNumberConsistentTag
  type UpNumberConsistentWorldView = WorldView @@ UpNumberConsistentTag

  implicit val arbJoiningMember: Arbitrary[JoiningMember] = Arbitrary {
    for {
      uniqueAddress <- arbitrary[UniqueAddress]
    } yield tag[JoiningTag][Member](Member(uniqueAddress, Set("dc-datacenter")))
  }

  implicit val arbWeaklyUpMember: Arbitrary[WeaklyUpMember] = Arbitrary(
    arbJoiningMember.arbitrary.map(m => tag[WeaklyUpTag][Member](m.copy(WeaklyUp)))
  )

  implicit val arbUpMember: Arbitrary[UpMember] = Arbitrary(
    arbJoiningMember.arbitrary.map(m => tag[UpTag][Member](m.copyUp(m.hashCode())))
  )

  implicit val arbLeavingMember: Arbitrary[LeavingMember] = Arbitrary(
    arbJoiningMember.arbitrary.map(m => tag[LeavingTag][Member](m.copy(Leaving)))
  )

  implicit val arbDownMember: Arbitrary[DownMember] = Arbitrary(
    arbJoiningMember.arbitrary.map(m => tag[DownTag][Member](m.copy(Down)))
  )

  implicit val arbRemovedMember: Arbitrary[RemovedMember] = Arbitrary(
    arbJoiningMember.arbitrary.map(m => tag[RemovedTag][Member](m.copy(Removed)))
  )

  implicit val arbExitingMember: Arbitrary[ExitingMember] = Arbitrary(
    arbLeavingMember.arbitrary.map(m => tag[ExitingTag][Member](m.copy(Exiting)))
  )

  implicit val arbMember: Arbitrary[Member] = Arbitrary(
    oneOf(
      arbJoiningMember.arbitrary,
      arbWeaklyUpMember.arbitrary,
      arbUpMember.arbitrary,
      arbLeavingMember.arbitrary,
      arbDownMember.arbitrary,
//      arbRemovedMember.arbitrary,
      arbExitingMember.arbitrary
    )
  )

  implicit val arbWorldView: Arbitrary[WorldView] = Arbitrary(
    for {
      selfNode <- arbitrary[Node]
      nodes    <- arbitrary[Set[Node]]
      nodes0 = nodes - selfNode
    } yield
      WorldView.fromNodes(ReachableNode(selfNode.member), Set.empty, nodes0.map(n => n -> Set.empty[Address]).toMap)
  )

  implicit val arbHealthyWorldView: Arbitrary[HealthyWorldView] = Arbitrary(
    for {
      selfNode <- arbitrary[ReachableNode]
      nodes    <- arbitrary[Set[ReachableNode]]
      nodes0    = nodes - selfNode
      worldView = WorldView.fromNodes(selfNode, Set.empty, nodes0.map(n => n -> Set.empty[Address]).toMap)
    } yield tag[HealthyTag][WorldView](worldView)
  )

  implicit val arbUpNumberConsistentWorldView: Arbitrary[UpNumberConsistentWorldView] = Arbitrary(
    for {
      selfNode <- arbitrary[WeaklyUpMember]
      nodes    <- arbitrary[Set[WeaklyUpMember]]
      nodes0 = nodes - selfNode

      selfNodeStatuses = selfNode.copyUp(0)
      nodes0Statuses = nodes0.toList.zipWithIndex.map {
        case (weaklyUpMember, ix) => ReachableNode(weaklyUpMember.copyUp(ix + 1))
      }.toSet

      worldView = WorldView.fromNodes(ReachableNode(selfNodeStatuses),
                                      Set.empty,
                                      nodes0Statuses.map(_ -> Set.empty[Address]).toMap)
    } yield tag[UpNumberConsistentTag][WorldView](worldView)
  )

  implicit val arbNode: Arbitrary[Node] =
    Arbitrary(oneOf(arbReachableNode.arbitrary, arbUnreachableNode.arbitrary, arbIndirectlyConnectedNode.arbitrary))

  implicit val arbReachableNode: Arbitrary[ReachableNode] =
    Arbitrary(arbMember.arbitrary.map(ReachableNode(_)))

  implicit val arbUnreachableNode: Arbitrary[UnreachableNode] =
    Arbitrary(arbMember.arbitrary.map(UnreachableNode(_)))

  implicit val arbIndirectlyConnectedNode: Arbitrary[IndirectlyConnectedNode] =
    Arbitrary(arbMember.arbitrary.map(IndirectlyConnectedNode(_)))

  implicit val arbUniqueAddress: Arbitrary[UniqueAddress] =
    Arbitrary(for {
      address <- arbitrary[Address]
      longUid <- arbitrary[Long]
    } yield UniqueAddress(address, longUid))

  implicit val arbAddress: Arbitrary[Address] =
    Arbitrary(for {
      protocol <- alphaNumStr
      system   <- alphaNumStr
      host     <- alphaNumStr
      port     <- arbitrary[Int]
    } yield Address(protocol, system, Some(host), Some(port)))

  implicit val arbMemberStatus: Arbitrary[MemberStatus] =
    Arbitrary(
      oneOf(Joining, WeaklyUp, Up, Leaving, Exiting, Down, Removed)
    )

  implicit val arbMemberJoined: Arbitrary[MemberJoined] = Arbitrary(
    arbJoiningMember.arbitrary.map(MemberJoined)
  )

  implicit val arbMemberUp: Arbitrary[MemberUp] = Arbitrary(
    arbUpMember.arbitrary.map(MemberUp)
  )

  implicit val arbMemberLeft: Arbitrary[MemberLeft] = Arbitrary(
    arbLeavingMember.arbitrary.map(MemberLeft)
  )

  implicit val arbMemberExited: Arbitrary[MemberExited] = Arbitrary(
    arbExitingMember.arbitrary.map(MemberExited)
  )

  implicit val arbMemberDowned: Arbitrary[MemberDowned] = Arbitrary(
    arbDownMember.arbitrary.map(MemberDowned)
  )

  implicit val arbMemberWeaklyUp: Arbitrary[MemberWeaklyUp] = Arbitrary(
    arbWeaklyUpMember.arbitrary.map(MemberWeaklyUp)
  )

  implicit val arbMemberRemoved: Arbitrary[MemberRemoved] = Arbitrary(
    arbRemovedMember.arbitrary.map(MemberRemoved(_, Removed))
  )

  implicit val arbMemberEvent: Arbitrary[MemberEvent] = Arbitrary(
    oneOf(
      arbMemberJoined.arbitrary,
      arbMemberUp.arbitrary,
      arbMemberLeft.arbitrary,
      arbMemberExited.arbitrary,
      arbMemberDowned.arbitrary,
      arbMemberWeaklyUp.arbitrary,
      arbMemberRemoved.arbitrary
    )
  )

  implicit val arbUnreachableMember: Arbitrary[UnreachableMember] = Arbitrary(
    arbMember.arbitrary.map(UnreachableMember)
  )

  implicit val arbReachableMember: Arbitrary[ReachableMember] = Arbitrary(
    arbMember.arbitrary.map(ReachableMember)
  )

  implicit val arbReachabilityEvent: Arbitrary[ReachabilityEvent] = Arbitrary(
    oneOf(arbUnreachableMember.arbitrary, arbReachableMember.arbitrary)
  )

  implicit val arbDownReachable: Arbitrary[DownReachable] = Arbitrary(arbWorldView.arbitrary.map(DownReachable(_)))

  implicit val arbDownUnreachable: Arbitrary[DownUnreachable] = Arbitrary(
    arbWorldView.arbitrary.map(DownUnreachable(_))
  )

  implicit val arbDownSelf: Arbitrary[DownSelf] = Arbitrary(arbWorldView.arbitrary.map(DownSelf(_)))

  implicit val arbDownThese: Arbitrary[DownThese] = Arbitrary(
    for {
      decision1 <- oneOf(arbDownReachable.arbitrary, arbDownUnreachable.arbitrary, arbDownSelf.arbitrary) // todo also gen downtheses?
      decision2 <- oneOf(arbDownReachable.arbitrary, arbDownUnreachable.arbitrary, arbDownSelf.arbitrary)
    } yield DownThese(decision1, decision2)
  )

  implicit val arbStrategyDecision: Arbitrary[StrategyDecision] = Arbitrary(
    oneOf(arbDownReachable.arbitrary, arbDownUnreachable.arbitrary, arbDownSelf.arbitrary, arbDownThese.arbitrary)
  )

  implicit val arbSBRReachability: Arbitrary[SBRReachability] = Arbitrary(
    oneOf(Reachable, Unreachable, IndirectlyConnected)
  )
}

trait ArbitraryInstances0 {
  implicit def arbSortedSet[A: Arbitrary: Order]: Arbitrary[SortedSet[A]] =
    Arbitrary(arbitrary[Set[A]].map(s => SortedSet.empty[A](implicitly[Order[A]].toOrdering) ++ s))

  implicit def arbSortedMap[K: Arbitrary: Order, V: Arbitrary]: Arbitrary[SortedMap[K, V]] =
    Arbitrary(arbitrary[Map[K, V]].map(s => SortedMap.empty[K, V](implicitly[Order[K]].toOrdering) ++ s))

  implicit def arbNonEmptySet[A](implicit O: Order[A], A: Arbitrary[A]): Arbitrary[NonEmptySet[A]] =
    Arbitrary(implicitly[Arbitrary[SortedSet[A]]].arbitrary.flatMap(fa => A.arbitrary.map(a => NonEmptySet(a, fa))))

  implicit def arbNonEmptyMap[K, A](implicit O: Order[K],
                                    A: Arbitrary[A],
                                    K: Arbitrary[K]): Arbitrary[NonEmptyMap[K, A]] =
    Arbitrary(for {
      fa <- arbSortedMap[K, A].arbitrary
      k  <- K.arbitrary
      a  <- A.arbitrary
    } yield NonEmptyMap((k, a), fa))
}
