package com.iheart.thomas.stream

import cats.effect.ConcurrentEffect
import fs2.{Pipe, Stream}
import ConversionBanditKPITracker._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.{Conversions, KPIName}
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.bandit.tracking.EventLogger

class ConversionBanditKPITracker[F[_]: ConcurrentEffect](
    implicit
    bmabAlg: ConversionBMABAlg[F],
    log: EventLogger[F]) {

  def updateAllConversions[I](
      chunkSize: Int,
      toEvent: (FeatureName, KPIName) => F[Pipe[F, I, (ArmName, ConversionEvent)]]
    ): F[Pipe[F, I, Unit]] = {
    def updateConversion(
        featureName: FeatureName
      ): Pipe[F, (ArmName, ConversionEvent), Unit] =
      ConversionBanditKPITracker.toConversion(chunkSize) andThen { input =>
        input.evalMap { r =>
          bmabAlg
            .updateRewardState(featureName, r)
            .void
        }
      }

    bmabAlg.runningBandits(None).flatMap { bandits =>
      bandits
        .traverse { b =>
          toEvent(b.feature, b.kpiName).map(_ andThen updateConversion(b.feature))
        }
        .map { featurePipes => input: Stream[F, I] =>
          input.broadcastThrough(featurePipes: _*)
        }
    }
  }

}

object ConversionBanditKPITracker {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false

  private[stream] def toConversion[F[_]](
      chunkSize: Int
    ): Pipe[F, (ArmName, ConversionEvent), Map[
    ArmName,
    Conversions
  ]] = { input =>
    input
      .chunkN(chunkSize, true)
      .map { chunk =>
        val isConverted = identity[ConversionEvent] _
        (chunk
          .foldMap {
            case (an, ce) =>
              Map(an -> List(ce))
          })
          .map {
            case (an, ces) =>
              val convertedCount = ces.count(isConverted)
              (
                an,
                Conversions(
                  converted = convertedCount.toLong,
                  total = ces.size.toLong
                )
              )
          }
      }
  }
}