package org.constellation

import org.constellation.crypto.KeyUtils
import org.constellation.primitives.Schema._
import org.constellation.util.Signed

object TestHelpers {

  def createTestBundle(): Signed[Bundle] = {
    import constellation._

    val keyPair = KeyUtils.makeKeyPair()
    val keyPair2 = KeyUtils.makeKeyPair()
    val keyPair3 = KeyUtils.makeKeyPair()

    val tx1 = TX(TXData(Seq(keyPair.getPublic), keyPair2.getPublic, 33L).signed()(keyPair = keyPair))

    val tx2 = TX(TXData(Seq(keyPair3.getPublic), keyPair2.getPublic, 14L).signed()(keyPair = keyPair3))

    val vote = Vote(VoteData(Seq(tx1), Seq(tx2)).signed()(keyPair = keyPair))
    val bundle = Bundle(BundleData(vote.vote.data.accept).signed()(keyPair = keyPair)).signed()(keyPair = keyPair)

    bundle
  }

}
