package org.constellation.state

import java.security.KeyPair
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.constellation.consensus.Consensus.ProposedBlockUpdated
import org.constellation.primitives.{Block, Transaction}
import org.constellation.primitives.Chain.Chain
import org.constellation.state.ChainStateManager.BlockAddedToChain
import org.constellation.state.MemPoolManager.RemoveConfirmedTransactions
import org.constellation.wallet.KeyUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

import scala.collection.immutable.HashMap

class ChainStateManagerTest extends TestKit(ActorSystem("ChainStateManagerTest")) with FlatSpecLike with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "handleAddBlock" should "work correctly" in {
    val node1KeyPair = KeyUtils.makeKeyPair()
    val node2KeyPair = KeyUtils.makeKeyPair()

    val transaction1 =
      Transaction.senderSign(Transaction(0L, node1KeyPair.getPublic, node2KeyPair.getPublic, 33L), node1KeyPair.getPrivate)

    val genesisBlock = Block("gen", 0, "", Set(), 0L, Seq())
    val newBlock = Block("sig", 0, "", Set(), 1L, Seq(transaction1))

    val chain = Chain(Seq(genesisBlock))
    val memPoolManager = TestProbe()
    val replyTo = TestProbe()
    val updatedChain = ChainStateManager.handleAddBlock(chain, newBlock, memPoolManager.ref, replyTo.ref)

    val expectedChain = Chain(Seq(genesisBlock, newBlock))

    assert(updatedChain == expectedChain)

    memPoolManager.expectMsg(RemoveConfirmedTransactions(newBlock.transactions))

    replyTo.expectMsg(BlockAddedToChain(newBlock))

  }

  "handleCreateBlockProposal" should "work correctly" in {
    val node1 = TestProbe()
    val node2 = TestProbe()
    val node3 = TestProbe()
    val node4 = TestProbe()
    val node5 = TestProbe()

    val genesisBlock = Block("gen", 0, "", Set(), 0L, Seq())

    val node1KeyPair = KeyUtils.makeKeyPair()
    val node2KeyPair = KeyUtils.makeKeyPair()
    val node3KeyPair = KeyUtils.makeKeyPair()
    val node4KeyPair = KeyUtils.makeKeyPair()

    val transaction1 =
      Transaction.senderSign(Transaction(0L, node1KeyPair.getPublic, node2KeyPair.getPublic, 33L), node1KeyPair.getPrivate)

    val transaction2 =
      Transaction.senderSign(Transaction(1L, node2KeyPair.getPublic, node4KeyPair.getPublic, 14L), node2KeyPair.getPrivate)

    val transaction3 =
      Transaction.senderSign(Transaction(2L, node4KeyPair.getPublic, node1KeyPair.getPublic, 2L), node4KeyPair.getPrivate)

    val transaction4 =
      Transaction.senderSign(Transaction(3L, node3KeyPair.getPublic, node2KeyPair.getPublic, 20L), node3KeyPair.getPrivate)

    val chain = Chain(Seq(genesisBlock))

    val memPools = HashMap(
        node1.ref -> Seq(transaction1, transaction3),
        node2.ref -> Seq(transaction2),
        node3.ref -> Seq(transaction3, transaction2),
        node4.ref -> Seq(transaction4))

    val replyTo = TestProbe()

    ChainStateManager.handleCreateBlockProposal(memPools, chain, 1L, replyTo.ref)

    replyTo.expectMsg(ProposedBlockUpdated(Block(genesisBlock.signature, 1, "",
      genesisBlock.clusterParticipants, 1L, Seq(transaction1, transaction2, transaction3, transaction4))))

  }
}