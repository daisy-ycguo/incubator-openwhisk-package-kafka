/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package system.health

import java.time.Clock
import java.time.Instant

import system.utils.KafkaUtils

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.NOT_FOUND
import common.TestUtils.SUCCESS_EXIT
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny

import com.jayway.restassured.RestAssured

import whisk.utils.retry;

@RunWith(classOf[JUnitRunner])
class BasicHealthTest
  extends FlatSpec
  with Matchers
  with WskActorSystem
  with BeforeAndAfterAll
  with TestHelpers
  with WskTestHelpers
  with JsHelpers {

  val topic = "test"
  val sessionTimeout = 10 seconds

  implicit val wskprops = WskProps()
  val wsk = new Wsk()

  val messagingPackage = "/whisk.system/messaging"
  val messageHubFeed = "messageHubFeed"
  val messageHubProduce = "messageHubProduce"

  val consumerInitTime = 10000 // ms

  val kafkaUtils = new KafkaUtils

  behavior of "Message Hub feed"

  it should "create a new trigger" in withAssetCleaner(wskprops) {
    val triggerName = s"newTrigger-${System.currentTimeMillis}"
    println(s"Creating trigger ${triggerName}")

    (wp, assetHelper) =>
      val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
        (trigger, _) =>
          trigger.create(triggerName, feed = Some(s"$messagingPackage/$messageHubFeed"), parameters = Map(
            "user" -> kafkaUtils.getAsJson("user"),
            "password" -> kafkaUtils.getAsJson("password"),
            "api_key" -> kafkaUtils.getAsJson("api_key"),
            "kafka_admin_url" -> kafkaUtils.getAsJson("kafka_admin_url"),
            "kafka_brokers_sasl" -> kafkaUtils.getAsJson("brokers"),
            "topic" -> topic.toJson
          ))
      }

      withActivation(wsk.activation, feedCreationResult, initialWait = 5 seconds, totalWait = 60 seconds) {
        activation =>
          // should be successful
          activation.response.success shouldBe true
          val uuid = activation.response.result.get.fields.get("uuid").get.toString

          // get /health endpoint and ensure it contains the new uuid
          retry({
            val response = RestAssured.given().get(System.getProperty("health_url"))
            assert(response.statusCode() == 200 && response.asString().contains(uuid))
          }, N = 3, waitBeforeRetry = Some(1.second))
      }
  }

  it should "fire a trigger when a message is posted to message hub" in withAssetCleaner(wskprops) {
    val currentTime = s"${System.currentTimeMillis}"

    val baseTriggerName = "/_/BasicHealthTestTrigger"

    val triggerName = System.getProperty("trigger.suffix") match {
      case suffix if suffix != "" && suffix != null => s"${baseTriggerName}-${suffix}"
      case _ => s"${baseTriggerName}-${currentTime}"
    }

    (wp, assetHelper) =>
      val result = wsk.trigger.get(triggerName, expectedExitCode = DONTCARE_EXIT)

      if (result.exitCode == NOT_FOUND) {
        // trigger does not yet exist, create it
        println(s"Creating trigger ${triggerName}")

        val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
          (trigger, _) =>
            trigger.create(triggerName, feed = Some(s"$messagingPackage/$messageHubFeed"), parameters = Map(
              "user" -> kafkaUtils.getAsJson("user"),
              "password" -> kafkaUtils.getAsJson("password"),
              "api_key" -> kafkaUtils.getAsJson("api_key"),
              "kafka_admin_url" -> kafkaUtils.getAsJson("kafka_admin_url"),
              "kafka_brokers_sasl" -> kafkaUtils.getAsJson("brokers"),
              "topic" -> topic.toJson
            ))
        }

        withActivation(wsk.activation, feedCreationResult, initialWait = 5 seconds, totalWait = 60 seconds) {
          activation =>
            // should be successful
            activation.response.success shouldBe true
        }

        // It takes a moment for the consumer to fully initialize.
        println("Giving the consumer a moment to get ready")
        Thread.sleep(consumerInitTime)
      } else {
        result.exitCode shouldBe (SUCCESS_EXIT)
        println(s"Trigger already exists, reusing it: $triggerName")
      }

      retry({
        val start = Instant.now(Clock.systemUTC())
        // key to use for the produced message
        val key = "TheKey"

        println("Producing a message")
        withActivation(wsk.activation, wsk.action.invoke(s"$messagingPackage/$messageHubProduce", Map(
          "user" -> kafkaUtils.getAsJson("user"),
          "password" -> kafkaUtils.getAsJson("password"),
          "kafka_brokers_sasl" -> kafkaUtils.getAsJson("brokers"),
          "topic" -> topic.toJson,
          "key" -> key.toJson,
          "value" -> currentTime.toJson
        ))) {
          _.response.success shouldBe true
        }

        println("Polling for activations")
        val activations = wsk.activation.pollFor(N = 100, Some(triggerName), since = Some(start), retries = 30)
        assert(activations.length > 0)

        println("Validating content of activation(s)")
        val matchingActivations = for {
          id <- activations
          activation = wsk.activation.waitForActivation(id)
          if (activation.isRight && activation.right.get.fields.get("response").toString.contains(currentTime))
        } yield activation.right.get

        assert(matchingActivations.length == 1)

        val activation = matchingActivations.head
        activation.getFieldPath("response", "success") shouldBe Some(true.toJson)

        // assert that there exists a message in the activation which has the expected keys and values
        val messages = KafkaUtils.messagesInActivation(activation, field = "value", value = currentTime)
        assert(messages.length == 1)

        val message = messages.head
        message.getFieldPath("topic") shouldBe Some(topic.toJson)
        message.getFieldPath("key") shouldBe Some(key.toJson)
      }, N = 3)
  }
}
