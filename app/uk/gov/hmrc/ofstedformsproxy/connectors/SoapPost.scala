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

package uk.gov.hmrc.ofstedformsproxy.connectors

import play.api.http.{HttpVerbs, Writeable}
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.http.logging.ConnectionTracing
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, HttpVerb}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.play.http.ws.{WSProxy, WSProxyConfiguration}

import scala.concurrent.Future
import scala.xml.Elem

trait SoapPost extends WSProxy with ConnectionTracing with HttpVerbs with HttpVerb {
  def post[A](url: String, body: A, headers: Seq[(String, String)])(implicit wrt: Writeable[A], hc: HeaderCarrier): Future[SoapHttpResponse]
}

object SoapPost {

  def apply(env: String): SoapPost = new SoapPost {
    override def post[A](url: String, body: A, headers: Seq[(String, String)])(implicit wrt: Writeable[A], hc: HeaderCarrier) =
      withTracing(POST, url) {
      val req = buildRequest(url).withHeaders(headers: _*)
        println("-----------------\n\n\n\n")
        println(url)
        println("-----------------\n\n\n\n")
        println(headers.toString)
        println(req.toString)
        println("-----------------\n\n\n\n")
        req.post(body).map(new SoapResponse(_))
    }

    override protected def configuration = None //TODO: double check this

    override def wsProxyServer = WSProxyConfiguration("microservice.services.cygnum.proxy")
  }

}

trait SoapHttpResponse extends HttpResponse {
  def xml: Elem
}

class SoapResponse(wsResponse: WSResponse) extends SoapHttpResponse {
  override def xml: Elem = wsResponse.xml

  override def body = wsResponse.body

  override def status = wsResponse.status
}