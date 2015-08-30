/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.twitter.zipkin.query

import java.nio.ByteBuffer

import com.twitter.finagle.stats.{DefaultStatsReceiver, Stat, StatsReceiver}
import com.twitter.finagle.tracing.{Trace => FTrace}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import com.twitter.zipkin.common.Span
import com.twitter.zipkin.conversions.thrift._
import com.twitter.zipkin.query.adjusters._
import com.twitter.zipkin.query.constants._
import com.twitter.zipkin.storage._
import com.twitter.zipkin.thriftscala

class ThriftQueryService(
  spanStore: SpanStore,
  aggsStore: Aggregates = new NullAggregates,
  adjusters: Map[thriftscala.Adjust, Adjuster] = Map.empty[thriftscala.Adjust, Adjuster],
  traceDurationFetchBatchSize: Int = 500,
  stats: StatsReceiver = DefaultStatsReceiver.scope("ThriftQueryService"),
  log: Logger = Logger.get("ThriftQueryService")
) extends thriftscala.ZipkinQuery[Future] {

  private[this] val methodStats = stats.scope("perMethod")

  private[this] def opt[T](param: T): Option[T] = param match {
    case null | "" => None
    case s => Some(s)
  }

  private[this] def adjustedTraces(traces: Seq[Seq[Span]], adjusts: Seq[thriftscala.Adjust]): Seq[Trace] = {
    val as = adjusts flatMap { adjusters.get(_) }
    traces map { spans =>
      as.foldLeft(Trace(spans)) { (t, adjuster) => adjuster.adjust(t) }
    }
  }

  private[this] def padTimestamp(timestamp: Long): Long =
    timestamp + TraceTimestampPadding.inMicroseconds

  private[this] def traceIdsIntersect(idSeqs: Seq[Seq[IndexedTraceId]]): Seq[IndexedTraceId] = {
    /* Find the trace IDs present in all the Seqs */
    val idMaps = idSeqs.map(_.groupBy(_.traceId))
    val traceIds = idMaps.map(_.keys.toSeq)
    val commonTraceIds = traceIds.tail.fold(traceIds(0))(_.intersect(_))

    /*
     * Find the timestamps associated with each trace ID and construct a new IndexedTraceId
     * that has the trace ID's maximum timestamp (ending) as the timestamp
     */
    commonTraceIds map { id =>
      IndexedTraceId(id, idMaps.flatMap(_(id).map(_.timestamp)).max)
    }
  }

  private[this] def queryResponse(
    ids: Seq[IndexedTraceId],
    qr: thriftscala.QueryRequest
  ): Future[thriftscala.QueryResponse] = {
    Future.value(thriftscala.QueryResponse(ids.slice(0, qr.limit).map(_.traceId)))
  }

  private trait SliceQuery
  private case class SpanSliceQuery(name: String) extends SliceQuery
  private case class AnnotationSliceQuery(key: String, value: Option[ByteBuffer]) extends SliceQuery

  private[this] def querySlices(slices: Seq[SliceQuery], qr: thriftscala.QueryRequest): Future[Seq[Seq[IndexedTraceId]]] =
    Future.collect(slices map {
      case SpanSliceQuery(name) =>
        spanStore.getTraceIdsByName(qr.serviceName, Some(name), qr.endTs, qr.limit)
      case AnnotationSliceQuery(key, value) =>
        spanStore.getTraceIdsByAnnotation(qr.serviceName, key, value, qr.endTs, qr.limit)
      case s =>
        Future.exception(new Exception("Uknown SliceQuery: %s".format(s)))
    })

  private[this] def handle[T](name: String)(f: => Future[T]): Future[T] = {
    val errorStats = methodStats.scope("errors")

    val ret = try {
      Stat.timeFuture(methodStats.stat(name))(f)
    } catch {
      case e: Exception => Future.exception(e)
    }

    ret rescue { case e: Exception =>
      log.error(e, "%s error".format(name))
      errorStats.counter(name).incr()
      errorStats.scope(name).counter(e.getClass.getName).incr()
      Future.exception(thriftscala.QueryException(e.toString))
    }
  }

  private[this] val noServiceNameError = Future.exception(thriftscala.QueryException("No service name provided"))
  private[this] def handleQuery[T](name: String, qr: thriftscala.QueryRequest)(f: => Future[T]): Future[T] = {
    if (!opt(qr.serviceName).isDefined) noServiceNameError else {
      FTrace.recordBinary("serviceName", qr.serviceName)
      FTrace.recordBinary("endTs", qr.endTs)
      FTrace.recordBinary("limit", qr.limit)
      handle(name)(f)
    }
  }

  override def getTraceIds(qr: thriftscala.QueryRequest): Future[thriftscala.QueryResponse] =
    handleQuery("getTraceIds", qr) {
      val sliceQueries = Seq[Option[Seq[SliceQuery]]](
        qr.spanName.map { n => Seq(SpanSliceQuery(n)) },
        qr.annotations.map { _.map { AnnotationSliceQuery(_, None) } },
        qr.binaryAnnotations.map { _.map { b => AnnotationSliceQuery(b.key, Some(b.value)) } }
      ).flatten.flatten

      sliceQueries match {
        case Nil =>
          spanStore.getTraceIdsByName(qr.serviceName, None, qr.endTs, qr.limit) flatMap {
            queryResponse(_, qr)
          }

        case slice :: Nil =>
          querySlices(sliceQueries, qr) flatMap { ids => queryResponse(ids.flatten, qr) }

        case _ =>
          // TODO: timestamps endTs is the wrong name for all this
          querySlices(sliceQueries, qr.copy(limit = 1)) flatMap { ids =>
            val ts = padTimestamp(ids.flatMap(_.map(_.timestamp)).reduceOption(_ min _).getOrElse(0))
            querySlices(sliceQueries, qr.copy(endTs = ts)) flatMap { ids =>
              queryResponse(traceIdsIntersect(ids), qr)
            }
          }
      }
    }

  override def getTracesByIds(traceIds: Seq[Long], adjust: Seq[thriftscala.Adjust]): Future[Seq[thriftscala.Trace]] =
    handle("getTracesByIds") {
      FTrace.recordBinary("numIds", traceIds.length)
      spanStore.getSpansByTraceIds(traceIds) map { adjustedTraces(_, adjust).map(_.toThrift) }
    }

  override def getTraceSummariesByIds(traceIds: Seq[Long], adjust: Seq[thriftscala.Adjust]): Future[Seq[thriftscala.TraceSummary]] =
    handle("getTraceSummariesByIds") {
      FTrace.recordBinary("numIds", traceIds.length)
      spanStore.getSpansByTraceIds(traceIds) map { traces =>
        adjustedTraces(traces, adjust) flatMap { TraceSummary(_).map(_.toThrift) }
      }
    }

  override def getTraceCombosByIds(traceIds: Seq[Long], adjust: Seq[thriftscala.Adjust]): Future[Seq[thriftscala.TraceCombo]] =
    handle("getTraceCombosByIds") {
      FTrace.recordBinary("numIds", traceIds.length)
      spanStore.getSpansByTraceIds(traceIds) map { traces =>
        adjustedTraces(traces, adjust) map { TraceCombo(_).toThrift }
      }
    }

  override def getServiceNames: Future[Set[String]] =
    handle("getServiceNames") {
      spanStore.getAllServiceNames
    }

  override def getSpanNames(serviceName: String): Future[Set[String]] =
    handle("getSpanNames") {
      spanStore.getSpanNames(serviceName)
    }

  override def getDependencies(startTime: Option[Long], endTime: Option[Long]) : Future[thriftscala.Dependencies] =
    handle("getDependencies") {
      val start = startTime map { Time.fromMicroseconds(_) }
      val end = endTime map { Time.fromMicroseconds(_) }
      aggsStore.getDependencies(start, end).map(_.toThrift)
    }
}
