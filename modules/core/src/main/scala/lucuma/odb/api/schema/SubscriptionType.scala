// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.schema

import lucuma.odb.api.model.Event
import lucuma.odb.api.model.{AsterismModel, ObservationModel, ProgramModel, TargetModel}
import lucuma.odb.api.repo.OdbRepo
import cats.effect.{ConcurrentEffect, Effect}
import _root_.fs2.Stream
import lucuma.odb.api.model.AsterismModel.{AsterismCreatedEvent, AsterismEditedEvent}
import lucuma.odb.api.model.ObservationModel.{ObservationCreatedEvent, ObservationEditedEvent}
import lucuma.odb.api.model.ProgramModel.{ProgramCreatedEvent, ProgramEditedEvent}
import lucuma.odb.api.model.TargetModel.{TargetCreatedEvent, TargetEditedEvent}
import sangria.schema._
import sangria.streaming.SubscriptionStream
import sangria.streaming.SubscriptionStreamLike._
import sangria.streaming.fs2._

import scala.reflect.ClassTag

object SubscriptionType {

  implicit def asterismType[F[_]: Effect]: InterfaceType[OdbRepo[F], AsterismModel] =
    AsterismSchema.AsterismType[F]

  implicit def observationType[F[_]: Effect]: ObjectType[OdbRepo[F], ObservationModel] =
    ObservationSchema.ObservationType[F]

  implicit def programType[F[_]: Effect]: ObjectType[OdbRepo[F], ProgramModel] =
    ProgramSchema.ProgramType[F]

  implicit def targetType[F[_]: Effect]: ObjectType[OdbRepo[F], TargetModel] =
    TargetSchema.TargetType[F]

  def EventType[F[_]: Effect]: InterfaceType[OdbRepo[F], Event]  =
    InterfaceType[OdbRepo[F], Event](
      "Event",
      "Common fields shared by all events",
      fields[OdbRepo[F], Event](
        Field("id",      LongType, resolve = _.value.id)
      )
    )

  def CreatedEventType[F[_]: Effect, T: OutputType, E <: Event.Created[T]: ClassTag](
    name: String
  ): ObjectType[OdbRepo[F], E] =
    ObjectType[OdbRepo[F], E](
      name        = name,
      description = "Event sent when a new object is created",
      interfaces  = List(PossibleInterface.apply[OdbRepo[F], E](EventType)),
      fields      = fields[OdbRepo[F], E](
        Field(
          name        = "value",
          fieldType   = implicitly[OutputType[T]],
          description = Some("Newly created object"),
          resolve     = _.value.value
        )
      )
    )

  def EditedEventType[F[_]: Effect, T: OutputType, E <: Event.Edited[T]: ClassTag](
    name: String
  ): ObjectType[OdbRepo[F], E] =
    ObjectType[OdbRepo[F], E](
      name        = name,
      description = "Event sent when an object is edited",
      interfaces  = List(PossibleInterface.apply[OdbRepo[F], E](EventType)),
      fields      = fields[OdbRepo[F], E](
        Field(
          name        = "oldValue",
          fieldType   = implicitly[OutputType[T]],
          description = Some("Previous value of the edited object"),
          resolve     = _.value.oldValue
        ),

        Field(
          name        = "newValue",
          fieldType   = implicitly[OutputType[T]],
          description = Some("Updated value of the edited object"),
          resolve     = _.value.newValue
        )
      )
    )


  def subscriptionField[F[_]: ConcurrentEffect, T <: Event](
    fieldName: String,
    tpe:       ObjectType[OdbRepo[F], T]
  ): Field[OdbRepo[F], Unit] = {

    implicit val subStream: SubscriptionStream[Stream[F, *]] =
      fs2SubscriptionStream[F](ConcurrentEffect[F], scala.concurrent.ExecutionContext.global)

    Field.subs(fieldName, tpe,
      resolve = (c: Context[OdbRepo[F], Unit]) => {
        c.ctx
          .eventService
          .subscribe
          .filter(event => tpe.valClass.isAssignableFrom(event.getClass))
          .map(event => Action[OdbRepo[F], T](event.asInstanceOf[T]))
      }
    )
  }

  def createdField[F[_]: ConcurrentEffect, T: OutputType, E <: Event.Created[T]: ClassTag](name: String): Field[OdbRepo[F], Unit] =
    subscriptionField[F, E](
      s"${name}Created",
      CreatedEventType[F, T, E](s"${name.capitalize}Created")
    )

  def editedField[F[_]: ConcurrentEffect, T: OutputType, E <: Event.Edited[T]: ClassTag](name: String): Field[OdbRepo[F], Unit] =
    subscriptionField[F, E](
      s"${name}Edited",
      EditedEventType[F, T, E](s"${name.capitalize}Edited")
    )

  def apply[F[_]: ConcurrentEffect]: ObjectType[OdbRepo[F], Unit] =
    ObjectType(
      name   = "Subscription",
      fields = fields(
        createdField[F, AsterismModel, AsterismCreatedEvent]("asterism"),
        editedField[F, AsterismModel, AsterismEditedEvent]("asterism"),
        createdField[F, ObservationModel, ObservationCreatedEvent]("observation"),
        editedField[F, ObservationModel, ObservationEditedEvent]("observation"),
        createdField[F, ProgramModel, ProgramCreatedEvent]("program"),
        editedField[F, ProgramModel, ProgramEditedEvent]("program"),
        createdField[F, TargetModel, TargetCreatedEvent]("target"),
        editedField[F, TargetModel, TargetEditedEvent]("target")
      )
    )
}