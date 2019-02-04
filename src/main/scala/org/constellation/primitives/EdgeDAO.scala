package org.constellation.primitives

import java.util.concurrent.{Executors, Semaphore, TimeUnit}
import akka.util.Timeout
import com.twitter.storehaus.cache.MutableLRUCache
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.Random

import org.constellation.consensus.EdgeProcessor.acceptCheckpoint
import org.constellation.consensus._
import org.constellation.primitives.Schema._
import org.constellation.{DAO, ProcessingConfig}

/** Documentation. */
class ThreadSafeTXMemPool() {

  private var transactions = Seq[Transaction]()

  /** Documentation. */
  def pull(minCount: Int): Option[Seq[Transaction]] = this.synchronized{
    if (transactions.size > minCount) {
      val (left, right) = transactions.splitAt(minCount)
      transactions = right
      Some(left)
    } else None
  }

  /** Documentation. */
  def batchPutDebug(txs: Seq[Transaction]) : Boolean = this.synchronized{
    transactions ++= txs
    true
  }

  /** Documentation. */
  def put(transaction: Transaction, overrideLimit: Boolean = false)(implicit dao: DAO): Boolean = this.synchronized{
    val notContained = !transactions.contains(transaction)

    if (notContained) {
      if (overrideLimit) {
        // Prepend in front to process user TX first before random ones
        transactions = Seq(transaction) ++ transactions

      } else if (transactions.size < dao.processingConfig.maxMemPoolSize) {
        transactions :+= transaction
      }
    }
    notContained
  }

  /** Documentation. */
  def unsafeCount: Int = transactions.size

}

/** Documentation. */
class ThreadSafeMessageMemPool() {

  private var messages = Seq[Seq[ChannelMessage]]()

  val activeChannels: TrieMap[String, Semaphore] = TrieMap()

  val messageHashToSendRequest: TrieMap[String, ChannelSendRequest] = TrieMap()

  /** Documentation. */
  def pull(minCount: Int): Option[Seq[ChannelMessage]] = this.synchronized{
    if (messages.size > minCount) {
      val (left, right) = messages.splitAt(minCount)
      messages = right
      Some(left.flatten)
    } else None
  }

  /** Documentation. */
  def batchPutDebug(messagesToAdd: Seq[ChannelMessage]) : Boolean = this.synchronized{
    //messages ++= messagesToAdd
    true
  }

  /** Documentation. */
  def put(message: Seq[ChannelMessage], overrideLimit: Boolean = false)(implicit dao: DAO): Boolean = this.synchronized{
    val notContained = !messages.contains(message)

    if (notContained) {
      if (overrideLimit) {
        // Prepend in front to process user TX first before random ones
        messages = Seq(message) ++ messages

      } else if (messages.size < dao.processingConfig.maxMemPoolSize) {
        messages :+= message
      }
    }
    notContained
  }

  /** Documentation. */
  def unsafeCount: Int = messages.size

}

import constellation._

/** Documentation. */
class ThreadSafeTipService() {

  implicit val timeout: Timeout = Timeout(15, TimeUnit.SECONDS)

  private var thresholdMetCheckpoints: Map[String, TipData] = Map()
  var acceptedCBSinceSnapshot: Seq[String] = Seq()
  var facilitators: Map[Id, PeerData] = Map()
  private var snapshot: Snapshot = Snapshot.snapshotZero

  /** Documentation. */
  def tips: Map[String, TipData] = thresholdMetCheckpoints

  /** Documentation. */
  def getSnapshotInfo()(implicit dao: DAO): SnapshotInfo = this.synchronized(
    SnapshotInfo(
      snapshot,
      acceptedCBSinceSnapshot,
      lastSnapshotHeight = lastSnapshotHeight,
      snapshotHashes = dao.snapshotHashes,
      addressCacheData = dao.addressService.toMap(),
      tips = thresholdMetCheckpoints,
      snapshotCache = snapshot.checkpointBlocks.flatMap{dao.checkpointService.get}
    )
  )

  var totalNumCBsInShapshots = 0L

  // ONLY TO BE USED BY DOWNLOAD COMPLETION CALLER

