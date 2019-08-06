package org.constellation.consensus

import cats.effect.{Concurrent, LiftIO, Sync}
import cats.implicits._
import com.softwaremill.sttp.Response
import com.typesafe.config.Config
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.DAO
import org.constellation.consensus.Consensus.ConsensusStage.ConsensusStage
import org.constellation.consensus.Consensus.StageState.StageState
import org.constellation.consensus.Consensus._
import org.constellation.consensus.ConsensusManager.{
  BroadcastLightTransactionProposal,
  BroadcastSelectedUnionBlock,
  BroadcastUnionBlockProposal
}
import org.constellation.p2p.{DataResolver, PeerData, PeerNotification}
import org.constellation.primitives.Schema.{CheckpointCache, EdgeHashType, TypedEdgeHash}
import org.constellation.primitives._
import org.constellation.primitives.concurrency.SingleRef
import org.constellation.storage._
import org.constellation.util.PeerApiClient

import scala.concurrent.duration._

class Consensus[F[_]: Concurrent](
  roundData: RoundData,
  arbitraryTransactions: Seq[(Transaction, Int)],
  arbitraryMessages: Seq[(ChannelMessage, Int)],
  dataResolver: DataResolver,
  transactionService: TransactionService[F],
  checkpointService: CheckpointService[F],
  messageService: MessageService[F],
  experienceService: ExperienceService[F],
  remoteSender: ConsensusRemoteSender[F],
  consensusManager: ConsensusManager[F],
  dao: DAO,
  config: Config
) {

  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  implicit val shadowDAO: DAO = dao

  private[consensus] val transactionProposals: SingleRef[F, Map[FacilitatorId, LightTransactionsProposal]] =
    SingleRef(Map.empty[FacilitatorId, LightTransactionsProposal])
  private[consensus] val checkpointBlockProposals: SingleRef[F, Map[FacilitatorId, CheckpointBlock]] =
    SingleRef(Map.empty[FacilitatorId, CheckpointBlock])
  private[consensus] val selectedCheckpointBlocks: SingleRef[F, Map[FacilitatorId, CheckpointBlock]] =
    SingleRef(Map.empty[FacilitatorId, CheckpointBlock])

  private[consensus] val stage: SingleRef[F, ConsensusStage] = SingleRef(ConsensusStage.STARTING)

  def startTransactionProposal(): F[Unit] =
    for {
      transactions <- transactionService
        .pullForConsensusWithDummy(1)
        .map(_.map(_.transaction))
      messages <- Sync[F].delay(dao.threadSafeMessageMemPool.pull())
      notifications <- LiftIO[F].liftIO(dao.peerInfo.map(_.values.flatMap(_.notification).toSeq))
      experiences <- experienceService.pullForConsensus(0)
      proposal = LightTransactionsProposal(
        roundData.roundId,
        FacilitatorId(dao.id),
        transactions
          .map(_.hash) ++ arbitraryTransactions.filter(_._2 == 0).map(_._1.hash),
        messages
          .map(_.map(_.signedMessageData.hash))
          .getOrElse(Seq()) ++ arbitraryMessages
          .filter(_._2 == 0)
          .map(_._1.signedMessageData.hash),
        notifications,
        experiences.map(_.hash)
      )
      _ <- addTransactionProposal(proposal)
      _ <- remoteSender.broadcastLightTransactionProposal(
        BroadcastLightTransactionProposal(roundData.roundId, roundData.peers, proposal)
      )
    } yield ()

  def addBlockProposal(proposal: UnionBlockProposal): F[Unit] =
    for {
      _ <- verifyStage(
        Set(
          ConsensusStage.RESOLVING_MAJORITY_CB,
          ConsensusStage.WAITING_FOR_SELECTED_BLOCKS,
          ConsensusStage.ACCEPTING_MAJORITY_CB
        )
      )
      receivedAllBlockProposals <- checkpointBlockProposals.modify { curr =>
        val updated = curr + (proposal.facilitatorId -> proposal.checkpointBlock)
        (updated, receivedAllCheckpointBlockProposals(updated.size))
      }
      _ <- logger.debug(s"[${dao.id.short}] ${roundData.roundId} received block proposal $receivedAllBlockProposals")
      _ <- if (receivedAllBlockProposals)
        stage
          .set(ConsensusStage.RESOLVING_MAJORITY_CB)
          .flatMap(_ => validateAndMergeBlockProposals())
      else Sync[F].unit
    } yield ()

  def addTransactionProposal(proposal: LightTransactionsProposal): F[Unit] =
    for {
      _ <- verifyStage(
        Set(
          ConsensusStage.WAITING_FOR_BLOCK_PROPOSALS,
          ConsensusStage.RESOLVING_MAJORITY_CB,
          ConsensusStage.WAITING_FOR_SELECTED_BLOCKS,
          ConsensusStage.ACCEPTING_MAJORITY_CB
        )
      )
      receivedAllTransactionProposals <- transactionProposals.modify { curr =>
        val merged = if (curr.contains(proposal.facilitatorId)) {
          val old = curr(proposal.facilitatorId)
          old.copy(
            txHashes = old.txHashes ++ proposal.txHashes,
            messages = old.messages ++ proposal.messages,
            notifications = old.notifications ++ proposal.notifications
          )
        } else
          proposal
        val updated = curr + (proposal.facilitatorId -> merged)
        (updated, receivedAllTransactionProposals(updated.size))
      }
      _ <- logger.debug(
        s"[${dao.id.short}] ${roundData.roundId} received transaction proposal $receivedAllTransactionProposals"
      )
      _ <- if (receivedAllTransactionProposals)
        stage
          .set(ConsensusStage.WAITING_FOR_BLOCK_PROPOSALS)
          .flatMap(_ => unionTransactionProposals(StageState.FINISHED))
      else Sync[F].unit
    } yield ()

  def unionTransactionProposals(stageState: StageState): F[Unit] = {
    val action = stageState match {
      case StageState.BEHIND => mergeTxProposalsAndBroadcastBlock()
      case _                 => validateAndMergeTransactionProposals()
    }
    verifyStage(
      Set(
        ConsensusStage.RESOLVING_MAJORITY_CB,
        ConsensusStage.WAITING_FOR_SELECTED_BLOCKS,
        ConsensusStage.ACCEPTING_MAJORITY_CB
      )
    ).flatTap(_ => action)
  }

  private[consensus] def validateAndMergeBlockProposals(): F[Unit] =
    for {
      proposals <- checkpointBlockProposals.getUnsafe
      validationResult <- validateReceivedProposals(proposals, "blockProposals", countSelfAsPeer = roundStartedByMe)
      _ <- validationResult match {
        case Left(exception) => consensusManager.handleRoundError(exception)
        case Right(_)        => mergeBlockProposalsToMajorityBlock(proposals)
      }
    } yield ()

  private[consensus] def validateAndAcceptMajorityBlockProposals(): F[Unit] =
    for {
      proposals <- selectedCheckpointBlocks.getUnsafe
      validationResult <- validateReceivedProposals(proposals, "majorityProposals", 100, roundStartedByMe)
      _ <- validationResult match {
        case Left(exception) => consensusManager.handleRoundError(exception)
        case Right(_)        => acceptMajorityCheckpointBlock(proposals)
      }
    } yield ()

  def addSelectedBlockProposal(proposal: SelectedUnionBlock): F[Unit] =
    for {
      _ <- verifyStage(Set(ConsensusStage.ACCEPTING_MAJORITY_CB))

      receivedAllSelectedProposals <- selectedCheckpointBlocks.modify { curr =>
        val updated = curr + (proposal.facilitatorId -> proposal.checkpointBlock)
        (updated, receivedAllSelectedUnionBlocks(updated.size))
      }
      _ <- logger.debug(
        s"[${dao.id.short}] ${roundData.roundId} received selected proposal $receivedAllSelectedProposals"
      )
      _ <- if (receivedAllSelectedProposals)
        stage
          .set(ConsensusStage.ACCEPTING_MAJORITY_CB)
          .flatTap(_ => validateAndAcceptMajorityBlockProposals())
      else Sync[F].unit
    } yield ()

  private[consensus] def acceptMajorityCheckpointBlock(proposals: Map[FacilitatorId, CheckpointBlock]): F[Unit] = {
    val sameBlocks = proposals
      .groupBy(_._2.soeHash)
      .maxBy(_._2.size)
      ._2

    val checkpointBlock = sameBlocks.head._2
    val uniques = proposals.groupBy(_._2.baseHash).size

    val cache =
      CheckpointCache(Some(checkpointBlock), height = checkpointBlock.calculateHeight())

    for {
      _ <- dao.metrics.incrementMetricAsync(
        "acceptMajorityCheckpointBlockSelectedCount_" + proposals.size
      )
      _ <- dao.metrics.incrementMetricAsync(
        "acceptMajorityCheckpointBlockUniquesCount_" + uniques
      )
      _ <- logger.debug(
        s"[${dao.id.short}] accepting majority checkpoint block ${checkpointBlock.baseHash}  " +
          s" with txs ${checkpointBlock.transactions.map(_.hash)} " +
          s" with exs ${checkpointBlock.experiences.map(_.hash)} " +
          s"proposed by ${sameBlocks.head._1.id.short} other blocks ${sameBlocks.size} in round ${roundData.roundId} with soeHash ${checkpointBlock.soeHash} and parent ${checkpointBlock.parentSOEHashes} and height ${cache.height}"
      )
      acceptedBlock <- checkpointService
        .accept(cache)
        .map { _ =>
          (Option(checkpointBlock), Seq.empty[String])
        }
        .handleErrorWith {
          case error @ (CheckpointAcceptBlockAlreadyStored(_) | PendingAcceptance(_)) =>
            logger
              .warn(error.getMessage)
              .flatMap(_ => Sync[F].pure[(Option[CheckpointBlock], Seq[String])]((None, Seq.empty[String])))
          case tipConflict: TipConflictException =>
            logger
              .error(tipConflict)(
                s"[${dao.id.short}] Failed to accept majority checkpoint block due: ${tipConflict.getMessage}"
              )
              .flatMap(_ => Sync[F].pure[(Option[CheckpointBlock], Seq[String])]((None, tipConflict.conflictingTxs)))
          case unknownError =>
            logger
              .error(unknownError)(
                s"[${dao.id.short}] Failed to accept majority checkpoint block due: ${unknownError.getMessage}"
              )
              .flatMap(_ => Sync[F].pure[(Option[CheckpointBlock], Seq[String])]((None, Seq.empty[String])))
        }
      _ <- broadcastSignedBlockToNonFacilitators(FinishedCheckpoint(cache, proposals.keySet.map(_.id)))
      ownTransactions <- getOwnTransactionsToReturn
      ownExperiences <- getOwnExperiencesToReturn
      transactionsToReturn = ownTransactions
        .diff(acceptedBlock._1.map(_.transactions.map(_.hash)).getOrElse(Seq.empty))
        .filterNot(acceptedBlock._2.contains)
      experiencesToReturn = ownExperiences
        .diff(acceptedBlock._1.map(_.experiences.map(_.hash)).getOrElse(Seq.empty))
        .filterNot(acceptedBlock._2.contains)
      _ <- consensusManager.stopBlockCreationRound(
        StopBlockCreationRound(
          roundData.roundId,
          acceptedBlock._1,
          transactionsToReturn,
          experiencesToReturn
        )
      )
      _ <- logger.debug(
        s"[${dao.id.short}] round stopped ${roundData.roundId} block is empty ? ${acceptedBlock._1.isEmpty}"
      )

    } yield ()

  }

  private[consensus] def broadcastSignedBlockToNonFacilitators(
    finishedCheckpoint: FinishedCheckpoint
  ): F[List[Response[Unit]]] = {
    val allFacilitators = roundData.peers.map(p => p.peerMetadata.id -> p).toMap
    for {
      nonFacilitators <- LiftIO[F]
        .liftIO(dao.peerInfo)
        .map(info => info.values.toList.filterNot(pd => allFacilitators.contains(pd.peerMetadata.id)))
      responses <- LiftIO[F].liftIO(
        nonFacilitators.traverse(
          pd => pd.client.postNonBlockingIOUnit("finished/checkpoint", finishedCheckpoint, timeout = 10.seconds)
        )
      )
    } yield responses
  }

  private[consensus] def mergeBlockProposalsToMajorityBlock(
    proposals: Map[FacilitatorId, CheckpointBlock]
  ): F[Unit] = {
    val sameBlocks = proposals
      .groupBy(_._2.baseHash)
      .maxBy(_._2.size)
      ._2

    val uniques = proposals.groupBy(_._2.baseHash).size

    val checkpointBlock = sameBlocks.values.foldLeft(sameBlocks.head._2)(_ + _)
    val selectedCheckpointBlock =
      SelectedUnionBlock(roundData.roundId, FacilitatorId(dao.id), checkpointBlock)

    for {
      _ <- stage.set(ConsensusStage.WAITING_FOR_SELECTED_BLOCKS)
      _ <- dao.metrics.incrementMetricAsync(
        "resolveMajorityCheckpointBlockProposalCount_" + proposals.size
      )
      _ <- dao.metrics.incrementMetricAsync(
        "resolveMajorityCheckpointBlockUniquesCount_" + uniques
      )
      _ <- remoteSender.broadcastSelectedUnionBlock(
        BroadcastSelectedUnionBlock(roundData.roundId, roundData.peers, selectedCheckpointBlock)
      )
      _ <- addSelectedBlockProposal(selectedCheckpointBlock)
    } yield ()
  }

  private[consensus] def mergeTxProposalsAndBroadcastBlock(): F[Unit] =
    for {
      readyPeers <- LiftIO[F].liftIO(dao.readyPeers.map(_.mapValues(p => PeerApiClient(p.peerMetadata.id, p.client))))
      proposals <- transactionProposals.get
      idsTxs = (
        proposals.keySet,
        proposals.values.map(_.txHashes).toList.flatten.union(roundData.transactions.map(_.hash)).distinct
      )
      txs <- idsTxs._2.traverse(t => transactionService.lookup(t).map((t, _)))
      resolved <- txs
        .filter(_._2.isEmpty)
        .traverse(
          t =>
            LiftIO[F].liftIO(
              dataResolver
                .resolveTransactions(
                  t._1,
                  readyPeers.values.filter(r => idsTxs._1.contains(FacilitatorId(r.id))).toList,
                  None
                )
                .map(_.transaction)
            )
        )
      _ <- logger.debug(
        s"transactions proposal_size ${proposals.size} values size ${idsTxs._2.size} lookup size ${txs.size} resolved ${resolved.size}"
      )
      msgs <- proposals.values
        .flatMap(_.messages)
        .toList
        .traverse(m => messageService.lookup(m).map((m, _)))
      resolvedMsg <- msgs
        .filter(_._2.isEmpty)
        .traverse(
          t =>
            LiftIO[F].liftIO(
              dataResolver
                .resolveMessages(
                  t._1,
                  readyPeers.values.filter(r => idsTxs._1.contains(FacilitatorId(r.id))).toList,
                  None
                )
                .map(_.channelMessage)
            )
        )
      notifications = proposals
        .flatMap(_._2.notifications)
        .toSet
        .union(roundData.peers.flatMap(_.notification))
        .toSeq
      experiences <- proposals
        .flatMap(_._2.exHashes)
        .toList
        .traverse(e => experienceService.lookup(e).map((e, _)))
      resolvedExs <- experiences
        .filter(_._2.isEmpty)
        .traverse { ex =>
          LiftIO[F].liftIO(
            dataResolver.resolveExperience(
              ex._1,
              readyPeers.values.filter(r => idsTxs._1.contains(FacilitatorId(r.id))).toList,
              None
            )
          )
        }
      proposal = UnionBlockProposal(
        roundData.roundId,
        FacilitatorId(dao.id),
        CheckpointBlock.createCheckpointBlock(
          resolved ++ txs.flatMap(_._2.map(_.transaction)),
          roundData.tipsSOE.soe
            .map(soe => TypedEdgeHash(soe.hash, EdgeHashType.CheckpointHash, Some(soe.baseHash))),
          resolvedMsg.union(msgs.flatMap(_._2.map(_.channelMessage))).union(roundData.messages).distinct,
          notifications,
          experiences.flatMap(_._2) ++ resolvedExs
        )(dao.keyPair)
      )
      _ <- remoteSender.broadcastBlockUnion(
        BroadcastUnionBlockProposal(roundData.roundId, roundData.peers, proposal)
      )
      _ <- addBlockProposal(proposal)
    } yield ()

  private[consensus] def validateAndMergeTransactionProposals(): F[Unit] =
    for {
      proposals <- transactionProposals.getUnsafe
      validationResult <- validateReceivedProposals(
        proposals,
        "transactionProposals",
        countSelfAsPeer = roundStartedByMe
      )
      _ <- validationResult match {
        case Left(exception) => consensusManager.handleRoundError(exception)
        case Right(_)        => mergeTxProposalsAndBroadcastBlock()
      }
    } yield ()

  def verifyStage(forbiddenStages: Set[ConsensusStage]): F[Unit] =
    stage.get
      .flatMap(
        stage =>
          if (forbiddenStages.contains(stage))
            getOwnTransactionsToReturn
              .flatMap(
                txs =>
                  getOwnExperiencesToReturn.flatMap(
                    exs => consensusManager.handleRoundError(PreviousStage(roundData.roundId, stage, txs, exs))
                  )
              )
          else Sync[F].unit
      )

  private[consensus] def getOwnTransactionsToReturn: F[Seq[String]] =
    transactionProposals.get.map(_.get(FacilitatorId(dao.id)).map(_.txHashes).getOrElse(Seq.empty))

  private[consensus] def getOwnExperiencesToReturn: F[Seq[String]] =
    transactionProposals.get.map(_.get(FacilitatorId(dao.id)).map(_.exHashes).getOrElse(Seq.empty))

  private def roundStartedByMe: Boolean = roundData.facilitatorId.id == dao.id

  private[consensus] def receivedAllSelectedUnionBlocks(size: Int): Boolean =
    size == roundData.peers.size + 1

  private[consensus] def receivedAllCheckpointBlockProposals(size: Int): Boolean =
    size == roundData.peers.size + 1

  private[consensus] def receivedAllTransactionProposals(size: Int): Boolean = {
    val extraMessage = if (roundStartedByMe) 1 else 0
    size == roundData.peers.size + extraMessage
  }

  def validateReceivedProposals(
    proposals: Map[FacilitatorId, AnyRef],
    stage: String,
    minimumPercentage: Int = 51,
    countSelfAsPeer: Boolean = true
  ): F[Either[ConsensusException, Unit]] = {
    val peerSize = roundData.peers.size + (if (countSelfAsPeer) 1 else 0)
    val proposalPercentage: Float = proposals.size * 100 / peerSize
    (proposalPercentage, proposals.size) match {
      case (0, _) =>
        getOwnTransactionsToReturn.flatMap(
          txs => getOwnExperiencesToReturn.map(exs => Left(EmptyProposals(roundData.roundId, stage, txs, exs)))
        )
      case (_, size) if size == 1 =>
        getOwnTransactionsToReturn.flatMap(
          txs => getOwnExperiencesToReturn.map(exs => Left(EmptyProposals(roundData.roundId, stage, txs, exs)))
        )
      case (p, _) if p < minimumPercentage =>
        getOwnTransactionsToReturn.flatMap(
          txs =>
            getOwnExperiencesToReturn.map(
              exs =>
                Left(
                  NotEnoughProposals(roundData.roundId, proposals.size, peerSize, stage, txs, exs)
                )
            )
        )
      case _ => Sync[F].pure(Right(()))
    }
  }

}

