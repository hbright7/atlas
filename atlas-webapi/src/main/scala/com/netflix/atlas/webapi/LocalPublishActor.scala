/*
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.atlas.webapi

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.actor.ActorLogging
import com.netflix.atlas.core.db.MemoryDatabase
import com.netflix.atlas.core.model.Datapoint
import com.netflix.atlas.core.model.DefaultSettings
import com.netflix.atlas.core.model.TagKey
import com.netflix.atlas.core.norm.NormalizationCache
import com.netflix.spectator.api.Spectator
import com.netflix.spectator.sandbox.BucketCounter
import com.netflix.spectator.sandbox.BucketFunctions
import spray.http.HttpResponse
import spray.http.StatusCodes


class LocalPublishActor(db: MemoryDatabase) extends Actor with ActorLogging {

  import com.netflix.atlas.webapi.PublishApi._

  // Track the ages of data flowing into the system. Data is expected to arrive quickly and
  // should hit the backend within the step interval used.
  private val numReceived = {
    val registry = Spectator.registry
    val f = BucketFunctions.age(DefaultSettings.stepSize, TimeUnit.MILLISECONDS)
    BucketCounter.get(registry, registry.createId("atlas.db.numMetricsReceived"), f)
  }

  private val cache = new NormalizationCache(DefaultSettings.stepSize, db.update)

  def receive = {
    case PublishRequest(vs) =>
      update(vs)
      sender() ! HttpResponse(StatusCodes.OK)
  }

  private def update(vs: List[Datapoint]): Unit = {
    val now = System.currentTimeMillis()
    vs.foreach { v =>
      numReceived.record(now - v.timestamp)
      v.tags.get(TagKey.dsType) match {
        case Some("counter") => cache.updateCounter(v)
        case Some("gauge")   => cache.updateGauge(v)
        case Some("rate")    => cache.updateRate(v)
        case _               => cache.updateRate(v)
      }
    }
  }
}

