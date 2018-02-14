package BIDMach.allreduce

import akka.actor.{Actor, ActorRef}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


class AllreduceLineMaster(config: LineMasterConfig) extends Actor with akka.actor.ActorLogging {

  var gridMaster: Option[ActorRef] = None

  // worker discovery
  val workerResolutionTimeOut: FiniteDuration = config.workerResolutionTimeout
  var roundNum = config.roundWorkerPerDimNum //the number of rounds (lags) allowed
  var dim = config.dim

  // peer worker refs (map) for each round (array)
  var peerWorkersPerRound: Array[Map[Int, ActorRef]] = new Array(roundNum)

  // Round progression/completion conditions
  val maxRound = config.maxRound
  val thAllreduce = config.threshold.thAllreduce
  var peerNodesInLineNum = -1
  var completeCount = 0
  var round = -1

  var lineMasterVersion = -1

  def receive = {

    case c: CompleteAllreduce =>
      log.debug(s"\n----LineMaster ${self.path}: Node ${c.srcId} completes allreduce round ${c.config.round}")
      if (c.config.round == round) {
        completeCount += 1
        if (completeCount >= peerNodesInLineNum * thAllreduce && round < maxRound) {
          log.debug(s"\n----LineMaster ${self.path}: ${completeCount} (out of ${peerNodesInLineNum}) nodes complete round ${round}\n")
          round += 1
          startAllreduce()
        }
      }

    case s: StartAllreduceTask =>
      // Currently assumes here that start all reduce comes at once.
      log.debug(s"\n----LineMaster ${self.path}: Receive SlavesInfo from GridMaster.")
      gridMaster = Some(sender())
      lineMasterVersion = s.lineMasterVersion
      val peerNodeRefs = s.peerNodes
      peerNodesInLineNum = peerNodeRefs.size
      for (roundNth <- 0 until roundNum) {
        peerWorkersPerRound(roundNth) = discoverWorkerPeers(roundNth, peerNodeRefs.toArray)
      }
      round = 0
      startAllreduce()
  }

  private def startAllreduce() = {
    log.debug(s"\n----LineMaster ${self.path}: START ROUND ${round} at time ${System.currentTimeMillis} --------------------")
    completeCount = 0
    val peerWorkers = peerWorkersPerRound(timeIdx(round))
    for ((workerId, worker) <- peerWorkers) {
      worker ! StartAllreduce(RoundConfig(lineMasterVersion, round, self, peerWorkers, workerId))
    }
  }

  private def timeIdx(round: Int) = {
    round % roundNum
  }

  /**
    * Discover peers from given node refs and assign the worker id sequentially
    * @param round round at which worker is responsible for
    * @param nodeArray node references of root actor under which the peer workers live
    * @return map of worker id and its refs
    */
  private def discoverWorkerPeers(round: Int, nodeArray: Array[ActorRef]): Map[Int, ActorRef] = {
    val refFut: Seq[Future[(Int, ActorRef)]] = nodeArray.zipWithIndex.map {
      case (nodeRef, i) =>
        val assignedWorkerId = i
        //nodePath/worker-id-dim
        context.actorSelection(nodeRef.path / s"DimensionNode-dim=${dim}" / s"Worker-round=${round % roundNum}")
          .resolveOne(workerResolutionTimeOut)
          .map(ref => (assignedWorkerId, ref))

    }
    Await.result(Future.sequence(refFut), workerResolutionTimeOut).toMap
  }
}