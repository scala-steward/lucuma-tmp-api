// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.repo

import lucuma.odb.api.model.{Editor, Event, Existence, InputError, Sharing, TopLevelModel, ValidatedInput}
import lucuma.odb.api.model.syntax.toplevel._
import lucuma.odb.api.model.syntax.validatedinput._
import lucuma.core.util.Gid

import cats._ //{Eq, FunctorFilter, Monad, MonadError}
import cats.data._
import cats.effect.concurrent.Ref
import cats.kernel.BoundedEnumerable
import cats.syntax.all._

import monocle.Lens
import monocle.function.At
import monocle.state.all._

import Function.unlift
import scala.Function.const
import scala.collection.immutable.SortedMap


trait TopLevelRepo[F[_], I, T] {

  def nextId: F[I]

  def select(id: I, includeDeleted: Boolean = false): F[Option[T]]

  def unsafeSelect(id: I, includeDeleted: Boolean = false): F[T]

  def selectAll(includeDeleted: Boolean = false): F[List[T]]

  def selectAllWhere(f: Tables => T => Boolean, includeDeleted: Boolean = false): F[List[T]]

  /**
   * Edits the top-level item identified by the given `Editor`.
   * @param editor editor instance
   * @return updated item
   */
  def edit(editor: Editor[I, T]): F[T] =
    edit(editor.id, editor.editor, _ => Nil)

  /**
   * Edits the top-level item identified by the given id and editor
   *
   * @param id id of the top level item
   * @param editor state program that edits the associated top-level item, or else
   *          any input validation errors
   * @param checks additional checks that require accessing database tables
   * @return updated item, but raises an error and does nothing if any checks
   *         fail
   */
  def edit(
    id:     I,
    editor: ValidatedInput[State[T, Unit]],
    checks: Tables => List[InputError]
  ): F[T]

  /**
   * Edits a top-level item identified by the given id, assuming it is of type
   * `U <: T`.
   *
   * @param id id of the top level item (of type T)
   * @param editor state program that edits the associated top-level item, or else
   *               any input validation errors
   * @param checks additional checks that require accessing database tables
   * @param f a partial function defined when the item is of type U
   * @return updated item, but raises an error and does nothing if any checks
   *         fail
   */
  def editSub[U <: T: Eq](
    id:     I,
    editor: ValidatedInput[State[U, Unit]],
    checks: Tables => List[InputError]
  )(
    f: PartialFunction[T, U]
  ): F[U]

  def delete(id: I): F[T]

  def undelete(id: I): F[T]

}

