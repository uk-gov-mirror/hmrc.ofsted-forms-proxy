/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.ofstedformsproxy.notification

import java.time.LocalDateTime

import cats.{Eq, Monad, MonadError}
import uk.gov.service.notify.SendEmailResponse

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import cats.implicits._

trait Notifier[F[_]] extends OfstedNotificationConf {

  def notifyByEmail(templateId: String, emailAddress: EmailAddress, personalisation: Map[String, String])(
    implicit me: MonadError[F, String]): F[SendEmailResponse] =
    runNotification(Try(notificationClient.sendEmail(templateId, emailAddress.email, personalisation.asJava, "")))

  private def runNotification[T](fn: Try[T])(implicit me: MonadError[F, String]): F[T] = fn match {
    case Success(response) => me.pure(response)
    case Failure(ex)       => me.raiseError(s"Unable to notify reviewer ${ex.getMessage}")
  }
}

class OfstedNotificationClient[F[_]: Monad](notifier: Notifier[F]) extends FormLinkBuilder {

  def send(notifyRequest: NotifyRequest)(implicit me: MonadError[F, String]): F[OfstedNotificationClientResponse] =
    notifier
      .notifyByEmail(
        formTemplates(notifyRequest.formStatus),
        EmailAddress(notifier.ofstedNotification.email),
        personalise(notifyRequest.formId, notifyRequest.formStatus))
      .map(emailResponse => OfstedNotificationClientResponse(emailResponse))

  private val basicTemplate: (FormId, String) => Map[String, String] =
    (formId, key) => Map("form-id" -> formId.value, key -> LocalDateTime.now.toString)

  private def personalise(formId: FormId, status: FormStatus): Map[String, String] = status match {
    case Approved   => basicTemplate(formId, "acceptance-time")
    case InProgress => basicTemplate(formId, "rejection-time") + ("url" -> buildLink(formId).link)
    case Submitted  => basicTemplate(formId, "submission-time")
    case _          => Map.empty
  }
}

case class OfstedNotificationClientResponse(emailResponse: SendEmailResponse)

trait FormLinkBuilder extends OfstedNotificationConf {
  def buildLink(formId: FormId): FormLink = {
    val link = s"${ofstedNotification.formLinkPrefix}${formId.value}"
    println(link)
    FormLink(link)
  }
}

//TODO the following is very likely to be removed
case class FormId(value: String)

sealed trait FormStatus
case object InProgress extends FormStatus
case object Summary extends FormStatus
case object Validated extends FormStatus
case object Signed extends FormStatus
case object NeedsReview extends FormStatus
case object Approved extends FormStatus
case object Submitted extends FormStatus

object FormStatus {
  implicit val equal: Eq[FormStatus] = Eq.fromUniversalEquals

  val all: Set[FormStatus] = Set(InProgress, Summary, Validated, Signed, NeedsReview, Approved, Submitted)
}