  /** Documentation. */
  def setSnapshot(latestSnapshotInfo: SnapshotInfo)(implicit dao: DAO): Unit = this.synchronized{
    snapshot = latestSnapshotInfo.snapshot
    lastSnapshotHeight = latestSnapshotInfo.lastSnapshotHeight
    thresholdMetCheckpoints = latestSnapshotInfo.tips

    // Below may not be necessary, just a sanity check
    acceptedCBSinceSnapshot = latestSnapshotInfo.acceptedCBSinceSnapshot
    latestSnapshotInfo.addressCacheData.foreach{
      case (k,v) =>
        dao.addressService.put(k, v)
    }

    latestSnapshotInfo.snapshotCache.foreach{
      h =>
        dao.metrics.incrementMetric("checkpointAccepted")
        dao.checkpointService.put(h.checkpointBlock.get.baseHash, h)
        h.checkpointBlock.get.storeSOE()
        h.checkpointBlock.get.transactions.foreach{
          _ =>
            dao.metrics.incrementMetric("transactionAccepted")
        }
    }

    latestSnapshotInfo.acceptedCBSinceSnapshotCache.foreach{
      h =>
        dao.checkpointService.put(h.checkpointBlock.get.baseHash, h)
        h.checkpointBlock.get.storeSOE()
        dao.metrics.incrementMetric("checkpointAccepted")
        h.checkpointBlock.get.transactions.foreach{
          _ =>
          dao.metrics.incrementMetric("transactionAccepted")
        }
    }

    dao.metrics.updateMetric("acceptCBCacheMatchesAcceptedSize", (latestSnapshotInfo.acceptedCBSinceSnapshot.size == latestSnapshotInfo.acceptedCBSinceSnapshotCache.size).toString)

  }

  // TODO: Read from lastSnapshot in DB optionally, assign elsewhere
  var lastSnapshotHeight = 0

  /** Documentation. */
  def getMinTipHeight()(implicit dao: DAO) = thresholdMetCheckpoints.keys.map {
    dao.checkpointService.get
  }.flatMap {
    _.flatMap {
      _.height.map {
        _.min
      }
    }
  }.min

  var syncBuffer : Seq[CheckpointCacheData] = Seq()

  /** Documentation. */
  def syncBufferAccept(cb: CheckpointCacheData)(implicit dao: DAO): Unit = {
    syncBuffer :+= cb
    dao.metrics.updateMetric("syncBufferSize", syncBuffer.size.toString)
  }

