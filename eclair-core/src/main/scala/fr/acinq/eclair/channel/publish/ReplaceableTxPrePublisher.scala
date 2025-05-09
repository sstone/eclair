/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.publish

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, Transaction}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.channel.{FullCommitment, HtlcTxAndRemoteSig}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateFulfillHtlc

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 20/12/2021.
 */

/**
 * This actor verifies that preconditions are met before attempting to publish a replaceable transaction.
 * It verifies for example that we're not trying to publish htlc transactions while the remote commitment has already
 * been confirmed, or that we have all the data necessary to sign transactions.
 */
object ReplaceableTxPrePublisher {

  // @formatter:off
  sealed trait Command
  case class CheckPreconditions(replyTo: ActorRef[PreconditionsResult], cmd: TxPublisher.PublishReplaceableTx) extends Command

  private case object ParentTxOk extends Command
  private case object FundingTxNotFound extends RuntimeException with Command
  private case object CommitTxAlreadyConfirmed extends RuntimeException with Command
  private case object LocalCommitTxConfirmed extends Command
  private case object LocalCommitTxPublished extends Command
  private case object RemoteCommitTxConfirmed extends Command
  private case object RemoteCommitTxPublished extends RuntimeException with Command
  private case object HtlcOutputAlreadySpent extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait PreconditionsResult
  case class PreconditionsOk(txWithWitnessData: ReplaceableTxWithWitnessData) extends PreconditionsResult
  case class PreconditionsFailed(reason: TxPublisher.TxRejectedReason) extends PreconditionsResult

  /** Replaceable transaction with all the witness data necessary to finalize. */
  sealed trait ReplaceableTxWithWitnessData {
    def txInfo: ReplaceableTransactionWithInputInfo
    def updateTx(tx: Transaction): ReplaceableTxWithWitnessData
  }
  /** Replaceable transaction for which we may need to add wallet inputs. */
  sealed trait ReplaceableTxWithWalletInputs extends ReplaceableTxWithWitnessData {
    override def updateTx(tx: Transaction): ReplaceableTxWithWalletInputs
  }
  case class ClaimAnchorWithWitnessData(txInfo: ClaimAnchorOutputTx) extends ReplaceableTxWithWalletInputs {
    override def updateTx(tx: Transaction): ClaimAnchorWithWitnessData = this.copy(txInfo = this.txInfo.copy(tx = tx))
  }
  sealed trait HtlcWithWitnessData extends ReplaceableTxWithWalletInputs {
    override def txInfo: HtlcTx
    override def updateTx(tx: Transaction): HtlcWithWitnessData
  }
  case class HtlcSuccessWithWitnessData(txInfo: HtlcSuccessTx, remoteSig: ByteVector64, preimage: ByteVector32) extends HtlcWithWitnessData {
    override def updateTx(tx: Transaction): HtlcSuccessWithWitnessData = this.copy(txInfo = this.txInfo.copy(tx = tx))
  }
  case class HtlcTimeoutWithWitnessData(txInfo: HtlcTimeoutTx, remoteSig: ByteVector64) extends HtlcWithWitnessData {
    override def updateTx(tx: Transaction): HtlcTimeoutWithWitnessData = this.copy(txInfo = this.txInfo.copy(tx = tx))
  }
  sealed trait ClaimHtlcWithWitnessData extends ReplaceableTxWithWitnessData {
    override def txInfo: ClaimHtlcTx
    override def updateTx(tx: Transaction): ClaimHtlcWithWitnessData
  }
  case class ClaimHtlcSuccessWithWitnessData(txInfo: ClaimHtlcSuccessTx, preimage: ByteVector32) extends ClaimHtlcWithWitnessData {
    override def updateTx(tx: Transaction): ClaimHtlcSuccessWithWitnessData = this.copy(txInfo = this.txInfo.copy(tx = tx))
  }
  case class ClaimHtlcTimeoutWithWitnessData(txInfo: ClaimHtlcTimeoutTx) extends ClaimHtlcWithWitnessData {
    override def updateTx(tx: Transaction): ClaimHtlcTimeoutWithWitnessData = this.copy(txInfo = this.txInfo.copy(tx = tx))
  }
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(txPublishContext.mdc()) {
        Behaviors.receiveMessagePartial {
          case CheckPreconditions(replyTo, cmd) =>
            val prePublisher = new ReplaceableTxPrePublisher(nodeParams, replyTo, cmd, bitcoinClient, context)
            cmd.txInfo match {
              case localAnchorTx: Transactions.ClaimAnchorOutputTx => prePublisher.checkAnchorPreconditions(localAnchorTx)
              case htlcTx: Transactions.HtlcTx => prePublisher.checkHtlcPreconditions(htlcTx)
              case claimHtlcTx: Transactions.ClaimHtlcTx => prePublisher.checkClaimHtlcPreconditions(claimHtlcTx)
            }
        }
      }
    }
  }

}

