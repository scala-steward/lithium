package com.swissborg.sbr.reachability

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster._
import akka.cluster.swissborg.SBReachability
import cats.Eq
import cats.data.StateT
import cats.data.StateT._
import cats.effect.SyncIO
import cats.implicits._
import com.swissborg.sbr.converter.Converter
import com.swissborg.sbr.implicits._
import com.swissborg.sbr.reachability.SBReachabilityReporter.SBReachabilityStatus._
import com.swissborg.sbr.splitbrain.SBSplitBrainReporter._

import scala.concurrent.duration._

/**
  * Actor reporting the reachability status of cluster members based on `akka.cluster.Reachability`.
  *
  * A node is indirectly connected when only some of its observers see it as unreachable.
  * This might happen for instance when the link between two nodes is faulty,
  * they cannot directly communicate but can via another node.
  *
  * @param sbSplitBrainReporter the actor to which the reachability events have to be sent.
  */
class SBReachabilityReporter(val sbSplitBrainReporter: ActorRef)
    extends Actor
    with ActorLogging
    with Stash
    with Timers {
  import SBReachabilityReporter._

  private val cluster = Cluster(context.system)
  private val selfUniqueAddress = cluster.selfUniqueAddress
  private val failureDetector = cluster.failureDetector

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case _: CurrentClusterState =>
      unstashAll()
      context.become(active(SBReachabilityReporterState(selfUniqueAddress)))

    case _ => stash()
  }

  private def active(state: SBReachabilityReporterState): Receive = {
    case SBReachabilityChanged(r) =>
      context.become(active(updateReachabilities(r).runS(state).unsafeRunSync()))

    case MemberRemoved(m, _) =>
      log.debug("REMOVED {}", m)
      context.become(active(remove(m.uniqueAddress).runS(state).unsafeRunSync()))

    case MemberDowned(m) =>
      log.debug("DOWNED {}", m)
      context.become(active(remove(m.uniqueAddress).runS(state).unsafeRunSync()))

    case ReachableMember(m) =>
      context.become(active(withReachable(m.uniqueAddress).runS(state).unsafeRunSync()))

    case c: Contention =>
      log.debug("CONTENTION {}", c)
      context.become(active(withContentionFrom(sender(), c).runS(state).unsafeRunSync()))

    case ack: ContentionAck =>
      context.become(active(registerContentionAck(ack).runS(state).unsafeRunSync()))

    case RetrySend(message, to, key) =>
      context.become(active(sendWithRetry(message, to, key).runS(state).unsafeRunSync()))
  }

  /**
    * Broadcast contentions if the current node sees unreachable nodes as reachable.
    * Otherwise, send the updated reachability to the reporter.
    *
    * Sends the contentions with at-least-once delivery semantics.
    */
  private def updateReachabilities(reachability: SBReachability): Eval[Unit] = {

    /**
      * Attempts to find the record of `observer` that describes
      * `subject` as unreachable.
      *
      * Assumes that there's only one record per observer, subject pair in the
      * `Reachability` data structure.
      */
    def unreachableRecord(observer: Observer, subject: Subject): Option[Contention] =
      reachability
        .findUnreachableRecord(observer, subject)
        .map(r => Contention(selfUniqueAddress, r.observer, r.subject, r.version))

    def localReachability(node: UniqueAddress): Eval[LocalReachability] =
      liftF(SyncIO {
        if (failureDetector.isMonitoring(node.address)) {
          if (failureDetector.isAvailable(node.address)) LocallyReachable
          else LocallyUnreachable
        } else Unknown
      })

    /**
      * Send the contention to `to` expecting an ack. If an ack is not received in 1 second the actor
      * will retry.
      */
    def sendContentionWithRetry(contention: Contention, to: UniqueAddress): Eval[Unit] =
      sendWithRetry(
        contention,
        sbReachabilityReporterOnNode(to),
        ContentionKey(to, contention.observer, contention.subject)
      )

    /**
      * All the instances of this actor living on the other cluster nodes.
      */
    val sbFailureDetectors: Eval[List[UniqueAddress]] = liftF(
      SyncIO(cluster.state.members.toList.map(_.uniqueAddress))
    )

    /**
      * Broadcast with at-least-once delivery the contention to all the `SBFailureDetector`s
      * that exist on the cluster members, including itself.
      */
    def broadcastContentionWithRetry(contention: Contention): Eval[Unit] =
      for {
        sbFailureDetectors <- sbFailureDetectors
        state <- get[SyncIO, SBReachabilityReporterState]
        state <- sbFailureDetectors.traverse_ { to =>
          val ack = ContentionAck.fromContention(contention, to)
          val key = ContentionKey.fromAck(ack)

          val contentionAlreadyReceived = state.receivedAcks.get(ack.from).exists(_ === ack)

          val res: StateT[SyncIO, SBReachabilityReporterState, Unit] =
            if (contentionAlreadyReceived) {
              // No need to send the broadcast as it was already received.
              liftF(SyncIO.unit)
            } else if (to === selfUniqueAddress) {
              // Shortcut
              modify(
                _.withContention(
                  contention.protester,
                  contention.observer,
                  contention.subject,
                  contention.version
                ).registerContentionAck(ack) // so it won't be done again in subsequently
              )
            } else {
              for {
                // Cancel the timer for the previous observer, subject pair contention
                // as there is only one timer per such pair. There's no need to make sure
                // it was delivered, the new contention will override it.
                _ <- liftF(cancelContentionResend(key))
                _ <- sendContentionWithRetry(contention, to)
                _ <- modify[SyncIO, SBReachabilityReporterState](_.expectContentionAck(ack))
              } yield ()
            }

          res
        }
      } yield state

    // TODO directly use the record
    def withUnreachableFrom(contention: Contention): Eval[Unit] =
      modify(_.withUnreachableFrom(contention.observer, contention.subject, contention.version))

    def removeStaleContentions(reachability: SBReachability): Eval[Unit] = modify { state =>
      state.receivedAcks.valuesIterator
        .filterNot {
          case ContentionAck(_, observer, subject, _) =>
            reachability.findUnreachableRecord(observer, subject).isDefined
        }
        .foldLeft(state) {
          case (state, ack) => state.withoutContention(ack.from, ack.observer, ack.subject)
        }
    }

    reachability.observersGroupedByUnreachable.toList
      .traverse_ {
        case (subject, observers) =>
          observers.toList.traverse_ { observer =>
            for {
//              _ <- liftF[SyncIO, SBReachabilityReporterState, Unit](
//                SyncIO(log.debug("_.withUnreachableFrom({}, {})", observer, subject))
//              )
              _ <- removeStaleContentions(reachability)
              _ <- localReachability(subject).flatMap {
                case LocallyReachable =>
                  unreachableRecord(observer, subject).traverse_(broadcastContentionWithRetry)
                case LocallyUnreachable | Unknown =>
                  unreachableRecord(observer, subject).traverse_(withUnreachableFrom)
              }
              _ <- sendReachability(subject)
            } yield ()
          }
      }
  }

  /**
    * Register the node as removed.
    *
    * If the removed node is the current one the actor will stop itself.
    */
  private def remove(node: UniqueAddress): Eval[Unit] =
    if (node === selfUniqueAddress) {
      // This node is being stopped. Kill the actor
      // to stop any further updates.
      liftF(SyncIO(context.stop(self)))
    } else {
      val cancelContentionResend0: Eval[Unit] = inspectF {
        _.pendingContentionAcks
          .getOrElse(node, Set.empty)
          .foldLeft(SyncIO.unit) {
            case (cancelContentionResends, ack) =>
              cancelContentionResends >> cancelContentionResend(ContentionKey.fromAck(ack))
          }
      }

      for {
        _ <- cancelContentionResend0
        _ <- modify[SyncIO, SBReachabilityReporterState](_.remove(node))
      } yield ()
    }

  /**
    * Register the node as reachable and inform the reporter of it.
    */
  private def withReachable(node: UniqueAddress): Eval[Unit] =
    for {
      _ <- modify[SyncIO, SBReachabilityReporterState](_.withReachable(node))
      _ <- sendReachability(node)
    } yield ()

  /**
    * Send the reachability of `node` to the reporter.
    *
    * If it is the same as the previous time this function was called
    * it will do nothing.
    */
  private def sendReachability(node: UniqueAddress): Eval[Unit] =
    modifyF { state =>
      val (status, state0) = state.updatedStatus(node)

      val sendStatus = status
        .traverse_ { reachability =>
          SyncIO(sbSplitBrainReporter ! (reachability match {
            case Reachable           => NodeReachable(node)
            case IndirectlyConnected => NodeIndirectlyConnected(node)
            case Unreachable         => NodeUnreachable(node)
          }))
        }

      sendStatus.as(state0)
    }

  /**
    * Add the contention and acknowledge the sender that it was received.
    */
  private def withContentionFrom(sender: ActorRef, contention: Contention): Eval[Unit] = {
    def withContention(contention: Contention): Eval[Unit] = modify(
      _.withContention(
        contention.protester,
        contention.observer,
        contention.subject,
        contention.version
      )
    )

    def ackContention(sender: ActorRef, contention: Contention): Eval[Unit] = liftF(
      SyncIO(sender ! ContentionAck.fromContention(contention, selfUniqueAddress))
    )

    for {
      _ <- withContention(contention)
      _ <- sendReachability(contention.subject)
      _ <- ackContention(sender, contention)
    } yield ()
  }

  private def registerContentionAck(ack: ContentionAck): Eval[Unit] =
    for {
      _ <- liftF(cancelContentionResend(ContentionKey.fromAck(ack)))
      _ <- modify[SyncIO, SBReachabilityReporterState](_.registerContentionAck(ack))
    } yield ()

  /**
    * Send `message` to `to` every second until an ack is received.
    */
  private def sendWithRetry(message: Any, to: ActorPath, cancellationKey: Any): Eval[Unit] = {
    def retryAfter(timeout: FiniteDuration): SyncIO[Unit] =
      SyncIO(
        timers.startSingleTimer(cancellationKey, RetrySend(message, to, cancellationKey), timeout)
      )

    liftF(
      for {
        _ <- SyncIO(context.system.actorSelection(to) ! message)
        _ <- SyncIO(log.debug("Attempting to send {} to {}", message, to))
        _ <- retryAfter(1.second)
      } yield ()
    )
  }

  private def cancelContentionResend(key: ContentionKey): SyncIO[Unit] = SyncIO(timers.cancel(key))

  private def sbReachabilityReporterOnNode(node: UniqueAddress): ActorPath =
    ActorPath.fromString(s"${node.address.toString}/${self.path.toStringWithoutAddress}")

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
    Converter(context.system).subscribeToReachabilityChanged(self)
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    Converter(context.system).unsubscribe(self)
    timers.cancelAll()
  }
}