  /** Documentation. */
  def attemptSnapshot()(implicit dao: DAO): Unit = this.synchronized{

    // Sanity check memory protection
    if (thresholdMetCheckpoints.size > dao.processingConfig.maxActiveTipsAllowedInMemory) {
      thresholdMetCheckpoints = thresholdMetCheckpoints.slice(0, 100)
      dao.metrics.incrementMetric("memoryExceeded_thresholdMetCheckpoints")
      dao.metrics.updateMetric("activeTips", thresholdMetCheckpoints.size.toString)
    }
    if (acceptedCBSinceSnapshot.size > dao.processingConfig.maxAcceptedCBHashesInMemory) {
      acceptedCBSinceSnapshot = acceptedCBSinceSnapshot.slice(0, 100)
      dao.metrics.incrementMetric("memoryExceeded_acceptedCBSinceSnapshot")
      dao.metrics.updateMetric("acceptedCBSinceSnapshot", acceptedCBSinceSnapshot.size.toString)
    }

    val peerIds = dao.peerInfo //(dao.peerManager ? GetPeerInfo).mapTo[Map[Id, PeerData]].get().toSeq
    val facilMap = peerIds.filter{case (_, pd) =>
      pd.peerMetadata.timeAdded < (System.currentTimeMillis() - dao.processingConfig.minPeerTimeAddedSeconds * 1000) && pd.peerMetadata.nodeState == NodeState.Ready
    }

    facilitators = facilMap

    if (dao.nodeState == NodeState.Ready && acceptedCBSinceSnapshot.nonEmpty) {

      val minTipHeight = getMinTipHeight()
      dao.metrics.updateMetric("minTipHeight", minTipHeight.toString)

      val nextHeightInterval = lastSnapshotHeight + dao.processingConfig.snapshotHeightInterval

      val canSnapshot = minTipHeight > (nextHeightInterval + dao.processingConfig.snapshotHeightDelayInterval)
      if (!canSnapshot) {
        dao.metrics.incrementMetric("snapshotHeightIntervalConditionNotMet")
      } else {

        val maybeDatas = acceptedCBSinceSnapshot.map(dao.checkpointService.get)

        val blocksWithinHeightInterval = maybeDatas.filter {
          _.exists(_.height.exists { h =>
            h.min > lastSnapshotHeight && h.min <= nextHeightInterval
          })
        }

        if (blocksWithinHeightInterval.isEmpty) {
          dao.metrics.incrementMetric("snapshotNoBlocksWithinHeightInterval")
        } else {

          val blockCaches = blocksWithinHeightInterval.map {
            _.get
          }

          val hashesForNextSnapshot = blockCaches.map {
            _.checkpointBlock.get.baseHash
          }.sorted
          val nextSnapshot = Snapshot(snapshot.hash, hashesForNextSnapshot)

          // TODO: Make this a future and have it not break the unit test
          // Also make the db puts blocking, may help for different issue
          if (snapshot != Snapshot.snapshotZero) {
            dao.metrics.incrementMetric("snapshotCount")

            // Write snapshot to file
            tryWithMetric({
              val maybeBlocks = snapshot.checkpointBlocks.map {
                dao.checkpointService.get
              }
              if (maybeBlocks.exists(_.exists(_.checkpointBlock.isEmpty))) {
                // TODO : This should never happen, if it does we need to reset the node state and redownload
                dao.metrics.incrementMetric("snapshotWriteToDiskMissingData")
              }
              val flatten = maybeBlocks.flatten.sortBy(_.checkpointBlock.map {
                _.baseHash
              })
              Snapshot.writeSnapshot(StoredSnapshot(snapshot, flatten))
              // dao.dbActor.kvdb.put("latestSnapshot", snapshot)
            },
              "snapshotWriteToDisk"
            )

            Snapshot.acceptSnapshot(snapshot)
            dao.checkpointService.delete(snapshot.checkpointBlocks.toSet)

            totalNumCBsInShapshots += snapshot.checkpointBlocks.size
            dao.metrics.updateMetric("totalNumCBsInShapshots", totalNumCBsInShapshots.toString)
            dao.metrics.updateMetric("lastSnapshotHash", snapshot.hash)
          }

          // TODO: Verify from file
          /*
        if (snapshot.lastSnapshot != Snapshot.snapshotZeroHash && snapshot.lastSnapshot != "") {

          val lastSnapshotVerification = File(dao.snapshotPath, snapshot.lastSnapshot).read
          if (lastSnapshotVerification.isEmpty) {
            dao.metrics.incrementMetric("snapshotVerificationFailed")
          } else {
            dao.metrics.incrementMetric("snapshotVerificationCount")
            if (
              !lastSnapshotVerification.get.checkpointBlocks.map {
                dao.checkpointService.get
              }.forall(_.exists(_.checkpointBlock.nonEmpty))
            ) {
              dao.metrics.incrementMetric("snapshotCBVerificationFailed")
            } else {
              dao.metrics.incrementMetric("snapshotCBVerificationCount")
            }

          }
        }
*/

          lastSnapshotHeight = nextHeightInterval
          snapshot = nextSnapshot
          acceptedCBSinceSnapshot = acceptedCBSinceSnapshot.filterNot(hashesForNextSnapshot.contains)
          dao.metrics.updateMetric("acceptedCBSinceSnapshot", acceptedCBSinceSnapshot.size.toString)
          dao.metrics.updateMetric("lastSnapshotHeight", lastSnapshotHeight.toString)
          dao.metrics.updateMetric("nextSnapshotHeight", (lastSnapshotHeight + dao.processingConfig.snapshotHeightInterval).toString)
        }
      }
    }
  }

  /** Documentation. */
  def acceptGenesis(genesisObservation: GenesisObservation): Unit = this.synchronized{
    thresholdMetCheckpoints += genesisObservation.initialDistribution.baseHash -> TipData(genesisObservation.initialDistribution, 0)
    thresholdMetCheckpoints += genesisObservation.initialDistribution2.baseHash -> TipData(genesisObservation.initialDistribution2, 0)
  }