object Consensus {
  sealed trait RoundCommand {
    def roundId: RoundId
  }
  abstract class ConsensusException(msg: String) extends Exception(msg) {
    def roundId: RoundId
    def transactionsToReturn: Seq[String]
    def experiencesToReturn: Seq[String]
  }

  object ConsensusStage extends Enumeration {
    type ConsensusStage = Value

    val STARTING, WAITING_FOR_PROPOSALS, WAITING_FOR_BLOCK_PROPOSALS, RESOLVING_MAJORITY_CB,
      WAITING_FOR_SELECTED_BLOCKS, ACCEPTING_MAJORITY_CB =
      Value
  }

  object StageState extends Enumeration {
    type StageState = Value
    val TIMEOUT, BEHIND, FINISHED = Value
  }

  case class FacilitatorId(id: Schema.Id) extends AnyVal
  case class RoundId(id: String) extends AnyVal

  case class UnionProposals(state: StageState)
  case class ArbitraryDataProposals(distance: Int)

  case class ResolveMajorityCheckpointBlock(roundId: RoundId, stageState: StageState) extends RoundCommand

  case class AcceptMajorityCheckpointBlock(roundId: RoundId) extends RoundCommand

  case class StartTransactionProposal(roundId: RoundId) extends RoundCommand

  case class LightTransactionsProposal(
    roundId: RoundId,
    facilitatorId: FacilitatorId,
    txHashes: Seq[String],
    messages: Seq[String] = Seq(),
    notifications: Seq[PeerNotification] = Seq(),
    exHashes: Seq[String] = Seq()
  ) extends RoundCommand