abstract class TopLevelRepoBase[F[_]: Monad, I: Gid, T: TopLevelModel[I, *]: Eq](
  tablesRef:    Ref[F, Tables],
  eventService: EventService[F],
  idLens:       Lens[Tables, I],
  mapLens:      Lens[Tables, SortedMap[I, T]],
  edited:       (Event.EditType, T) => Long => Event.Edit[T]
)(implicit M: MonadError[F, Throwable]) extends TopLevelRepo[F, I, T] {

  def nextId: F[I] =
    tablesRef.modifyState(idLens.mod(BoundedEnumerable[I].cycleNext))

  def deletionFilter[FF[_]: FunctorFilter](includeDeleted: Boolean)(ff: FF[T]): FF[T] =
    ff.filter(t => includeDeleted || t.isPresent)

  def focusOn(id: I): Lens[Tables, Option[T]] =
    mapLens ^|-> At.at(id)

  def select(id: I, includeDeleted: Boolean = false): F[Option[T]] =
    selectUnconditional(id).map(deletionFilter(includeDeleted))

  def unsafeSelect(id: I, includeDeleted: Boolean = false): F[T] =
    select(id, includeDeleted).flatMap {
      case None    => ExecutionException.missingReference[F, I, T](id)
      case Some(t) => t.pure[F]
    }

  def selectUnconditional(id: I): F[Option[T]] =
    tablesRef.get.map(focusOn(id).get)

  def selectAll(includeDeleted: Boolean = false): F[List[T]] =
    selectAllWhere(const(const(true)), includeDeleted)

  def selectAllWhere(f: Tables => T => Boolean, includeDeleted: Boolean = false): F[List[T]] =
    selectAllWhereUnconditional(f).map(deletionFilter(includeDeleted))

  def selectAllWhereUnconditional(f: Tables => T => Boolean): F[List[T]] =
    tablesRef.get.map { tables =>
      mapLens.get(tables).values.toList.filter(f(tables))
    }

  def constructAndPublish[U <: T](
    cons: Tables => ValidatedInput[State[Tables, U]]
  ): F[U] = {
    val fu = EitherT(
      tablesRef.modify { tables =>
        cons(tables).fold(
          err => (tables, InputError.Exception(err).asLeft[U]),
          _.run(tables).value.map(_.asRight)
        )
      }
    ).rethrowT

    for {
      u <- fu
      _ <- eventService.publish(edited(Event.EditType.Created, u))
    } yield u
  }

  def createAndInsert[U <: T](id: Option[I], f: I => U): State[Tables, U] =
    for {
      i <- id.fold(idLens.mod(BoundedEnumerable[I].cycleNext))(State.pure[Tables, I])
      t  = f(i)
      _ <- mapLens.mod(_ + (i -> t))
    } yield t

  override def edit(
    id:     I,
    editor: ValidatedInput[State[T, Unit]],
    checks: Tables => List[InputError]
  ): F[T] =
    editSub[T](id, editor, checks)(unlift(_.some))

  override def editSub[U <: T: Eq](
    id:     I,
    editor: ValidatedInput[State[U, Unit]],
    checks: Tables => List[InputError]
  )(
    f: PartialFunction[T, U]
  ): F[U] = {

    val lensT = focusOn(id)

    val lens: Lens[Tables, Option[U]] =
      Lens[Tables, Option[U]](lensT.get(_).flatMap(f.unapply))(ou => lensT.set(ou))

    val doUpdate: F[Either[U, U]] =
      tablesRef.modify { oldTables =>

        val item   = lens.get(oldTables).toValidNec(InputError.missingReference("id", Gid[I].show(id)))
        val errors = NonEmptyChain.fromSeq(checks(oldTables))
        val result = (item, editor, errors.toInvalid(())).mapN { (oldU, state, _) =>
          val newU = state.runS(oldU).value
          Either.cond(oldU =!= newU, newU, oldU) // Right => updated, Left => no update
        }

        val tables = result.fold(_ => oldTables, {
          _.fold(_ => oldTables, newU => lens.set(Some(newU))(oldTables))
        })

        (tables, result)
      }.flatMap(_.liftTo[F])

    for {
      e <- doUpdate
      _ <- e.fold(_ => M.unit, u => eventService.publish(edited(Event.EditType.Updated, u)))
    } yield e.merge

  }

  private def setExistence(id: I, newState: Existence): F[T] =
    edit(id, TopLevelModel[I, T].existenceEditor(newState).validNec, _ => Nil)

  def delete(id: I): F[T] =
    setExistence(id, Existence.Deleted)

  def undelete(id: I): F[T] =
    setExistence(id, Existence.Present)

  private def share[J, M](
    name:    String,
    input:   Sharing[I, J],
    findM:   J => State[Tables, ValidatedInput[M]],
    editedM: M => Long => Event.Edit[M]
  )(
    update:  ValidatedInput[(T, List[M])] => State[Tables, Unit],
  ): F[T] = {

    val link = tablesRef.modifyState {
      for {
        vo <- focusOn(input.one).st.map(_.toValidNec(InputError.missingReference(name, Gid[I].show(input.one))))
        vm <- input.many.traverse(findM).map(_.sequence)
        vtm = (vo, vm).mapN { (o, m) => (o, m) }
        _  <- update(vtm)
      } yield vtm
    }

    for {
      tm <- link.flatMap(_.liftTo[F])
      (t, ms) = tm
      _ <- eventService.publish(edited(Event.EditType.Updated, t)) // publish one
      _ <- ms.traverse_(m => eventService.publish(editedM(m)))     // publish many
    } yield t
  }

  protected def shareLeft[J, M](
    name:     String,
    input:    Sharing[I, J],
    findM:    J => State[Tables, ValidatedInput[M]],
    linkLens: Lens[Tables, ManyToMany[J, I]],
    editedM:  M => Long => Event.Edit[M]
 )(
    update:   (ManyToMany[J, I], IterableOnce[(J, I)]) => ManyToMany[J, I]
 ): F[T] =
    share(name, input, findM, editedM) { vtm =>
      vtm.traverse_ { _ => linkLens.mod_(links => update(links, input.tupleRight)) }
    }

  protected def shareRight[J, M](
    name:     String,
    input:    Sharing[I, J],
    findM:    J => State[Tables, ValidatedInput[M]],
    linkLens: Lens[Tables, ManyToMany[I, J]],
    editedM:  M => Long => Event.Edit[M]
  )(
    update:   (ManyToMany[I, J], IterableOnce[(I, J)]) => ManyToMany[I, J]
  ): F[T] =
    share(name, input, findM, editedM) { vtm =>
      vtm.traverse_ { _ => linkLens.mod_(links => update(links, input.tupleLeft)) }
    }

}
