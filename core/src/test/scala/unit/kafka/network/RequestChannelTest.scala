/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.network


import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.{Collections, Optional, Properties}
import com.fasterxml.jackson.databind.ObjectMapper
import com.yammer.metrics.core.Counter
import kafka.network
import kafka.network.RequestChannel.Metrics
import kafka.server.KafkaConfig
import kafka.utils.TestUtils
import org.apache.kafka.clients.admin.AlterConfigOp.OpType
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.types.Password
import org.apache.kafka.common.config.{ConfigResource, SaslConfigs, SslConfigs, TopicConfig}
import org.apache.kafka.common.memory.MemoryPool
import org.apache.kafka.common.message.{IncrementalAlterConfigsRequestData, ProduceRequestData}
import org.apache.kafka.common.message.IncrementalAlterConfigsRequestData._
import org.apache.kafka.common.network.{ByteBufferSend, ClientInformation, ListenerName}
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{AbstractRequest, MetadataRequest, RequestTestUtils}
import org.apache.kafka.common.requests.AlterConfigsRequest._
import org.apache.kafka.common.requests._
import org.apache.kafka.common.security.auth.{KafkaPrincipal, KafkaPrincipalSerde, SecurityProtocol}
import org.apache.kafka.common.utils.{SecurityUtils, Utils}
import org.easymock.EasyMock._
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api._
import org.mockito.{ArgumentCaptor, Mockito}

import java.util
import scala.collection.{Map, Seq}
import scala.jdk.CollectionConverters._

class RequestChannelTest {
  private val requestChannelMetrics: RequestChannel.Metrics = mock(classOf[RequestChannel.Metrics])
  private val clientId = "id"
  private val principalSerde = new KafkaPrincipalSerde() {
    override def serialize(principal: KafkaPrincipal): Array[Byte] = Utils.utf8(principal.toString)
    override def deserialize(bytes: Array[Byte]): KafkaPrincipal = SecurityUtils.parseKafkaPrincipal(Utils.utf8(bytes))
  }
  private val mockSend: ByteBufferSend = Mockito.mock(classOf[ByteBufferSend])