  /** Documentation. */
  def pull()(implicit dao: DAO): Option[(Seq[SignedObservationEdge], Map[Id, PeerData])] = this.synchronized{
    if (thresholdMetCheckpoints.size >= 2 && facilitators.nonEmpty) {
      val tips = Random.shuffle(thresholdMetCheckpoints.toSeq).take(2)

      val tipSOE = tips.map {
        _._2.checkpointBlock.checkpoint.edge.signedObservationEdge
      }.sortBy(_.hash)

      val mergedTipHash = tipSOE.map {_.hash}.mkString("")

      val totalNumFacil = facilitators.size
      // TODO: Use XOR distance instead as it handles peer data mismatch cases better
      val facilitatorIndex = (BigInt(mergedTipHash, 16) % totalNumFacil).toInt
      val sortedFacils = facilitators.toSeq.sortBy(_._1.hex)
      val selectedFacils = Seq.tabulate(dao.processingConfig.numFacilitatorPeers) { i => (i + facilitatorIndex) % totalNumFacil }.map {
        sortedFacils(_)
      }
      val finalFacilitators = selectedFacils.toMap
      dao.metrics.updateMetric("activeTips", thresholdMetCheckpoints.size.toString)

      Some(tipSOE -> finalFacilitators)
    } else None
  }

  // TODO: Synchronize only on values modified by this, same for other functions

  /** Documentation. */
  def accept(checkpointCacheData: CheckpointCacheData)(implicit dao: DAO): Unit = this.synchronized {

    if (dao.checkpointService.contains(checkpointCacheData.checkpointBlock.map {
      _.baseHash
    }.getOrElse(""))) {

      dao.metrics.incrementMetric("checkpointAcceptBlockAlreadyStored")

    } else {

      tryWithMetric(acceptCheckpoint(checkpointCacheData), "acceptCheckpoint")

      /** Documentation. */
      def reuseTips: Boolean = thresholdMetCheckpoints.size < dao.maxWidth

      checkpointCacheData.checkpointBlock.foreach { checkpointBlock =>

        val keysToRemove = checkpointBlock.parentSOEBaseHashes.flatMap {
          h =>
            thresholdMetCheckpoints.get(h).flatMap {
              case TipData(block, numUses) =>

                /** Documentation. */
                def doRemove(): Option[String] = {
                  dao.metrics.incrementMetric("checkpointTipsRemoved")
                  Some(block.baseHash)
                }

                if (reuseTips) {
                  if (numUses >= 2) {
                    doRemove()
                  } else {
                    None
                  }
                } else {
                  doRemove()
                }
            }
        }

        val keysToUpdate = checkpointBlock.parentSOEBaseHashes.flatMap {
          h =>
            thresholdMetCheckpoints.get(h).flatMap {
              case TipData(block, numUses) =>

                /** Documentation. */
                def doUpdate(): Option[(String, TipData)] = {
                  dao.metrics.incrementMetric("checkpointTipsIncremented")
                  Some(block.baseHash -> TipData(block, numUses + 1))
                }

                if (reuseTips && numUses <= 2) {
                  doUpdate()
                } else None
            }
        }.toMap

        thresholdMetCheckpoints = thresholdMetCheckpoints +
          (checkpointBlock.baseHash -> TipData(checkpointBlock, 0)) ++
          keysToUpdate --
          keysToRemove

        if (acceptedCBSinceSnapshot.contains(checkpointBlock.baseHash)) {
          dao.metrics.incrementMetric("checkpointAcceptedButAlreadyInAcceptedCBSinceSnapshot")
        } else {
          acceptedCBSinceSnapshot = acceptedCBSinceSnapshot :+ checkpointBlock.baseHash
          dao.metrics.updateMetric("acceptedCBSinceSnapshot", acceptedCBSinceSnapshot.size.toString)
        }

      }
    }
  }

}

// TODO: Use atomicReference increment pattern instead of synchronized

/** Documentation. */
class StorageService[T](size: Int = 50000) {

  private val lruCache: MutableLRUCache[String, T] = {
    import com.twitter.storehaus.cache._
    MutableLRUCache[String, T](size)
  }

  // val actualDatastore = ... .update

  // val mutexStore = TrieMap[String, AtomicUpdater]

  // val mutexKeyCache = mutable.Queue()

