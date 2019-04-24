package akka.cluster.sbr.strategies.keepmajority.five

import akka.cluster.sbr.FiveNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction

import scala.concurrent.duration._

class KeepMajoritySpec5MultiJvmNode1 extends KeepMajoritySpec5
class KeepMajoritySpec5MultiJvmNode2 extends KeepMajoritySpec5
class KeepMajoritySpec5MultiJvmNode3 extends KeepMajoritySpec5
class KeepMajoritySpec5MultiJvmNode4 extends KeepMajoritySpec5
class KeepMajoritySpec5MultiJvmNode5 extends KeepMajoritySpec5

class KeepMajoritySpec5 extends FiveNodeSpec("StaticQuorum", KeepMajoritySpec5Config) {
  override def assertions(): Unit =
    "Unidirectional link failure" in within(120 seconds) {
      runOn(node1) {
        // Node4 cannot receive node5 messages
        val _ = testConductor.blackhole(node4, node5, Direction.Receive).await
      }

      enterBarrier("links-disconnected")

      runOn(node1, node2, node3) {
        waitForSurvivors(node1, node2, node3)
        waitExistsAllDownOrGone(Seq(Seq(node4), Seq(node5)))
      }

      enterBarrier("split-brain-resolved")
    }
}