  @Test
  def testAlterRequests(): Unit = {

    val sensitiveValue = "secret"
    def verifyConfig(resource: ConfigResource, entries: Seq[ConfigEntry], expectedValues: Map[String, String]): Unit = {
      val alterConfigs = request(new AlterConfigsRequest.Builder(
          Collections.singletonMap(resource, new Config(entries.asJavaCollection)), true).build())

      val loggableAlterConfigs = alterConfigs.loggableRequest.asInstanceOf[AlterConfigsRequest]
      val loggedConfig = loggableAlterConfigs.configs.get(resource)
      assertEquals(expectedValues, toMap(loggedConfig))
      val alterConfigsDesc = RequestConvertToJson.requestDesc(alterConfigs.header, alterConfigs.requestLog, alterConfigs.isForwarded).toString
      assertFalse(alterConfigsDesc.contains(sensitiveValue), s"Sensitive config logged $alterConfigsDesc")
    }

    val brokerResource = new ConfigResource(ConfigResource.Type.BROKER, "1")
    val keystorePassword = new ConfigEntry(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, sensitiveValue)
    verifyConfig(brokerResource, Seq(keystorePassword), Map(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> Password.HIDDEN))

    val keystoreLocation = new ConfigEntry(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, "/path/to/keystore")
    verifyConfig(brokerResource, Seq(keystoreLocation), Map(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG -> "/path/to/keystore"))
    verifyConfig(brokerResource, Seq(keystoreLocation, keystorePassword),
      Map(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG -> "/path/to/keystore", SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> Password.HIDDEN))

    val listenerKeyPassword = new ConfigEntry(s"listener.name.internal.${SslConfigs.SSL_KEY_PASSWORD_CONFIG}", sensitiveValue)
    verifyConfig(brokerResource, Seq(listenerKeyPassword), Map(listenerKeyPassword.name -> Password.HIDDEN))

    val listenerKeystore = new ConfigEntry(s"listener.name.internal.${SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG}", "/path/to/keystore")
    verifyConfig(brokerResource, Seq(listenerKeystore), Map(listenerKeystore.name -> "/path/to/keystore"))

    val plainJaasConfig = new ConfigEntry(s"listener.name.internal.plain.${SaslConfigs.SASL_JAAS_CONFIG}", sensitiveValue)
    verifyConfig(brokerResource, Seq(plainJaasConfig), Map(plainJaasConfig.name -> Password.HIDDEN))

    val plainLoginCallback = new ConfigEntry(s"listener.name.internal.plain.${SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS}", "test.LoginClass")
    verifyConfig(brokerResource, Seq(plainLoginCallback), Map(plainLoginCallback.name -> plainLoginCallback.value))

    val customConfig = new ConfigEntry("custom.config", sensitiveValue)
    verifyConfig(brokerResource, Seq(customConfig), Map(customConfig.name -> Password.HIDDEN))

    val topicResource = new ConfigResource(ConfigResource.Type.TOPIC, "testTopic")
    val compressionType = new ConfigEntry(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
    verifyConfig(topicResource, Seq(compressionType), Map(TopicConfig.COMPRESSION_TYPE_CONFIG -> "lz4"))
    verifyConfig(topicResource, Seq(customConfig), Map(customConfig.name -> Password.HIDDEN))

    // Verify empty request
    val alterConfigs = request(new AlterConfigsRequest.Builder(
        Collections.emptyMap[ConfigResource, Config], true).build())
    assertEquals(Collections.emptyMap, alterConfigs.loggableRequest.asInstanceOf[AlterConfigsRequest].configs)
  }

  @Test
  def testIncrementalAlterRequests(): Unit = {

    def incrementalAlterConfigs(resource: ConfigResource,
                                entries: Map[String, String], op: OpType): IncrementalAlterConfigsRequest = {
      val data = new IncrementalAlterConfigsRequestData()
      val alterableConfigs = new AlterableConfigCollection()
      entries.foreach { case (name, value) =>
        alterableConfigs.add(new AlterableConfig().setName(name).setValue(value).setConfigOperation(op.id))
      }
      data.resources.add(new AlterConfigsResource()
        .setResourceName(resource.name).setResourceType(resource.`type`.id)
        .setConfigs(alterableConfigs))
      new IncrementalAlterConfigsRequest.Builder(data).build()
    }

    val sensitiveValue = "secret"
    def verifyConfig(resource: ConfigResource,
                     op: OpType,
                     entries: Map[String, String],
                     expectedValues: Map[String, String]): Unit = {
      val alterConfigs = request(incrementalAlterConfigs(resource, entries, op))
      val loggableAlterConfigs = alterConfigs.loggableRequest.asInstanceOf[IncrementalAlterConfigsRequest]
      val loggedConfig = loggableAlterConfigs.data.resources.find(resource.`type`.id, resource.name).configs
      assertEquals(expectedValues, toMap(loggedConfig))
      val alterConfigsDesc = RequestConvertToJson.requestDesc(alterConfigs.header, alterConfigs.requestLog, alterConfigs.isForwarded).toString
      assertFalse(alterConfigsDesc.contains(sensitiveValue), s"Sensitive config logged $alterConfigsDesc")
    }

    val brokerResource = new ConfigResource(ConfigResource.Type.BROKER, "1")
    val keystorePassword = Map(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> sensitiveValue)
    verifyConfig(brokerResource, OpType.SET, keystorePassword, Map(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> Password.HIDDEN))

    val keystoreLocation = Map(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG -> "/path/to/keystore")
    verifyConfig(brokerResource, OpType.SET, keystoreLocation, keystoreLocation)
    verifyConfig(brokerResource, OpType.SET, keystoreLocation ++ keystorePassword,
      Map(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG -> "/path/to/keystore", SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> Password.HIDDEN))

    val listenerKeyPassword = Map(s"listener.name.internal.${SslConfigs.SSL_KEY_PASSWORD_CONFIG}" -> sensitiveValue)
    verifyConfig(brokerResource, OpType.SET, listenerKeyPassword,
      Map(s"listener.name.internal.${SslConfigs.SSL_KEY_PASSWORD_CONFIG}" -> Password.HIDDEN))

    val listenerKeystore = Map(s"listener.name.internal.${SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG}" -> "/path/to/keystore")
    verifyConfig(brokerResource, OpType.SET, listenerKeystore, listenerKeystore)

    val plainJaasConfig = Map(s"listener.name.internal.plain.${SaslConfigs.SASL_JAAS_CONFIG}" -> sensitiveValue)
    verifyConfig(brokerResource, OpType.SET, plainJaasConfig,
      Map(s"listener.name.internal.plain.${SaslConfigs.SASL_JAAS_CONFIG}" -> Password.HIDDEN))

    val plainLoginCallback = Map(s"listener.name.internal.plain.${SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS}" -> "test.LoginClass")
    verifyConfig(brokerResource, OpType.SET, plainLoginCallback, plainLoginCallback)

    val sslProtocols = Map(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG -> "TLSv1.1")
    verifyConfig(brokerResource, OpType.APPEND, sslProtocols, Map(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG -> "TLSv1.1"))
    verifyConfig(brokerResource, OpType.SUBTRACT, sslProtocols, Map(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG -> "TLSv1.1"))
    val cipherSuites = Map(SslConfigs.SSL_CIPHER_SUITES_CONFIG -> null)
    verifyConfig(brokerResource, OpType.DELETE, cipherSuites, cipherSuites)

    val customConfig = Map("custom.config" -> sensitiveValue)
    verifyConfig(brokerResource, OpType.SET, customConfig, Map("custom.config" -> Password.HIDDEN))

    val topicResource = new ConfigResource(ConfigResource.Type.TOPIC, "testTopic")
    val compressionType = Map(TopicConfig.COMPRESSION_TYPE_CONFIG -> "lz4")
    verifyConfig(topicResource, OpType.SET, compressionType, compressionType)
    verifyConfig(topicResource, OpType.SET, customConfig, Map("custom.config" -> Password.HIDDEN))
  }

  @Test
  def testNonAlterRequestsNotTransformed(): Unit = {
    val metadataRequest = request(new MetadataRequest.Builder(List("topic").asJava, true).build())
    assertSame(metadataRequest.body[MetadataRequest], metadataRequest.loggableRequest)
  }

  @Test
  def testJsonRequests(): Unit = {
    val sensitiveValue = "secret"
    val resource = new ConfigResource(ConfigResource.Type.BROKER, "1")
    val keystorePassword = new ConfigEntry(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, sensitiveValue)
    val entries = Seq(keystorePassword)

    val alterConfigs = request(new AlterConfigsRequest.Builder(Collections.singletonMap(resource,
      new Config(entries.asJavaCollection)), true).build())

    assertTrue(isValidJson(RequestConvertToJson.request(alterConfigs.loggableRequest).toString))
  }

  @Test
  def testEnvelopeBuildResponseSendShouldReturnNoErrorIfInnerResponseHasNoError(): Unit = {
    val channelRequest = buildForwardRequestWithEnvelopeRequestAttached(buildMetadataRequest())

    val envelopeResponseArgumentCaptor = ArgumentCaptor.forClass(classOf[EnvelopeResponse])

    Mockito.doAnswer(_ => mockSend)
      .when(channelRequest.envelope.get.context).buildResponseSend(envelopeResponseArgumentCaptor.capture())

    // create an inner response without error
    val responseWithoutError = RequestTestUtils.metadataUpdateWith(2, Collections.singletonMap("a", 2))

    // build an envelope response
    channelRequest.buildResponseSend(responseWithoutError)

    // expect the envelopeResponse result without error
    val capturedValue: EnvelopeResponse = envelopeResponseArgumentCaptor.getValue
    assertTrue(capturedValue.error().equals(Errors.NONE))
  }

  @Test
  def testEnvelopeBuildResponseSendShouldReturnNoErrorIfInnerResponseHasNoNotControllerError(): Unit = {
    val channelRequest = buildForwardRequestWithEnvelopeRequestAttached(buildMetadataRequest())

    val envelopeResponseArgumentCaptor = ArgumentCaptor.forClass(classOf[EnvelopeResponse])

    Mockito.doAnswer(_ => mockSend)
      .when(channelRequest.envelope.get.context).buildResponseSend(envelopeResponseArgumentCaptor.capture())

    // create an inner response with REQUEST_TIMED_OUT error
    val responseWithTimeoutError = RequestTestUtils.metadataUpdateWith("cluster1", 2,
      Collections.singletonMap("a", Errors.REQUEST_TIMED_OUT),
      Collections.singletonMap("a", 2))

    // build an envelope response
    channelRequest.buildResponseSend(responseWithTimeoutError)

    // expect the envelopeResponse result without error
    val capturedValue: EnvelopeResponse = envelopeResponseArgumentCaptor.getValue
    assertTrue(capturedValue.error().equals(Errors.NONE))
  }

  @Test
  def testEnvelopeBuildResponseSendShouldReturnNotControllerErrorIfInnerResponseHasOne(): Unit = {
    val channelRequest = buildForwardRequestWithEnvelopeRequestAttached(buildMetadataRequest())

    val envelopeResponseArgumentCaptor = ArgumentCaptor.forClass(classOf[EnvelopeResponse])

    Mockito.doAnswer(_ => mockSend)
      .when(channelRequest.envelope.get.context).buildResponseSend(envelopeResponseArgumentCaptor.capture())

    // create an inner response with NOT_CONTROLLER error
    val responseWithNotControllerError = RequestTestUtils.metadataUpdateWith("cluster1", 2,
      Collections.singletonMap("a", Errors.NOT_CONTROLLER),
      Collections.singletonMap("a", 2))

    // build an envelope response
    channelRequest.buildResponseSend(responseWithNotControllerError)

    // expect the envelopeResponse result has NOT_CONTROLLER error
    val capturedValue: EnvelopeResponse = envelopeResponseArgumentCaptor.getValue
    assertTrue(capturedValue.error().equals(Errors.NOT_CONTROLLER))
  }

  @Test
  def testMetricsRequestSizeBucket(): Unit = {
    val apis = Seq(ApiKeys.FETCH, ApiKeys.PRODUCE)
    var props = new Properties()
    props.put(KafkaConfig.ZkConnectProp, "127.0.0.1:2181")
    props.put(KafkaConfig.RequestMetricsSizeBucketsProp, "0, 10, 20, 200")
    var config = KafkaConfig.fromProps(props)
    var metrics = new Metrics(apis, config)
    val fetchMetricsNameMap = metrics.consumerFetchRequestSizeMetricNameMap
    assertEquals(4, fetchMetricsNameMap.size)
    assertTrue(fetchMetricsNameMap.containsKey(0))
    assertTrue(fetchMetricsNameMap.containsKey(10))
    assertTrue(fetchMetricsNameMap.containsKey(20))
    assertTrue(fetchMetricsNameMap.containsKey(200))
    val flattenFetchMetricNames = metrics.getConsumerFetchRequestSizeMetricNames
    assertEquals(4, flattenFetchMetricNames.size)
    assertEquals("FetchConsumer0To10Mb", fetchMetricsNameMap.get(0))
    assertTrue(flattenFetchMetricNames.contains("FetchConsumer0To10Mb"))
    assertEquals("FetchConsumer10To20Mb", fetchMetricsNameMap.get(10))
    assertTrue(flattenFetchMetricNames.contains("FetchConsumer10To20Mb"))
    assertEquals("FetchConsumer20To200Mb", fetchMetricsNameMap.get(20))
    assertTrue(flattenFetchMetricNames.contains("FetchConsumer20To200Mb"))
    assertEquals("FetchConsumer200MbGreater", fetchMetricsNameMap.get(200))
    assertTrue(flattenFetchMetricNames.contains("FetchConsumer200MbGreater"))

    val produceMetricsNameMaps = metrics.produceRequestAcksSizeMetricNameMap
    assertEquals(3, produceMetricsNameMaps.size)

    assertTrue(produceMetricsNameMaps.contains(0))
    assertTrue(produceMetricsNameMaps.contains(1))
    assertTrue(produceMetricsNameMaps.contains(-1))
    val flattenProduceMetricNames = metrics.getProduceRequestAcksSizeMetricNames
    assertEquals(12, flattenProduceMetricNames.size)

    for (i <- 0 until 3) {
      val ackKey = if (i == 2) -1 else i
      val ackKeyString = if(i == 2) "All" else i.toString
      val produceMetricsNameMap = produceMetricsNameMaps(ackKey)
      assertTrue(produceMetricsNameMap.containsKey(0))
      assertTrue(produceMetricsNameMap.containsKey(10))
      assertTrue(produceMetricsNameMap.containsKey(20))
      assertTrue(produceMetricsNameMap.containsKey(200))
      var metricName = "Produce0To10MbAcks" + ackKeyString
      assertEquals(metricName, produceMetricsNameMap.get(0))
      assertTrue(flattenProduceMetricNames.contains(metricName))
      metricName = "Produce10To20MbAcks" + ackKeyString
      assertEquals(metricName, produceMetricsNameMap.get(10))
      assertTrue(flattenProduceMetricNames.contains(metricName))
      metricName = "Produce20To200MbAcks" + ackKeyString
      assertEquals(metricName, produceMetricsNameMap.get(20))
      assertTrue(flattenProduceMetricNames.contains(metricName))
      metricName = "Produce200MbGreaterAcks" + ackKeyString
      assertEquals(metricName, produceMetricsNameMap.get(200))
      assertTrue(flattenProduceMetricNames.contains(metricName))
    }

    // test get the bucket name
    val metadataRequest = request(new MetadataRequest.Builder(List("topic").asJava, true).build(), metrics)
    assertEquals(None, metadataRequest.getConsumerFetchSizeBucketMetricName)
    assertEquals(None, metadataRequest.getProduceAckSizeBucketMetricName)

    var produceRequest = request(new ProduceRequest.Builder(0, 0,
      new ProduceRequestData().setAcks(1.toShort).setTimeoutMs(1000)).build(),
      metrics)
    assertEquals(None, produceRequest.getConsumerFetchSizeBucketMetricName)
    assertEquals(Some("Produce0To10MbAcks1"), produceRequest.getProduceAckSizeBucketMetricName)
    produceRequest = request(new ProduceRequest.Builder(0, 0,
      new ProduceRequestData().setAcks(-1).setTimeoutMs(1000)).build(),
      metrics)
    assertEquals(Some("Produce0To10MbAcksAll"), produceRequest.getProduceAckSizeBucketMetricName)

    val tp = new TopicPartition("foo", 0)
    val fetchData = Map(tp -> new FetchRequest.PartitionData(0, 0, 1000,
      Optional.empty())).asJava
    val consumeFetchRequest = request(new FetchRequest.Builder(9, 9, -1, 100, 0, fetchData)
      .build(),
      metrics)
    assertEquals(Some("FetchConsumer0To10Mb"), consumeFetchRequest.getConsumerFetchSizeBucketMetricName)
    assertEquals(None, consumeFetchRequest.getProduceAckSizeBucketMetricName)
    val followerFetchRequest = request(new FetchRequest.Builder(9, 9, 1, 100, 0, fetchData)
      .build(),
      metrics)
    assertEquals(None, followerFetchRequest.getConsumerFetchSizeBucketMetricName)

    assertEquals("FetchConsumer0To10Mb", metrics.getRequestSizeBucketMetricName(metrics.consumerFetchRequestSizeMetricNameMap, 2*1024 *1024))
    assertEquals("Produce10To20MbAcks0", metrics.getRequestSizeBucketMetricName(metrics.produceRequestAcksSizeMetricNameMap(0), 10*1024 *1024))
    assertEquals("Produce200MbGreaterAcks1", metrics.getRequestSizeBucketMetricName(metrics.produceRequestAcksSizeMetricNameMap(1), 201*1024 *1024))
    assertEquals("Produce0To10MbAcksAll", metrics.getRequestSizeBucketMetricName(metrics.produceRequestAcksSizeMetricNameMap(-1), 0))
    assertEquals("Produce20To200MbAcksAll", metrics.getRequestSizeBucketMetricName(metrics.produceRequestAcksSizeMetricNameMap(-1), 35*1024 *1024))

    // test default config
    props = new Properties()
    props.put(KafkaConfig.ZkConnectProp, "127.0.0.1:2181")
    config = KafkaConfig.fromProps(props)
    metrics = new Metrics(apis, config)
    testMetricsRequestSizeBucketDefault(metrics)
  }
  @Test
  def testHistogram(): Unit = {
    val props = new Properties()
    props.put(KafkaConfig.ZkConnectProp, "127.0.0.1:2181")
    props.put(KafkaConfig.RequestMetricsTotalTimeBucketsProp, "0, 10, 30, 300")
    props.put(KafkaConfig.TotalTimeHistogramEnabledMetricsProp, util.Arrays.asList("Produce0To1MbAcks1", "Produce0To1MbAcksAll", "FetchConsumer0To1Mb"))

    val config = KafkaConfig.fromProps(props)
    // totalTimeBucketHist is only enabled for RequestMetrics with name defined in TotalTimeHistogramEnabledMetricsProp.
    val requestMetricsNoTotalTimeBucketHist = new RequestMetrics("RequestMetrics", config)
    assertTrue(requestMetricsNoTotalTimeBucketHist.totalTimeBucketHist.isEmpty)
    val requestMetrics = new RequestMetrics("FetchConsumer0To1Mb", config)
    assertTrue(requestMetrics.totalTimeBucketHist.isDefined)
    val boundaries = Array(0, 10, 30, 300)
    val histogram = new requestMetrics.Histogram("TotalTime", "Ms", boundaries)
    val counterMap = histogram.boundaryCounterMap

    assertEquals(4, counterMap.size())
    assertTrue(counterMap.containsKey(0))
    assertTrue(counterMap.containsKey(10))
    assertTrue(counterMap.containsKey(30))
    assertTrue(counterMap.containsKey(300))

    assertEquals("TotalTime_Bin1_0To10Ms", counterMap.get(0)._1)
    assertEquals("TotalTime_Bin2_10To30Ms", counterMap.get(10)._1)
    assertEquals("TotalTime_Bin3_30To300Ms", counterMap.get(30)._1)
    assertEquals("TotalTime_Bin4_300MsGreater", counterMap.get(300)._1)

    testHistogramCounts(counterMap, Array(0, 0, 0, 0), boundaries)

    histogram.update(0)
    testHistogramCounts(counterMap, Array(1, 0, 0, 0), boundaries)

    histogram.update(10.0)
    testHistogramCounts(counterMap, Array(1, 1, 0, 0), boundaries)

    histogram.update(15.345)
    testHistogramCounts(counterMap, Array(1, 2, 0, 0), boundaries)

    histogram.update(26.345)
    testHistogramCounts(counterMap, Array(1, 3, 0, 0), boundaries)

    histogram.update(6.345)
    testHistogramCounts(counterMap, Array(2, 3, 0, 0), boundaries)

    histogram.update(66.345)
    testHistogramCounts(counterMap, Array(2, 3, 1, 0), boundaries)

    histogram.update(166.345)
    testHistogramCounts(counterMap, Array(2, 3, 2, 0), boundaries)

    histogram.update(366.345)
    testHistogramCounts(counterMap, Array(2, 3, 2, 1), boundaries)

    histogram.update(30066.345)
    testHistogramCounts(counterMap, Array(2, 3, 2, 2), boundaries)

    histogram.update(-1)
    testHistogramCounts(counterMap, Array(3, 3, 2, 2), boundaries)

    assertEquals(4, requestMetrics.totalTimeBucketHist.get.boundaryCounterMap.size())
    assertTrue(requestMetrics.totalTimeBucketHist.get.boundaryCounterMap.containsKey(300))
    assertEquals("TotalTime_Bin4_300MsGreater", requestMetrics.totalTimeBucketHist.get.boundaryCounterMap.get(300)._1)
  }

  private def testHistogramCounts(counterMap: util.TreeMap[Int, (String, Counter)], counts: Array[Int],
    boundaries: Array[Int]): Unit = {
    for(i <- 0 until counts.length) {
      assertEquals(counts(i), counterMap.get(boundaries(i))._2.count())
    }
  }

  private def testMetricsRequestSizeBucketDefault(metrics: Metrics): Unit = {
    //default bucket "0,1,10,50,100"
    val fetchMetricsNameMap = metrics.consumerFetchRequestSizeMetricNameMap
    assertEquals(5, fetchMetricsNameMap.size)
    assertTrue(fetchMetricsNameMap.containsKey(0))
    assertTrue(fetchMetricsNameMap.containsKey(1))
    assertTrue(fetchMetricsNameMap.containsKey(10))
    assertTrue(fetchMetricsNameMap.containsKey(50))
    assertTrue(fetchMetricsNameMap.containsKey(100))
    assertEquals("FetchConsumer0To1Mb", fetchMetricsNameMap.get(0))
    assertEquals("FetchConsumer1To10Mb", fetchMetricsNameMap.get(1))
    assertEquals("FetchConsumer10To50Mb", fetchMetricsNameMap.get(10))
    assertEquals("FetchConsumer50To100Mb", fetchMetricsNameMap.get(50))
    assertEquals("FetchConsumer100MbGreater", fetchMetricsNameMap.get(100))
    val produceMetricsNameMaps = metrics.produceRequestAcksSizeMetricNameMap
    assertEquals(3, produceMetricsNameMaps.size)
    assertTrue(produceMetricsNameMaps.contains(0))
    assertTrue(produceMetricsNameMaps.contains(1))
    assertTrue(produceMetricsNameMaps.contains(-1))
    for (i <- 0 until 3) {
      val ackKey = if (i == 2) -1 else i
      val ackKeyString = if(i == 2) "All" else i.toString
      val produceMetricsNameMap = produceMetricsNameMaps(ackKey)
      assertTrue(produceMetricsNameMap.containsKey(0))
      assertTrue(produceMetricsNameMap.containsKey(1))
      assertTrue(produceMetricsNameMap.containsKey(10))
      assertTrue(produceMetricsNameMap.containsKey(50))
      assertTrue(produceMetricsNameMap.containsKey(100))
      assertEquals("Produce0To1MbAcks" + ackKeyString, produceMetricsNameMap.get(0))
      assertEquals("Produce1To10MbAcks" + ackKeyString, produceMetricsNameMap.get(1))
      assertEquals("Produce10To50MbAcks" + ackKeyString, produceMetricsNameMap.get(10))
      assertEquals("Produce50To100MbAcks" + ackKeyString, produceMetricsNameMap.get(50))
      assertEquals("Produce100MbGreaterAcks" + ackKeyString, produceMetricsNameMap.get(100))
    }
  }

  private def buildMetadataRequest(): AbstractRequest = {
    val resourceName = "topic-1"
    val header = new RequestHeader(ApiKeys.METADATA, ApiKeys.METADATA.latestVersion,
      clientId, 0)

    new MetadataRequest.Builder(Collections.singletonList(resourceName), true).build(header.apiVersion)
  }

  private def buildForwardRequestWithEnvelopeRequestAttached(request: AbstractRequest): RequestChannel.Request = {
    val envelopeRequest = TestUtils.buildRequestWithEnvelope(
      request, principalSerde, requestChannelMetrics, System.nanoTime(), shouldSpyRequestContext = true)

    TestUtils.buildRequestWithEnvelope(
      request, principalSerde, requestChannelMetrics, System.nanoTime(), envelope = Option(envelopeRequest))
  }

  private def isValidJson(str: String): Boolean = {
    try {
      val mapper = new ObjectMapper
      mapper.readTree(str)
      true
    } catch {
      case _: IOException => false
    }
  }

  def request(req: AbstractRequest, metrics: Metrics): RequestChannel.Request = {
    val buffer = req.serializeWithHeader(new RequestHeader(req.apiKey, req.version, "client-id", 1))
    val requestContext = newRequestContext(buffer)
    new network.RequestChannel.Request(processor = 1,
      requestContext,
      startTimeNanos = 0,
      createNiceMock(classOf[MemoryPool]),
      buffer,
      metrics
    )
  }

  def request(req: AbstractRequest): RequestChannel.Request = {
    request(req, createNiceMock(classOf[RequestChannel.Metrics]))
  }

  private def newRequestContext(buffer: ByteBuffer): RequestContext = {
    new RequestContext(
      RequestHeader.parse(buffer),
      "connection-id",
      InetAddress.getLoopbackAddress,
      new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "user"),
      ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT),
      SecurityProtocol.PLAINTEXT,
      new ClientInformation("name", "version"),
      false)
  }

  private def toMap(config: Config): Map[String, String] = {
    config.entries.asScala.map(e => e.name -> e.value).toMap
  }

  private def toMap(config: IncrementalAlterConfigsRequestData.AlterableConfigCollection): Map[String, String] = {
    config.asScala.map(e => e.name -> e.value).toMap
  }
}