  case class UnionBlockProposal(
    roundId: RoundId,
    facilitatorId: FacilitatorId,
    checkpointBlock: CheckpointBlock
  ) extends RoundCommand

  case class RoundData(
    roundId: RoundId,
    peers: Set[PeerData],
    lightPeers: Set[PeerData],
    facilitatorId: FacilitatorId,
    transactions: List[Transaction],
    tipsSOE: TipSoe,
    messages: Seq[ChannelMessage],
    experiences: List[Experience]
  )

  case class StopBlockCreationRound(
    roundId: RoundId,
    maybeCB: Option[CheckpointBlock],
    transactionsToReturn: Seq[String],
    experiencesToReturn: Seq[String]
  ) extends RoundCommand

  case class EmptyProposals(
    roundId: RoundId,
    stage: String,
    transactionsToReturn: Seq[String],
    experiencesToReturn: Seq[String]
  ) extends ConsensusException(s"Proposals for stage: $stage and round: $roundId are empty.")

  case class PreviousStage(
    roundId: RoundId,
    stage: ConsensusStage,
    transactionsToReturn: Seq[String],
    experiencesToReturn: Seq[String]
  ) extends ConsensusException(s"Received message from previous round stage. Current round stage is $stage")

  case class NotEnoughProposals(
    roundId: RoundId,
    proposals: Int,
    facilitators: Int,
    stage: String,
    transactionsToReturn: Seq[String],
    experiencesToReturn: Seq[String]
  ) extends ConsensusException(
        s"Proposals number: $proposals for stage: $stage and round: $roundId are below given percentage. Number of facilitators: $facilitators"
      )

  case class SelectedUnionBlock(
    roundId: RoundId,
    facilitatorId: FacilitatorId,
    checkpointBlock: CheckpointBlock
  ) extends RoundCommand

}
