package BIDMach.allreduce


import BIDMach.allreduce.buffer.{ReducedDataBuffer, ScatteredDataBuffer}
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext.Implicits.global


import scala.collection.mutable
import scala.concurrent.{Await, Future}


class AllreduceWorker(config: WorkerConfig,
                      dataSource: AllReduceInputRequest => AllReduceInput,
                      dataSink: AllReduceOutput => Unit) extends Actor with akka.actor.ActorLogging {

  val thReduce = config.threshold.thReduce // pct of scattered data needed to start reducing
  val thComplete = config.threshold.thComplete // pct of reduced data needed to complete current round

  val dataSize = config.metaData.dataSize
  val maxChunkSize = config.metaData.maxChunkSize;
  val workerNum = config.workerNum
  val workerDiscoveryTimeout = config.discoveryTimeout


  var master: Option[ActorRef] = None
  var peers = Map[Int, ActorRef]() // workers in the same row/col, including self
  var peerNum = 0

  var nodeId = -1
  var currentRound = -1
  var isCompleted = true

  // Data
  var data: Array[Float] = new Array(dataSize)
  var dataRange: Array[Int] = Array.empty
  var maxBlockSize = 0
  var minBlockSize = 0
  var myBlockSize = 0

  // Buffer
  var scatterBlockBuf: ScatteredDataBuffer = ScatteredDataBuffer.empty // store scattered data received
  var reduceBlockBuf: ReducedDataBuffer = ReducedDataBuffer.empty // store reduced data received

  // Output
  var output: Array[Float] = new Array(dataSize)
  var outputCount: Array[Int] = new Array(dataSize)

  println(s"\n----Actor id = ${nodeId}")
  println(s"\n----Thresholds: thReduce = ${thReduce}, thComplete = ${thComplete}");

  def receive = {

    case p: PrepareAllreduce => {

      log.info(s"\n----recieve prepare data round ${p.round}")


      try {
        
        assert(p.round > currentRound)

        if (!isCompleted) {

          log.warning(s"Force completing round ${p.round}")
          val unreducedChunkIds = scatterBlockBuf.getUnreducedChunkIds()
          for (i <- unreducedChunkIds) {
            reduceAndBroadcast(i)
          }
          completeRound()
        }

        // TODO: to reconsider potential bugs of changing master
        master = Some(sender())

        // prepare data meta-data
        peerNum = p.nodeAddresses.size;
        nodeId = p.nodeId
        peers = p.nodeAddresses

        dataRange = initDataBlockRanges();
        myBlockSize = blockSize(nodeId);
        maxBlockSize = blockSize(0);
        minBlockSize = blockSize(peerNum - 1);

        // start new round
        scatterBlockBuf = ScatteredDataBuffer(
          dataSize = myBlockSize,
          peerSize = peerNum,
          maxLag = 1,
          reducingThreshold = thReduce,
          maxChunkSize = maxChunkSize
        )

        reduceBlockBuf = ReducedDataBuffer(
          maxBlockSize = maxBlockSize,
          minBlockSize = minBlockSize,
          totalDataSize = dataSize,
          peerSize = peerNum,
          maxLag = 1,
          completionThreshold = thComplete,
          maxChunkSize = maxChunkSize
        )
        print(s"Seding cofirm to master: $master....")

        master.orNull ! ConfirmPreparation(p.round)

        // prepare state for new round
        currentRound = p.round
        isCompleted = false

        println(s"\n----Prepare round ${p.round}")
        println(s"\n----Size of scatter buffer: ${scatterBlockBuf.maxLag} x ${scatterBlockBuf.peerSize} x ${scatterBlockBuf.dataSize}. threshold : ${scatterBlockBuf.minChunkRequired}")
        println(s"\n----Size of reduce buffer: ${reduceBlockBuf.maxLag} x ${reduceBlockBuf.peerSize} x ${reduceBlockBuf.maxBlockSize}. threshold: ${reduceBlockBuf.minChunkRequired}")

      } catch {
        case e: Throwable => printStackTrace("prepare block", e);
      }
    }

    case s: StartAllreduce => {
      try {
        assert(s.round == currentRound)

        // discover workers
        val addressesFut: List[Future[(Int, ActorRef)]] = peers.toList.map {
          case (peerId:Int, peerAddress: ActorRef) =>
            val path = peerAddress.path / s"worker-${s.round % workerNum}"
            println(s"Path to query ref: $path")
            context.actorSelection(path)
              .resolveOne(workerDiscoveryTimeout)
              .map(ref => (peerId, ref))
        }
        Future.sequence(addressesFut).map( res => {
          peers = res.toMap
          fetch()
          scatter()
        })

      } catch {
        case e: Throwable => printStackTrace("start all reduce", e);
      }
    }

    case s: ScatterBlock => {
      try {
        log.debug(s"\n----receive scattered data from round ${s.round} srcId = ${s.srcId}, destId = ${s.destId}, chunkId=${s.chunkId}")
        handleScatterBlock(s);
      } catch {
        case e: Throwable => printStackTrace("scatter block", e);
      }
    }

    case r: ReduceBlock => {
      try {
        log.debug(s"\n----Receive reduced data from round ${r.round}, srcId = ${r.srcId}, destId = ${r.destId}, chunkId=${r.chunkId}")
        handleReduceBlock(r);
      } catch {
        case e: Throwable => printStackTrace("reduce block", e);
      }
    }

    case Terminated(a) =>
      for ((idx, worker) <- peers) {
        if (worker == a) {
          peers -= idx
        }
      }
  }

  private def handleReduceBlock(r: ReduceBlock) = {
    if (r.value.size > maxChunkSize) {
      throw new RuntimeException(s"Reduced block of size ${r.value.size} is larger than expected.. Max msg size is $maxChunkSize")
    } else if (r.destId != nodeId) {
      throw new RuntimeException(s"Message with destination ${r.destId} was incorrectly routed to node $nodeId")
    } else if (r.round > currentRound) {
      throw new RuntimeException(s"New round ${r.round} should have been prepared, but current round is $currentRound")
    }

    if (r.round < currentRound) {
      log.debug(s"\n----Outdated reduced data")
    } else {
      reduceBlockBuf.store(r.value, r.round, r.srcId, r.chunkId, r.count)
      if (reduceBlockBuf.reachCompletionThreshold(r.round)) {
        log.debug(s"\n----Receive enough reduced data (numPeers = ${peers.size}) for round ${r.round}, complete")
        completeRound()
      }
    }
  }

  private def handleScatterBlock(s: ScatterBlock) = {

    if (s.destId != nodeId) {
      throw new RuntimeException(s"Scatter block should be directed to $nodeId, but received ${s.destId}")
    } else if (s.round > currentRound) {
      throw new RuntimeException(s"New round ${s.round} should have been prepared, but current round is $currentRound")
    }

    if (s.round < currentRound) {
      log.debug(s"\n----Outdated scattered data")
    } else {
      scatterBlockBuf.store(s.value, s.round, s.srcId, s.chunkId)
      if (scatterBlockBuf.reachReducingThreshold(s.round, s.chunkId)) {
        log.debug(s"\n----receive ${scatterBlockBuf.count(s.round, s.chunkId)} scattered data (numPeers = ${peers.size}), chunkId =${s.chunkId} for round ${s.round}, start reducing")
        reduceAndBroadcast(s.chunkId)
      }
    }

  }

  private def blockSize(id: Int) = {
    val (start, end) = range(id)
    end - start
  }

  private def initDataBlockRanges() = {
    val stepSize = math.ceil(dataSize * 1f / peerNum).toInt
    Array.range(0, dataSize, stepSize)
  }

  private def range(idx: Int): (Int, Int) = {
    if (idx >= peerNum - 1)
      (dataRange(idx), dataSize)
    else
      (dataRange(idx), dataRange(idx + 1))
  }

  private def fetch() = {
    log.debug(s"\nfetch ${currentRound}")
    val input = dataSource(AllReduceInputRequest(currentRound))
    if (dataSize != input.data.size) {
      throw new IllegalArgumentException(s"\nInput data size ${input.data.size} is different from initialization time $dataSize!")
    }
    data = input.data
  }

  private def flush() = {
    reduceBlockBuf.getWithCounts(currentRound, output, outputCount)
    log.debug(s"\n----Flushing output at completed round $currentRound")
    dataSink(AllReduceOutput(output, outputCount, currentRound))
  }

  private def scatter() = {
    for (peerId <- 0 until peerNum) {
      val idx = (peerId + nodeId) % peerNum
      val worker = peers(idx)
      //Partition the dataBlock if it is too big
      val (blockStart, blockEnd) = range(idx)
      val peerBlockSize = blockEnd - blockStart
      val peerNumChunks = math.ceil(1f * peerBlockSize / maxChunkSize).toInt
      for (i <- 0 until peerNumChunks) {
        val chunkStart = math.min(i * maxChunkSize, peerBlockSize - 1);
        val chunkEnd = math.min((i + 1) * maxChunkSize - 1, peerBlockSize - 1);
        val chunkSize = chunkEnd - chunkStart + 1
        val chunk: Array[Float] = new Array(chunkSize)

        System.arraycopy(data, blockStart + chunkStart, chunk, 0, chunkSize);
        log.debug(s"\n----send msg from ${nodeId} to ${idx}, chunkId: ${i}")
        val scatterMsg = ScatterBlock(chunk, nodeId, idx, i, currentRound)
        if (worker == self) {
          handleScatterBlock(scatterMsg)
        } else {
          worker ! scatterMsg
        }
      }
    }
  }

  private def reduceAndBroadcast(chunkId: Int) = {
    val (reducedData, reduceCount) = scatterBlockBuf.reduce(currentRound, chunkId)
    broadcast(reducedData, chunkId, reduceCount)
  }

  private def broadcast(data: Array[Float], chunkId: Int, reduceCount: Int) = {
    log.debug(s"\n----Start broadcasting")
    for (i <- 0 until peerNum) {
      val peerNodeId = (i + nodeId) % peerNum
      val worker = peers(peerNodeId)
      log.debug(s"\n----Broadcast reduced block src: ${nodeId}, dest: ${peerNodeId}, chunkId: ${chunkId}, round: ${currentRound}")
      val reduceMsg = ReduceBlock(data, nodeId, peerNodeId, chunkId, currentRound, reduceCount)
      if (worker == self) {
        handleReduceBlock(reduceMsg)
      } else {
        worker ! reduceMsg
      }
    }
  }

  private def completeRound() = {
    flush()
    master.orNull ! CompleteAllreduce(nodeId, currentRound)
    isCompleted = true
  }

  private def printStackTrace(location: String, e: Throwable): Unit = {
    import java.io.PrintWriter
    import java.io.StringWriter
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    val stackTrace = sw.toString
    println(e, s"error in $location, $stackTrace")
  }
}

