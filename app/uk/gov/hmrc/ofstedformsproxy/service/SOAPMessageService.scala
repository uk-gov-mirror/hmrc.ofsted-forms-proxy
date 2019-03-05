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

package uk.gov.hmrc.ofstedformsproxy.service

import java.io._
import java.nio.charset.StandardCharsets
import java.security._
import java.security.cert.{Certificate, CertificateEncodingException}
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util
import java.util.{Base64, UUID}

import com.google.inject.{ImplementedBy, Inject}
import javax.inject.Singleton
import javax.xml.crypto.dom.DOMStructure
import javax.xml.crypto.dsig._
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory
import javax.xml.crypto.dsig.spec.{C14NMethodParameterSpec, TransformParameterSpec}
import javax.xml.parsers.ParserConfigurationException
import javax.xml.soap._
import org.w3c.dom.{DOMException, Document}
import org.xml.sax.{InputSource, SAXException}
import play.api.Environment
import scalaz._
import uk.gov.hmrc.ofstedformsproxy.config.AppConfig
import uk.gov.hmrc.ofstedformsproxy.models.ServiceType._

import scala.util.{Failure, Success, Try}
import scala.xml.{NodeSeq, XML}

@ImplementedBy(classOf[SOAPMessageServiceImpl])
trait SOAPMessageService {
  def buildGetURNPayload(): String \/ String

  def buildFormSubmissionPayload(node: NodeSeq): String \/ String
}

@Singleton
class SOAPMessageServiceImpl @Inject()(env: Environment)(appConfig: AppConfig) extends SOAPMessageService {

  val uuid: String = UUID.randomUUID.toString
  val ID1: String = uuid + "-1"
  val ID2: String = uuid + "-2"

  System.setProperty("javax.xml.soap.MessageFactory", "com.sun.xml.internal.messaging.saaj.soap.ver1_2.SOAPMessageFactory1_2Impl")
  System.setProperty("javax.xml.bind.JAXBContext", "com.sun.xml.internal.bind.v2.ContextFactory")

  override def buildFormSubmissionPayload(node: NodeSeq): String \/ String = {
    val result: String \/ String = for {
      xmlDocument <- readInXMLPayload(node)
      soapMessage <- createSOAPEnvelope(xmlDocument)
      signedSoapMessage <- signSOAPMessage(soapMessage, SendData)
    } yield stringifySoapMessage(signedSoapMessage)

    result match {
      case \/-(xmlPayload) => \/-(xmlPayload)
      case -\/(error) => -\/(error)
    }
  }

  override def buildGetURNPayload(): String \/ String = {
    val result: String \/ String = for {
      xmlDocument <- readInXMLFilePayload(appConfig.getUrnXMLFileLocation)
      soapMessage <- createSOAPEnvelope(xmlDocument)
      signedSoapMessage <- signSOAPMessage(soapMessage, GetData)
    } yield stringifySoapMessage(signedSoapMessage)

    result match {
      case \/-(xmlPayload) => \/-(xmlPayload)
      case -\/(error) => -\/(error)
    }

  }

  private def stringifySoapMessage(soapMessage: SOAPMessage): String = {
    val file = File.createTempFile(getClass.getSimpleName, ".tmp")
    val fos = new FileOutputStream(file)

    soapMessage.writeTo(fos)
    val xx = XML.loadFile(file)
    val writer = new StringWriter
    XML.write(writer, xx, StandardCharsets.UTF_8.toString, xmlDecl = true, null)
    writer.toString
  }

  private def readInXMLPayload(path: NodeSeq): String \/ org.w3c.dom.Document = {
    Try {
      val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance
      dbFactory.setNamespaceAware(true)
      dbFactory.newDocumentBuilder.parse(new InputSource(new StringReader(path.toString())))
    }
    match {
      case Success(document) => \/-(document)
      case Failure(e: ParserConfigurationException) => -\/(e.getMessage)
      case Failure(e: SAXException) => -\/(e.getMessage)
      case Failure(e: IOException) => -\/(e.getMessage)
    }
  }

  private def readInXMLFilePayload(path: String): String \/ org.w3c.dom.Document = {
    env.getExistingFile(path) match {
      case Some(xml) => Try {
        val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance
        dbFactory.setNamespaceAware(true)
        dbFactory.newDocumentBuilder.parse(xml)
      }
      match {
        case Success(document) => \/-(document)
        case Failure(e: ParserConfigurationException) => -\/(e.getMessage)
        case Failure(e: SAXException) => -\/(e.getMessage)
        case Failure(e: IOException) => -\/(e.getMessage)
      }
      case None => {
        -\/("XML payload file does not exist.")
      }
    }
  }