  // if mutexKeyCache > size :
  // poll and remove from mutexStore?
  // mutexStore.getOrElseUpdate(hash)
  // Map[Address, AtomicUpdater] // computeIfAbsent getOrElseUpdate
/*  class AtomicUpdater {

    /** Documentation. */
    def update(
                key: String,
                updateFunc: T => T,
                empty: => T
              ): T =
      this.synchronized{
        val data = get(key).map {updateFunc}.getOrElse(empty)
        put(key, data)
        data
      }
  }*/

  /** Documentation. */
  def delete(keys: Set[String]) = this.synchronized{
    lruCache.multiRemove(keys)
  }

  /** Documentation. */
  def contains(key: String): Boolean = this.synchronized{
    lruCache.contains(key)
  }

  /** Documentation. */
  def get(key: String): Option[T] = this.synchronized{
    lruCache.get(key)
  }

  /** Documentation. */
  def put(key: String, cache: T): Unit = this.synchronized{
    lruCache.+=((key, cache))
  }

  /** Documentation. */
  def update(
              key: String,
              updateFunc: T => T,
              empty: => T
            ): T =
    this.synchronized{
      val data = get(key).map {updateFunc}.getOrElse(empty)
      put(key, data)
      data
    }

  /** Documentation. */
  def toMap(): Map[String, T] = this.synchronized {
    lruCache.iterator.toMap
  }

}

// TODO: Make separate one for acceptedCheckpoints vs nonresolved etc.

/** Documentation. */
class CheckpointService(size: Int = 50000) extends StorageService[CheckpointCacheData](size)

/** Documentation. */
class SOEService(size: Int = 50000) extends StorageService[SignedObservationEdgeCache](size)

/** Documentation. */
class MessageService(size: Int = 50000) extends StorageService[ChannelMessageMetadata](size)

/** Documentation. */
class TransactionService(size: Int = 50000) extends StorageService[TransactionCacheData](size) {
  private val queue = mutable.Queue[TransactionSerialized]()
  private val maxQueueSize = 20

  /** Documentation. */
  override def put(
    key: String,
    cache: TransactionCacheData
  ): Unit = {
    val tx = TransactionSerialized(cache.transaction)
    queue.synchronized {
      if (queue.size == maxQueueSize) {
        queue.dequeue()
      }

      queue.enqueue(tx)
      super.put(key, cache)
    }
  }

  /** Documentation. */
  def getLast20TX = queue.reverse
}

/** Documentation. */
class AddressService(size: Int = 50000) extends StorageService[AddressCacheData](size)

/** Documentation. */
trait EdgeDAO {

  var processingConfig = ProcessingConfig()

  @volatile var blockFormationInProgress: Boolean = false

  val publicReputation: TrieMap[Id, Double] = TrieMap()
  val secretReputation: TrieMap[Id, Double] = TrieMap()

  val otherNodeScores: TrieMap[Id, TrieMap[Id, Double]] = TrieMap()

  val checkpointService = new CheckpointService(processingConfig.checkpointLRUMaxSize)
  val transactionService = new TransactionService(processingConfig.transactionLRUMaxSize)
  val addressService = new AddressService(processingConfig.addressLRUMaxSize)
  val messageService = new MessageService()
  val soeService = new SOEService()

  val threadSafeTXMemPool = new ThreadSafeTXMemPool()
  val threadSafeMessageMemPool = new ThreadSafeMessageMemPool()
  val threadSafeTipService = new ThreadSafeTipService()

  var genesisObservation: Option[GenesisObservation] = None

  /** Documentation. */
  def maxWidth: Int = processingConfig.maxWidth

  /** Documentation. */
  def minCheckpointFormationThreshold: Int = processingConfig.minCheckpointFormationThreshold

  /** Documentation. */
  def minCBSignatureThreshold: Int = processingConfig.numFacilitatorPeers

  val resolveNotifierCallbacks: TrieMap[String, Seq[CheckpointBlock]] = TrieMap()

  val edgeExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newWorkStealingPool(40))

 // val peerAPIExecutionContext: ExecutionContextExecutor =
 //   ExecutionContext.fromExecutor(Executors.newWorkStealingPool(40))

  val apiClientExecutionContext: ExecutionContextExecutor = edgeExecutionContext
  //  ExecutionContext.fromExecutor(Executors.newWorkStealingPool(40))

  val signatureExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newWorkStealingPool(40))

  val finishedExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newWorkStealingPool(40))

  // Temporary to get peer data for tx hash partitioning
  @volatile var peerInfo: Map[Id, PeerData] = Map()

  /** Documentation. */
  def readyPeers: Map[Id, PeerData] = peerInfo.filter(_._2.peerMetadata.nodeState == NodeState.Ready)

}