object AllreduceWorker {

  type DataSink = AllReduceOutput => Unit
  type DataSource = AllReduceInputRequest => AllReduceInput


  def main(args: Array[String]): Unit = {
    val port = if (args.isEmpty) "2553" else args(0)
    val sourceDataSize = if (args.length <= 1) 10 else args(1).toInt

    initWorker(port, sourceDataSize)
  }

  private def initWorker(port: String, sourceDataSize: Int, checkpoint: Int = 50, assertCorrectness: Boolean = false) = {
    val config = ConfigFactory.parseString(s"\nakka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [worker]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)

    val (source, sink) = if (assertCorrectness) {
      testCorrectnessSourceSink(sourceDataSize, checkpoint)
    } else {
      testPerformanceSourceSink(sourceDataSize, checkpoint)
    }

    system.actorOf(Props(classOf[AllreduceWorker], source, sink), name = "worker")
  }

  private def testCorrectnessSourceSink(sourceDataSize: Int, checkpoint: Int) = {

    val random = new scala.util.Random(100)
    val totalInputSample = 8

    lazy val randomFloats = {
      val nestedArray = new Array[Array[Float]](totalInputSample)
      for (i <- 0 until totalInputSample) {
        nestedArray(i) = Array.range(0, sourceDataSize).toList.map(_ => random.nextFloat()).toArray
      }
      nestedArray
    }

    def ~=(x: Double, y: Double, precision: Double = 1e-5) = {
      if ((x - y).abs < precision) true else false
    }

    // Specify data source
    val inputSet = mutable.HashSet[Int]()
    val source: DataSource = r => {
      assert(!inputSet.contains(r.iteration), s"Same data ${r.iteration} is being requested more than once")
      inputSet.add(r.iteration)
      AllReduceInput(randomFloats(r.iteration % totalInputSample))
    }

    // Specify data sink
    val outputSet = mutable.HashSet[Int]()

    val sink: DataSink = r => {
      assert(!outputSet.contains(r.iteration), s"Output data ${r.iteration} is being flushed more than once")
      outputSet.add(r.iteration)

      if (r.iteration % checkpoint == 0) {
        val inputUsed = randomFloats(r.iteration % totalInputSample)
        println(s"\n----Asserting #${r.iteration} output...")
        var zeroCountNum = 0
        var totalCount = 0
        for (i <- 0 until sourceDataSize) {
          val count = r.count(i)
          val meanActual = r.data(i) / count
          totalCount += count
          if (count == 0) {
            zeroCountNum += 1
          } else {
            val expected = inputUsed(i)
            assert(~=(expected, meanActual), s"Expected [$expected], but actual [$meanActual] at pos $i for iteraton #${r.iteration}")
          }
        }
        val nonZeroCountElementNum = sourceDataSize - zeroCountNum
        println("OK: Mean of non-zero elements match the expected input!")
        println(f"Element with non-zero counts: ${nonZeroCountElementNum / sourceDataSize.toFloat}%.2f ($nonZeroCountElementNum/$sourceDataSize)")
        println(f"Average count value: ${totalCount / nonZeroCountElementNum.toFloat}%2.2f ($totalCount/$nonZeroCountElementNum)")
      }
    }

    (source, sink)
  }


  private def testPerformanceSourceSink(sourceDataSize: Int, checkpoint: Int): (DataSource, DataSink) = {

    lazy val floats = Array.range(0, sourceDataSize).map(_.toFloat)
    val source: DataSource = _ => AllReduceInput(floats)

    var cumulativeThroughput: Double = 0
    var measurementCount: Int = 0
    val initialDiscard: Int = 10

    var tic = System.currentTimeMillis()
    val sink: DataSink = r => {
      if (r.iteration % checkpoint == 0 && r.iteration != 0) {

        val timeElapsed = (System.currentTimeMillis() - tic) / 1.0e3

        println(s"----Data output at #${r.iteration} - $timeElapsed s")
        val bytes = r.data.length * 4.0 * checkpoint
        val mBytes = bytes / 1.0e6
        val throughput = mBytes / timeElapsed

        val report = f"$mBytes%2.1f Mbytes in $timeElapsed%2.1f seconds at $throughput%4.3f MBytes/sec"

        measurementCount += 1

        val avgReport = if (measurementCount > initialDiscard) {
          cumulativeThroughput += throughput
          val effectiveCount = measurementCount - initialDiscard
          val avgThroughput = cumulativeThroughput / effectiveCount
          f", mean throughput at $avgThroughput%4.3f MBytes/sec from $effectiveCount samples"
        } else ""

        println(s"$report$avgReport")

        tic = System.currentTimeMillis()
      }
    }


    (source, sink)
  }


  def startUp(port: String) = {
    main(Array(port))
  }

  /**
    * Test start up method
    *
    * @param port              port number
    * @param dataSize          number of elements in input array from each node to be reduced
    * @param checkpoint        interval at which timing is calculated
    * @param assertCorrectness expected multiple of input as reduced results
    * @return
    */
  def startUp(port: String, dataSize: Int, checkpoint: Int = 50, assertCorrectness: Boolean = false) = {
    initWorker(port, dataSize, checkpoint, assertCorrectness)
  }

}