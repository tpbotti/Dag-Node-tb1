package org.constellation.consensus

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import org.constellation.KVDB
import org.constellation.primitives.Schema
import org.constellation.primitives.Schema.{AddressCacheData, Transaction, TransactionCacheData}

import scala.concurrent.{ExecutionContext, Future}

object Validation {

  def validateCheckpoint(
                          dbActor: ActorRef,
                          cb: Schema.CheckpointBlock
                        )(implicit ec: ExecutionContext): Future[CheckpointValidationStatus] = {
    Future{CheckpointValidationStatus()}
  }

  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)

  // TODO : Add an LRU cache for looking up TransactionCacheData instead of pure LDB calls.

  case class CheckpointValidationStatus(
                                        )

  case class TransactionValidationStatus(
                                        transaction: Transaction,
                                        transactionCacheData: Option[TransactionCacheData],
                                        addressCacheData: Option[AddressCacheData]
                                        ) {
    def isDuplicateHash: Boolean = transactionCacheData.exists{_.inDAG}
    def sufficientBalance: Boolean = addressCacheData.exists{c =>
      c.balance >= transaction.amount
    }
    def sufficientMemPoolBalance: Boolean = addressCacheData.exists{c =>
      c.memPoolBalance >= transaction.amount
    }

    def validByCurrentState: Boolean = !isDuplicateHash && sufficientBalance
    def validByCurrentStateMemPool: Boolean = !isDuplicateHash && sufficientBalance && sufficientMemPoolBalance
    def validByAncestor(ancestorHash: String): Boolean = {
      transactionCacheData.exists{t => !t.inDAGByAncestor.contains(ancestorHash)} &&
      addressCacheData.exists(_.ancestorBalances.get(ancestorHash).exists(_ >= transaction.amount))
    }
    // Need separate validator here for CB validation vs. mempool addition
    // I.e. don't check mempool balance when validating a CB because it takes precedence over
    // a new TX which is being added to mempool and conflicts with current mempool values.
  }

  /**
    * Check if a transaction already exists on chain or if there's a sufficient balance to process it
    * TODO: Use a bloom filter
    * @param dbActor: Reference to LevelDB DAO
    * @param tx : Resolved transaction
    * @return Future of whether or not the transaction should be considered valid
    * **/
  def validateTransaction(dbActor: KVDB, tx: Transaction): TransactionValidationStatus = {

    // A transaction should only be considered in the DAG once it has been committed to a checkpoint block.
    // Before that, it exists only in the memPool and is not stored in the database.
    val txCache = dbActor.getTransactionCacheData(tx.baseHash)
    val addressCache = dbActor.getAddressCacheData(tx.src.hash)
    TransactionValidationStatus(tx, txCache, addressCache)
  }
}