object SBReachabilityReporter {
  type Eval[A] = StateT[SyncIO, SBReachabilityReporterState, A]

  def props(sendTo: ActorRef): Props = Props(new SBReachabilityReporter(sendTo))

  sealed abstract class SBReachabilityStatus
  object SBReachabilityStatus {
    final case object Reachable extends SBReachabilityStatus with SBSelfReachabilityStatus
    final case object Unreachable extends SBReachabilityStatus
    final case object IndirectlyConnected extends SBReachabilityStatus with SBSelfReachabilityStatus

//    implicit val sbrReachabilityStatusEncoder: Encoder[SBReachabilityStatus] = deriveEncoder
  }

  // Marker trait.
  // So that the "current" node cannot be unreachable.
  sealed trait SBSelfReachabilityStatus

  /**
    * Key for the timer related to the at-least-once delivery resend for the contention
    * of the observation of `observer` of `subject` as unreachable.
    *
    * Warning: `to` must containing the address!
    */
  final case class ContentionKey(to: UniqueAddress, observer: Observer, subject: Subject)

  object ContentionKey {
    def fromAck(ack: ContentionAck): ContentionKey =
      ContentionKey(ack.from, ack.observer, ack.subject)
  }

  final case class RetrySend(message: Any, to: ActorPath, key: Any)

  sealed abstract private class LocalReachability
  private case object Unknown extends LocalReachability
  private case object LocallyReachable extends LocalReachability
  private case object LocallyUnreachable extends LocalReachability

  /**
    * Acknowledgment of a contention message,
    *
    * Warning: `from` must containing the address!
    */
  final case class ContentionAck(
      from: UniqueAddress,
      observer: Observer,
      subject: Subject,
      version: Version
  )

  object ContentionAck {
    def fromContention(contention: Contention, from: UniqueAddress): ContentionAck =
      ContentionAck(from, contention.observer, contention.subject, contention.version)

    implicit val contentionAckEq: Eq[ContentionAck] = (x: ContentionAck, y: ContentionAck) =>
      x.from === y.from && x.observer === y.observer && x.subject === y.subject && x.version === y.version
  }

  final case class SBReachabilityChanged(reachability: SBReachability)

  final case class Contention(
      protester: UniqueAddress,
      observer: Observer,
      subject: Subject,
      version: Version
  )

  final case class RevertContention(
      protester: UniqueAddress,
      observer: Observer,
      subject: Subject,
      version: Version
  )
}