package com.swissborg.sbr.strategies.keepoldest.eight

import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.swissborg.sbr.TenNodeSpec
import com.swissborg.sbr.strategies.keepoldest.KeepOldestSpecTenNodeConfig

import scala.concurrent.duration._

class KeepOldestSpec8MultiJvmNode1  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode2  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode3  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode4  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode5  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode6  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode7  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode8  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode9  extends KeepOldestSpec8
class KeepOldestSpec8MultiJvmNode10 extends KeepOldestSpec8

/**
 * Node2 and node3 are indirectly connected in a ten node cluster
 * Node9 and node10 are indirectly connected in a ten node cluster
 */
class KeepOldestSpec8 extends TenNodeSpec("KeepOldest", KeepOldestSpecTenNodeConfig) {
  override def assertions(): Unit =
    "handle indirectly-connected nodes" in within(120 seconds) {
      runOn(node1) {
        // Node9 cannot receive node10 messages
        val a = testConductor.blackhole(node9, node10, Direction.Receive).await
        val b = testConductor.blackhole(node2, node3, Direction.Receive).await
      }

      enterBarrier("links-disconnected")

      runOn(node1, node4, node5, node6, node7, node8) {
        waitForSurvivors(node1, node4, node5, node6, node7, node8)
        waitExistsAllDownOrGone(
          Seq(Seq(node2, node9), Seq(node2, node10), Seq(node3, node9), Seq(node3, node10))
        )
      }

      enterBarrier("split-brain-resolved")
    }
}