private class ReplaceableTxPrePublisher(nodeParams: NodeParams,
                                        replyTo: ActorRef[ReplaceableTxPrePublisher.PreconditionsResult],
                                        cmd: TxPublisher.PublishReplaceableTx,
                                        bitcoinClient: BitcoinCoreClient,
                                        context: ActorContext[ReplaceableTxPrePublisher.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPrePublisher._

  private val log = context.log

  private def checkAnchorPreconditions(localAnchorTx: ClaimAnchorOutputTx): Behavior[Command] = {
    // We verify that:
    //  - our commit is not confirmed (if it is, no need to claim our anchor)
    //  - their commit is not confirmed (if it is, no need to claim our anchor either)
    val fundingOutpoint = cmd.commitment.commitInput.outPoint
    context.pipeToSelf(bitcoinClient.getTxConfirmations(fundingOutpoint.txid).flatMap {
      case Some(_) =>
        // The funding transaction was found, let's see if we can still spend it.
        bitcoinClient.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = false).flatMap {
          case false => Future.failed(CommitTxAlreadyConfirmed)
          case true if cmd.isLocalCommitAnchor =>
            // We are trying to bump our local commitment. Let's check if the remote commitment is published: if it is,
            // we will skip publishing our local commitment, because the remote commitment is more interesting (we don't
            // have any CSV delays and don't need 2nd-stage HTLC transactions).
            getRemoteCommitConfirmations(cmd.commitment).flatMap {
              case Some(_) => Future.failed(RemoteCommitTxPublished)
              // We're trying to bump the local commit tx: no need to do anything, we will publish it alongside the anchor transaction.
              case None => Future.successful(cmd.commitTx.txid)
            }
          case true =>
            // We're trying to bump a remote commitment: no need to do anything, we will publish it alongside the anchor transaction.
            Future.successful(cmd.commitTx.txid)
        }
      case None =>
        // If the funding transaction cannot be found (e.g. when using 0-conf), we should retry later.
        Future.failed(FundingTxNotFound)
    }) {
      case Success(_) => ParentTxOk
      case Failure(FundingTxNotFound) => FundingTxNotFound
      case Failure(CommitTxAlreadyConfirmed) => CommitTxAlreadyConfirmed
      case Failure(RemoteCommitTxPublished) => RemoteCommitTxPublished
      case Failure(reason) if reason.getMessage.contains("rejecting replacement") => RemoteCommitTxPublished
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        replyTo ! PreconditionsOk(ClaimAnchorWithWitnessData(localAnchorTx))
        Behaviors.stopped
      case FundingTxNotFound =>
        log.debug("funding tx could not be found, we don't know yet if we need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case CommitTxAlreadyConfirmed =>
        log.debug("commit tx is already confirmed, no need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case RemoteCommitTxPublished =>
        log.warn("not publishing local commit tx: we're using the remote commit tx instead")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our commit tx.
        replyTo ! PreconditionsOk(ClaimAnchorWithWitnessData(localAnchorTx))
        Behaviors.stopped
    }
  }

  private def getRemoteCommitConfirmations(commitment: FullCommitment): Future[Option[Int]] = {
    bitcoinClient.getTxConfirmations(commitment.remoteCommit.txid).transformWith {
      // NB: this handles the case where the remote commit is in the mempool because we will get Some(0).
      case Success(Some(remoteCommitConfirmations)) => Future.successful(Some(remoteCommitConfirmations))
      case notFoundOrFailed => commitment.nextRemoteCommit_opt match {
        case Some(nextRemoteCommit) => bitcoinClient.getTxConfirmations(nextRemoteCommit.commit.txid)
        case None => Future.fromTry(notFoundOrFailed)
      }
    }
  }

  /**
   * We verify that:
   *  - their commit is not confirmed: if it is, there is no need to publish our htlc transactions
   *  - the HTLC output isn't already spent by a confirmed transaction (race between HTLC-timeout and HTLC-success)
   */
  private def checkHtlcOutput(commitment: FullCommitment, htlcTx: HtlcTx): Future[Command] = {
    getRemoteCommitConfirmations(commitment).flatMap {
      case Some(depth) if depth >= nodeParams.channelConf.minDepth => Future.successful(RemoteCommitTxConfirmed)
      case Some(_) => Future.successful(RemoteCommitTxPublished)
      case _ => bitcoinClient.isTransactionOutputSpent(htlcTx.input.outPoint.txid, htlcTx.input.outPoint.index.toInt).map {
        case true => HtlcOutputAlreadySpent
        case false => ParentTxOk
      }
    }
  }

  private def checkHtlcPreconditions(htlcTx: HtlcTx): Behavior[Command] = {
    context.pipeToSelf(checkHtlcOutput(cmd.commitment, htlcTx)) {
      case Success(result) => result
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        // We make sure that if this is an htlc-success transaction, we have the preimage.
        extractHtlcWitnessData(htlcTx, cmd.commitment) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case RemoteCommitTxPublished =>
        log.info("cannot publish {}: remote commit has been published", cmd.desc)
        // We keep retrying until the remote commit reaches min-depth to protect against reorgs.
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case RemoteCommitTxConfirmed =>
        log.warn("cannot publish {}: remote commit has been confirmed", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case HtlcOutputAlreadySpent =>
        log.warn("cannot publish {}: htlc output has already been spent", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our htlc transactions.
        extractHtlcWitnessData(htlcTx, cmd.commitment) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
    }
  }

  private def extractHtlcWitnessData(htlcTx: HtlcTx, commitment: FullCommitment): Option[ReplaceableTxWithWitnessData] = {
    htlcTx match {
      case tx: HtlcSuccessTx =>
        commitment.localCommit.htlcTxsAndRemoteSigs.collectFirst {
          case HtlcTxAndRemoteSig(HtlcSuccessTx(input, _, _, _, _), remoteSig) if input.outPoint == tx.input.outPoint => remoteSig
        } match {
          case Some(remoteSig) =>
            commitment.changes.localChanges.all.collectFirst {
              case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
            } match {
              case Some(preimage) => Some(HtlcSuccessWithWitnessData(tx, remoteSig, preimage))
              case None =>
                log.error(s"preimage not found for htlcId=${tx.htlcId}, skipping...")
                None
            }
          case None =>
            log.error(s"remote signature not found for htlcId=${tx.htlcId}, skipping...")
            None
        }
      case tx: HtlcTimeoutTx =>
        commitment.localCommit.htlcTxsAndRemoteSigs.collectFirst {
          case HtlcTxAndRemoteSig(HtlcTimeoutTx(input, _, _, _), remoteSig) if input.outPoint == tx.input.outPoint => remoteSig
        } match {
          case Some(remoteSig) => Some(HtlcTimeoutWithWitnessData(tx, remoteSig))
          case None =>
            log.error(s"remote signature not found for htlcId=${tx.htlcId}, skipping...")
            None
        }
    }
  }

  /**
   * We verify that:
   *  - our commit is not confirmed: if it is, there is no need to publish our claim-htlc transactions
   *  - the HTLC output isn't already spent by a confirmed transaction (race between HTLC-timeout and HTLC-success)
   */
  private def checkClaimHtlcOutput(commitment: FullCommitment, claimHtlcTx: ClaimHtlcTx): Future[Command] = {
    bitcoinClient.getTxConfirmations(commitment.localCommit.commitTxAndRemoteSig.commitTx.tx.txid).flatMap {
      case Some(depth) if depth >= nodeParams.channelConf.minDepth => Future.successful(LocalCommitTxConfirmed)
      case Some(_) => Future.successful(LocalCommitTxPublished)
      case _ => bitcoinClient.isTransactionOutputSpent(claimHtlcTx.input.outPoint.txid, claimHtlcTx.input.outPoint.index.toInt).map {
        case true => HtlcOutputAlreadySpent
        case false => ParentTxOk
      }
    }
  }

  private def checkClaimHtlcPreconditions(claimHtlcTx: ClaimHtlcTx): Behavior[Command] = {
    context.pipeToSelf(checkClaimHtlcOutput(cmd.commitment, claimHtlcTx)) {
      case Success(result) => result
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        // We verify that if this is a claim-htlc-success transaction, we have the preimage.
        extractClaimHtlcWitnessData(claimHtlcTx, cmd.commitment) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case LocalCommitTxPublished =>
        log.info("cannot publish {}: local commit has been published", cmd.desc)
        // We keep retrying until the local commit reaches min-depth to protect against reorgs.
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case LocalCommitTxConfirmed =>
        log.warn("cannot publish {}: local commit has been confirmed", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case HtlcOutputAlreadySpent =>
        log.warn("cannot publish {}: htlc output has already been spent", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our htlc transactions.
        extractClaimHtlcWitnessData(claimHtlcTx, cmd.commitment) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
    }
  }

  private def extractClaimHtlcWitnessData(claimHtlcTx: ClaimHtlcTx, commitment: FullCommitment): Option[ReplaceableTxWithWitnessData] = {
    claimHtlcTx match {
      case tx: ClaimHtlcSuccessTx =>
        commitment.changes.localChanges.all.collectFirst {
          case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
        } match {
          case Some(preimage) => Some(ClaimHtlcSuccessWithWitnessData(tx, preimage))
          case None =>
            log.error(s"preimage not found for htlcId=${tx.htlcId}, skipping...")
            None
        }
      case tx: ClaimHtlcTimeoutTx => Some(ClaimHtlcTimeoutWithWitnessData(tx))
    }
  }

}
