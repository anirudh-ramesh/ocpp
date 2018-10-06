package com.thenewmotion.ocpp
package json
package api

import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.higherKinds
import messages2._
import org.json4s.{DefaultFormats, Extraction, Formats, JValue}
import org.slf4j.LoggerFactory

/** One roles of the OCPP 2.0 communicatino protocol: Charging Station (CS) or
  * Charging Station Management System (CSMS)
  */
// TODO use at a higher level
sealed trait Side
case object Cs extends Side
case object Csms extends Side

trait Ocpp2ConnectionComponent[
  OUTREQBOUND <: Request,
  INRESBOUND <: Response,
  OUTREQRES[_ <: OUTREQBOUND,_ <: INRESBOUND] <: ReqResV2[_, _],
  INREQBOUND <: Request,
  OUTRESBOUND <: Response,
  INREQRES[_ <: INREQBOUND, _ <: OUTRESBOUND] <: ReqResV2[_, _]
] extends OcppConnectionComponent[OUTREQBOUND, INRESBOUND, OUTREQRES, INREQBOUND, OUTRESBOUND, INREQRES] {

  this: SrpcComponent =>

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val executionContext: ExecutionContext

  implicit val formats: Formats = DefaultFormats

  trait Ocpp2Connection extends OcppConnection {

    def incomingProcedures: Ocpp2Procedures[INREQBOUND, OUTRESBOUND, INREQRES]
    val outgoingProcedures: Ocpp2Procedures[OUTREQBOUND, INRESBOUND, OUTREQRES]

    def onSrpcCall(call: SrpcCall): Future[SrpcResponse] = {
      val ocppProc = incomingProcedures.procedureByName(call.procedureName)
      ocppProc match {
        case None =>
          // TODO distinguish NotSupported and NotImplemented
          Future.successful(SrpcCallError(PayloadErrorCode.NotImplemented,
                                          "This OCPP 2.0 procedure is not yet implemented"))
        case Some(procedure) =>
          // TODO scalafmt opzetten
          val jsonResponse: Future[JValue] = procedure.reqRes(call.payload) { (req, rr) =>
            Ocpp2ConnectionComponent.this.onRequest(req)(rr)
          }
          jsonResponse map { res =>
            SrpcCallResult(Extraction.decompose(res))
          }  recover[SrpcResponse] { case NonFatal(e) =>
            // TODO this bit copied from DefaultOcppConnection, should be shared
            logger.warn("Exception processing incoming OCPP request {}: {} {}",
                        call.procedureName, e.getClass.getSimpleName, e.getMessage)

            // TODO how do we guarantee that extraction / decomposition errors are reported appropriately?
            val ocppError = e match {
              case OcppException(err) => err
              case _ => OcppError(PayloadErrorCode.InternalError, "Unexpected error processing request")
            }

            SrpcCallError(
              ocppError.error,
              ocppError.description
            )
          }
      }
    }

    def sendRequest[REQ <: OUTREQBOUND, RES <: INRESBOUND](req: REQ)(implicit reqRes: OUTREQRES[REQ, RES]): Future[RES] = {
      val procedure = outgoingProcedures.procedureByReqRes(reqRes)
      procedure match {
        case None =>
          throw OcppException(PayloadErrorCode.NotSupported, "This OCPP procedure is not supported")
        case Some(proc) =>
          val srpcCall = SrpcCall(proc.name, Extraction.decompose(req))
          srpcConnection.sendCall(srpcCall) map {
            case SrpcCallResult(payload) =>
              // TODO why is this asInstanceOf needed? we know that procedure's RES is
              // the same as this method's RES, don't we?
              proc.deserializeRes(payload).asInstanceOf[RES]
            case SrpcCallError(code, description, details) =>
              throw OcppException(code, description)
          }
      }
    }
  }

  def ocppVersion: Version = Version.V20

  def ocppConnection: Ocpp2Connection

  override def onSrpcCall(msg: SrpcCall):  Future[SrpcResponse] = ocppConnection.onSrpcCall(msg)
}

trait CsOcpp2ConnectionComponent extends Ocpp2ConnectionComponent[
    CsmsRequest,
    CsmsResponse,
    CsmsReqRes,
    CsRequest,
    CsResponse,
    CsReqRes
  ] {
  self: SrpcComponent =>

  val ocppConnection = new Ocpp2Connection {

    val incomingProcedures: Ocpp2Procedures[CsRequest, CsResponse, CsReqRes] = CsOcpp2Procedures

    val outgoingProcedures: Ocpp2Procedures[CsmsRequest, CsmsResponse, CsmsReqRes] = CsmsOcpp2Procedures

    override def sendRequestUntyped(req: CsmsRequest): Future[CsmsResponse] = {
      req match {
        case r: BootNotificationRequest => sendRequest(r)
      }
    }
  }
}