package com.swissborg.sbr.protobuf

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.testkit.TestKit
import com.swissborg.sbr.SBSpec
import com.swissborg.sbr.failuredetector.SBFailureDetector.{Contention, ContentionAck}

class SBMessageSerializerSpec extends TestKit(ActorSystem("helo")) with SBSpec {
  private val contentionSerializer = SerializationExtension(system).findSerializerFor(classOf[Contention])
  private val ackSerializer        = SerializationExtension(system).findSerializerFor(classOf[ContentionAck])

  "SBMessageSerializer" must {
    "Contention round-trip" in {
      forAll { contention: Contention =>
        val bytes = contentionSerializer.toBinary(contention)
        contentionSerializer.fromBinary(bytes) shouldBe contention
      }
    }

    "ContentionAck round-trip" in {
      forAll { contentionAck: ContentionAck =>
        val bytes = ackSerializer.toBinary(contentionAck)
        ackSerializer.fromBinary(bytes) match {
          case ack: ContentionAck => ack should ===(contentionAck)
          case other              => fail(s"$other")
        }
      }
    }
  }
}