  private def createSOAPEnvelope(xmlDocument: Document): String \/ SOAPMessage =
    Try {
      // Create SOAP Message
      val messageFactory = MessageFactory.newInstance
      val soapMessage = messageFactory.createMessage
      val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
      soapEnvelope.setPrefix("s")
      soapEnvelope.removeNamespaceDeclaration("SOAP-ENV")

      soapEnvelope.addNamespaceDeclaration("a", "http://www.w3.org/2005/08/addressing")
      soapEnvelope.addNamespaceDeclaration("u", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd")

      // Add DOM object to SOAP body
      val soapBody = soapMessage.getSOAPBody
      soapBody.setPrefix("s")
      soapBody.addDocument(xmlDocument)
      soapBody.addAttribute(soapEnvelope.createName("Id", "u", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"), "_1")
      soapMessage

    } match {
      case Success(soapMessage) => \/-(soapMessage)
      case Failure(e: SOAPException) => -\/(e.getMessage)
      case Failure(e: DOMException) => -\/(e.getMessage)
    }

  private def signSOAPMessage(soapMessage: SOAPMessage, action: ServiceType): String \/ SOAPMessage = {

    Try {
      val soapHeader: SOAPHeader = soapMessage.getSOAPHeader
      soapHeader.setPrefix("s")

      addAction(soapHeader, soapMessage, action)

      addMessageId(soapHeader)

      addReplyTo(soapHeader)

      addTo(soapHeader, soapMessage)

      val securityElement = addSecurity(soapHeader, soapMessage)

      val cert = getCertificate

      val timestamp = addTimestamp(securityElement, soapMessage)

      addUsernameToken(securityElement, soapMessage)

      addBinarySecurityToken(securityElement, cert)

      addSignature(securityElement, soapMessage.getSOAPBody, timestamp)

      soapMessage
    } match {
      case Success(signedSoapMessage) => \/-(signedSoapMessage)
      case Failure(e: SOAPException) => -\/(e.getMessage)
      case Failure(e: DOMException) => -\/(e.getMessage)
      case Failure(e: NoSuchAlgorithmException) => -\/(e.getMessage)
      case Failure(e: KeyStoreException) => -\/(e.getMessage)
      case Failure(e: CertificateEncodingException) => -\/(e.getMessage)
      case Failure(e: InvalidAlgorithmParameterException) => -\/(e.getMessage)
      case Failure(e: XMLSignatureException) => -\/(e.getMessage)
    }

  }

  def createTempFileForData(data: String): (String, Array[Byte]) = {
    val file = File.createTempFile(getClass.getSimpleName, ".tmp")
    file.deleteOnExit()
    val os = new FileOutputStream(file)
    try {
      val bytes = Base64.getDecoder.decode(data.trim)
      os.write(bytes)
      os.flush()
      file.getAbsolutePath → bytes
    } finally {
      os.close()
    }
  }

  private def getKeyFormCert: PrivateKey = {
    val password = appConfig.cygnumClientPassword.toCharArray
    val keystore = KeyStore.getInstance("jks")
    val (file, _) = createTempFileForData(appConfig.cygnumKeyStore)
    keystore.load(new FileInputStream(file), password)
    keystore.getKey(appConfig.cygnumPrivateKeyAlias, password).asInstanceOf[PrivateKey]
  }

  private def addSecurityToken(signature: SOAPElement): SOAPElement = {
    val securityTokenReference = signature.addChildElement("SecurityTokenReference", "o")
    val reference = securityTokenReference.addChildElement("Reference", "o")
    reference.setAttribute("URI", String.format("#uuid-%s", ID2))
    reference.setAttribute("ValueType", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3")
    securityTokenReference
  }

  private def addSignature(securityElement: SOAPElement, soapBody: SOAPBody, timestamp: SOAPElement): SOAPElement = {
    val key = getKeyFormCert
    val securityTokenReference = addSecurityToken(securityElement)
    // Add signature
    createDetachedSignature(securityElement, key, securityTokenReference, soapBody, timestamp)
    securityElement
  }

  private def createDetachedSignature(signatureElement: SOAPElement, privateKey: PrivateKey, securityTokenReference: SOAPElement, soapBody: SOAPBody, timestamp: SOAPElement): Unit = {
    val providerName = System.getProperty("jsr105Provider", "org.jcp.xml.dsig.internal.dom.XMLDSigRI")
    val xmlSignatureFactory = XMLSignatureFactory.getInstance("DOM", Class.forName(providerName).newInstance.asInstanceOf[Provider])
    //Digest method
    val digestMethod = xmlSignatureFactory.newDigestMethod(DigestMethod.SHA1, null)
    val transformList = new util.ArrayList[Transform]
    //Transform
    val envTransform = xmlSignatureFactory.newTransform("http://www.w3.org/2001/10/xml-exc-c14n#", null.asInstanceOf[TransformParameterSpec])
    transformList.add(envTransform)
    //References
    val refList = new util.ArrayList[Reference]
    val refTS = xmlSignatureFactory.newReference("#_0", digestMethod, transformList, null, null)
    val refBody = xmlSignatureFactory.newReference("#_1", digestMethod, transformList, null, null)
    refList.add(refTS)
    refList.add(refBody)
    val cm = xmlSignatureFactory.newCanonicalizationMethod("http://www.w3.org/2001/10/xml-exc-c14n#", null.asInstanceOf[C14NMethodParameterSpec])
    val sm = xmlSignatureFactory.newSignatureMethod("http://www.w3.org/2000/09/xmldsig#rsa-sha1", null)
    val signedInfo = xmlSignatureFactory.newSignedInfo(cm, sm, refList)
    val signContext = new DOMSignContext(privateKey, signatureElement)
    signContext.setIdAttributeNS(soapBody, "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd", "Id")
    signContext.setIdAttributeNS(timestamp, "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd", "Id")
    val keyFactory = KeyInfoFactory.getInstance
    val domKeyInfo = new DOMStructure(securityTokenReference)
    val keyInfo = keyFactory.newKeyInfo(java.util.Collections.singletonList(domKeyInfo))
    val signature = xmlSignatureFactory.newXMLSignature(signedInfo, keyInfo)
    signContext.setBaseURI("")
    signature.sign(signContext)
  }

  private def addBinarySecurityToken(securityElement: SOAPElement, cert: Certificate): SOAPElement = {
    val certByte = cert.getEncoded
    // Add the Binary Security Token element
    val binarySecurityToken = securityElement.addChildElement("BinarySecurityToken", "o")
    binarySecurityToken.setAttribute("u:Id", String.format("uuid-%s", ID2))
    binarySecurityToken.setAttribute("ValueType", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3")
    binarySecurityToken.setAttribute("EncodingType", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary")
    binarySecurityToken.addTextNode(Base64.getEncoder.encodeToString(certByte))
    securityElement
  }

  private def addTimestamp(securityElement: SOAPElement, soapMessage: SOAPMessage): SOAPElement = {
    val timestamp = securityElement.addChildElement("Timestamp", "u")
    val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
    timestamp.addAttribute(soapEnvelope.createName("Id", "u", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"), "_0")
    val DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSX"
    val timeStampFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)
    timestamp.addChildElement("Created", "u").setValue(timeStampFormatter.format(ZonedDateTime.now.toInstant.atZone(ZoneId.of("UTC"))))
    timestamp.addChildElement("Expires", "u").setValue(timeStampFormatter.format(ZonedDateTime.now.plusSeconds(300).toInstant.atZone(ZoneId.of("UTC"))))
    timestamp
  }

  private def addAction(soapHeader: SOAPElement, soapMessage: SOAPMessage, serviceType: ServiceType): SOAPElement = {
    val action = soapHeader.addChildElement("Action", "a")
    val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
    action.addAttribute(soapEnvelope.createName("mustUnderstand", "s", "http://www.w3.org/2003/05/soap-envelope"), "1")
    if (serviceType == GetData)
      action.addTextNode("http://tempuri.org/IGatewayOOServices/GetData")
    else
      action.addTextNode("http://tempuri.org/IGatewayOOServices/SendData")

    action
  }

  private def addMessageId(soapHeader: SOAPElement): SOAPElement = {
    val messageId = soapHeader.addChildElement("MessageID", "a")
    messageId.addTextNode(String.format("urn:uuid:%s", UUID.randomUUID.toString))
    messageId
  }

  private def addReplyTo(soapHeader: SOAPElement): SOAPElement = {
    val replyTo = soapHeader.addChildElement("ReplyTo", "a")
    val address = replyTo.addChildElement("Address", "a")
    address.addTextNode("http://www.w3.org/2005/08/addressing/anonymous")
    replyTo
  }

  private def addTo(soapHeader: SOAPElement, soapMessage: SOAPMessage): SOAPElement = {
    val to = soapHeader.addChildElement("To", "a")
    val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
    to.addAttribute(soapEnvelope.createName("mustUnderstand", "s", "http://www.w3.org/2003/05/soap-envelope"), "1")
    to.addAttribute(soapEnvelope.createName("Id", "u", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"), "_1")
    to.addTextNode(appConfig.cygnumURL)
    to
  }

  private def addUsernameToken(securityElement: SOAPElement, soapMessage: SOAPMessage): SOAPElement = {
    val usernameToken = securityElement.addChildElement("UsernameToken", "o")
    val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
    usernameToken.addAttribute(soapEnvelope.createName("Id", "u", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"), String.format("uuid-%s", ID1))
    usernameToken.addChildElement("Username", "o").setValue(appConfig.cygnumUsername)
    val e = usernameToken.addChildElement("Password", "o")
    e.addAttribute(soapEnvelope.createName("Type", "o", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"), "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText")
    e.addTextNode(appConfig.cygnumPassword)
    usernameToken
  }

  private def addSecurity(soapHeader: SOAPElement, soapMessage: SOAPMessage): SOAPElement = {
    val security = soapHeader.addChildElement("Security", "o", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd")
    val soapEnvelope = soapMessage.getSOAPPart.getEnvelope
    security.addAttribute(soapEnvelope.createName("mustUnderstand", "s", "http://www.w3.org/2003/05/soap-envelope"), "1")
    security
  }

  private def getCertificate: Certificate = {
    val password = appConfig.cygnumClientPassword.toCharArray
    val keystore = KeyStore.getInstance("jks")
    val (file, _) = createTempFileForData(appConfig.cygnumKeyStore)
    keystore.load(new FileInputStream(file), password)
    keystore.getCertificate(appConfig.cygnumPrivateKeyAlias)
  }

}