// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.model

import cats.Monoid
import cats.effect.Sync
import cats.syntax.functor._
import cats.syntax.flatMap._

import scala.concurrent.duration._
import scala.util.Random

final case class PlannedTimeSummaryModel(
  piTime:        FiniteDuration,
  unchargedTime: FiniteDuration
) {

  def executionTime: FiniteDuration =
    piTime + unchargedTime

}

object PlannedTimeSummaryModel {

  val Zero: PlannedTimeSummaryModel =
    PlannedTimeSummaryModel(0.microseconds, 0.microseconds)

  def random[F[_]: Sync]: F[PlannedTimeSummaryModel] =
    for {
      p <- Sync[F].delay(Random.between(5L, 120L))
      u <- Sync[F].delay(Random.between(1L,  15L))
    } yield PlannedTimeSummaryModel(p.minutes, u.minutes)


  implicit val MonoidPlannedTimeSummaryModel: Monoid[PlannedTimeSummaryModel] =
    Monoid.instance(
      Zero,
      (a, b) =>
        PlannedTimeSummaryModel(
          a.piTime + b.piTime,
          a.unchargedTime + b.unchargedTime
        )
    )

}
