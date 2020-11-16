package org.constellation.trust

import cats.effect.Concurrent
import cats.effect.concurrent.Ref

import scala.util.Random

case class TrustEdge(src: Int, dst: Int, trust: Double, isLabel: Boolean = false) {
  def other(id: Int): Int = Seq(src, dst).filterNot(_ == id).head
}

// Simple way to simulate modularity of connections / generate a topology different from random
case class TrustNode(id: Int, xCoordinate: Double, yCoordinate: Double, edges: Seq[TrustEdge] = Seq()) {

  def distance(other: TrustNode): Double =
    Math.sqrt {
      Math.pow(xCoordinate - other.xCoordinate, 2) +
        Math.pow(yCoordinate - other.yCoordinate, 2)
    } / DataGeneration.sqrt2

  def negativeEdges: Seq[TrustEdge] = edges.filter(_.trust < 0)

  def normalizedPositiveEdges(visited: Set[Int]): Map[Int, Double] = {
    val positiveSubset = positiveEdges.filterNot { e =>
      visited.contains(e.dst)
    }
    if (positiveSubset.isEmpty) Map.empty[Int, Double]
    else {
      val total = positiveSubset.map {
        _.trust
      }.sum
      val normalized = positiveSubset.map { edge =>
        edge.dst -> (edge.trust / total)
      }.toMap
      // println("Normalized sum: " + normalized.values.sum)
      normalized
    }
  }

  def positiveEdges: Seq[TrustEdge] = edges.filter(_.trust > 0)

}

class DataGeneration[F[_]: Concurrent] {
  private final val randomEffect: Ref[F, Random] = Ref.unsafe(new Random())

  def randomEdgeLogic(random: Random = new Random())(distance: Double): Boolean =
    random.nextDouble() > distance && random.nextDouble() < 0.5

  def randomEdge(random: Random = new Random())(logic: Double => Boolean = randomEdgeLogic(random))(n: TrustNode, n2: TrustNode) =
    if (logic(n.id)) {
      val trustZeroToOne = random.nextDouble()
      Some(TrustEdge(n.id, n2.id, 2 * (trustZeroToOne - 0.5), isLabel = true))
    } else None

  def seedCliqueLogic(maxSeedNodeIdx: Int = 1)(id: Double): Boolean =
    if (id <= maxSeedNodeIdx) true
    else false

  def cliqueEdge(random: Random = new Random())(logic: Double => Boolean = seedCliqueLogic())(n: TrustNode, n2: TrustNode) =
    if (logic(n.distance(n2))) Some(TrustEdge(n.id, n2.id, 1.0, isLabel = true))
    else {
      val trustZeroToOne = random.nextDouble()
      Some(TrustEdge(n.id, n2.id, 2 * (trustZeroToOne - 0.5)))
    }


  def generateData(numNodes: Int = 30,
                     edgeLogic: (TrustNode, TrustNode) => Option[TrustEdge] = randomEdge()()) =
      for {
        random <- randomEffect.modify(a => (a, a))
      } yield ()
  //  def generateData(numNodes: Int = 30,
//                   edgeLogic: (TrustNode, TrustNode) => Option[TrustEdge] = randomEdge()()) = {
//    for {
//      random <- randomEffect.modify(a => (a, a))
//    } yield ()
//    val nodes = (0 until numNodes).toList.map { id =>
//      val test = randomEffect.get
//        val t = test..map(r => TrustNode(id, random.nextDouble(), random.nextDouble()))
//    }
//    val nodesWithEdges = nodes.map { n =>
//      val edges = nodes.filterNot(_.id == n.id).flatMap { n2 =>
//        edgeLogic(n, n2)
//      }
//      n.copy(edges = edges)
//    }
//    nodesWithEdges
//  }

  def bipartiteEdge(random: Random = new Random())(logic: Double => Boolean = seedCliqueLogic())(n: TrustNode, n2: TrustNode) =
    if (logic(n.id)) Some(TrustEdge(n.id, n2.id, 1.0, isLabel = true))
    else Some(TrustEdge(n.id, n2.id, -1.0))
}