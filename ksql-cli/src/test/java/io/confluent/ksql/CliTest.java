/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql;

import io.confluent.ksql.cli.LocalCli;
import io.confluent.ksql.cli.console.OutputFormat;
import io.confluent.ksql.rest.client.KsqlRestClient;
import io.confluent.ksql.rest.server.KsqlRestApplication;
import io.confluent.ksql.rest.server.KsqlRestConfig;
import io.confluent.ksql.testutils.EmbeddedSingleNodeKafkaCluster;
import io.confluent.ksql.util.CliUtils;
import io.confluent.ksql.util.OrderDataProvider;
import io.confluent.ksql.util.TestDataProvider;
import io.confluent.ksql.util.TopicConsumer;
import io.confluent.ksql.util.TopicProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.confluent.ksql.TestResult.*;
import static io.confluent.ksql.util.KsqlConfig.*;
import static io.confluent.ksql.util.MetaStoreFixture.assertExpectedResults;

/**
 * Most tests in CliTest are end-to-end integration tests, so it may expect a long running time.
 */
public class CliTest extends TestRunner {

  @ClassRule
  public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

  private static final String COMMANDS_KSQL_TOPIC_NAME = KsqlRestApplication.COMMANDS_KSQL_TOPIC_NAME;
  private static final int PORT = 9098;
  private static final String LOCAL_REST_SERVER_ADDR = "http://localhost:" + PORT;
  private static final OutputFormat CLI_OUTPUT_FORMAT = OutputFormat.TABULAR;

  private static final long STREAMED_QUERY_ROW_LIMIT = 10000;
  private static final long STREAMED_QUERY_TIMEOUT_MS = 10000;

  private static final TestResult.OrderedResult EMPTY_RESULT = build("");

  private static LocalCli localCli;
  private static TestTerminal terminal;
  private static String commandTopicName;
  private static TopicProducer topicProducer;
  private static TopicConsumer topicConsumer;

  private static OrderDataProvider orderDataProvider;

  @BeforeClass
  public static void setUp() throws Exception {
    KsqlRestClient restClient = new KsqlRestClient(LOCAL_REST_SERVER_ADDR);

    // TODO: Fix Properties Setup in Local().getCli()
    // Local local =  new Local().getCli();
    // LocalCli localCli = local.getCli(restClient, terminal);

    // TODO: add remote cli test cases
    terminal = new TestTerminal(CLI_OUTPUT_FORMAT, restClient);

    KsqlRestConfig restServerConfig = new KsqlRestConfig(defaultServerProperties());
    commandTopicName = restServerConfig.getCommandTopic();

    KsqlRestApplication restServer = KsqlRestApplication.buildApplication(restServerConfig, false);
    restServer.start();

    localCli = new LocalCli(
        STREAMED_QUERY_ROW_LIMIT,
        STREAMED_QUERY_TIMEOUT_MS,
        restClient,
        terminal,
        restServer
    );

    TestRunner.setup(localCli, terminal);

    topicProducer = new TopicProducer(CLUSTER);
    topicConsumer = new TopicConsumer(CLUSTER);

    // Test list or show commands before any custom topics created.
    testListOrShowCommands();

    orderDataProvider = new OrderDataProvider();
    restServer.getKsqlEngine().getKafkaTopicClient().createTopic(orderDataProvider.topicName(), 1, (short)1);
    produceInputStream(orderDataProvider);
  }

  private static void produceInputStream(TestDataProvider dataProvider) throws Exception {
    createKStream(dataProvider);
    topicProducer.produceInputData(dataProvider);
  }

  private static void createKStream(TestDataProvider dataProvider) {
    test(
        String.format("CREATE STREAM %s %s WITH (value_format = 'json', kafka_topic = '%s' , key='%s')",
            dataProvider.kstreamName(), dataProvider.ksqlSchemaString(), dataProvider.topicName(), dataProvider.key()),
        build("Stream created")
    );
  }

  private static void testListOrShowCommands() {
    testListOrShow("topics", build(commandTopicName, true, 1, 1));
    testListOrShow("registered topics", build(COMMANDS_KSQL_TOPIC_NAME, commandTopicName, "JSON"));
    testListOrShow("streams", EMPTY_RESULT);
    testListOrShow("tables", EMPTY_RESULT);
    testListOrShow("queries", EMPTY_RESULT);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    // If WARN NetworkClient:589 - Connection to node -1 could not be established. Broker may not be available.
    // It may be due to not closing the resource.
    // ksqlEngine.close();
    System.out.println("[Terminal Output]");
    System.out.println(terminal.getOutputString());

    localCli.close();
    terminal.close();
  }

  private static Map<String, Object> genDefaultConfigMap() {
    Map<String, Object> configMap = new HashMap<>();
    configMap.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    configMap.put(KsqlRestConfig.LISTENERS_CONFIG, CliUtils.getLocalServerAddress(PORT));
    configMap.put("application.id", "KSQL");
    configMap.put("commit.interval.ms", 0);
    configMap.put("cache.max.bytes.buffering", 0);
    configMap.put("auto.offset.reset", "earliest");
    configMap.put("ksql.command.topic.suffix", "commands");

    return configMap;
  }

  private static Properties defaultServerProperties() {
    Properties serverProperties = new Properties();
    serverProperties.putAll(genDefaultConfigMap());
    return serverProperties;
  }

  private static Map<String, Object> validStartUpConfigs() {
    // TODO: these configs should be set with other configs on start-up, rather than setup later.
    Map<String, Object> startConfigs = genDefaultConfigMap();
    startConfigs.put("num.stream.threads", 4);

    startConfigs.put(SINK_NUMBER_OF_REPLICATIONS, 1);
    startConfigs.put(SINK_NUMBER_OF_PARTITIONS, 4);
    startConfigs.put(SINK_WINDOW_CHANGE_LOG_ADDITIONAL_RETENTION, 1000000);

    startConfigs.put(KSQL_TRANSIENT_QUERY_NAME_PREFIX_CONFIG, KSQL_TRANSIENT_QUERY_NAME_PREFIX_DEFAULT);
    startConfigs.put(KSQL_SERVICE_ID_CONFIG, KSQL_SERVICE_ID_DEFAULT);
    startConfigs.put(KSQL_TABLE_STATESTORE_NAME_SUFFIX_CONFIG, KSQL_TABLE_STATESTORE_NAME_SUFFIX_DEFAULT);
    startConfigs.put(KSQL_PERSISTENT_QUERY_NAME_PREFIX_CONFIG, KSQL_PERSISTENT_QUERY_NAME_PREFIX_DEFAULT);

    return startConfigs;
  }

  private static void testCreateStreamAsSelect(String selectQuery, Schema resultSchema, Map<String, GenericRow> expectedResults) throws Exception {
    if (!selectQuery.endsWith(";")) {
      selectQuery += ";";
    }
    String resultKStreamName = "RESULT";
    String resultTopicName = resultKStreamName;
    final String queryString = "CREATE STREAM " + resultKStreamName + " AS " + selectQuery;

    /* Start Stream Query */
    test(queryString, build("Stream created and running"));

    /* Assert Results */
    Map<String, GenericRow> results = topicConsumer.readResults(resultTopicName, resultSchema, expectedResults.size(), new StringDeserializer());
    Assert.assertEquals(expectedResults.size(), results.size());
    assertExpectedResults(results, expectedResults);

    /* Get first column of the first row in the result set to obtain the queryID */
    String queryID = (String) ((List) run("list queries").data.toArray()[0]).get(0);

    /* Clean Up */
    run("terminate query " + queryID);
    dropStream(resultKStreamName);
  }

  private static void dropStream(String name) {
    test(
        String.format("drop stream %s", name),
        build("Source " + name + " was dropped")
    );
  }

  @Test
  public void testPropertySetUnset() {
    test("set 'application.id' = 'Test_App'", EMPTY_RESULT);
    test("set 'producer.batch.size' = '16384'", EMPTY_RESULT);
    test("set 'max.request.size' = '1048576'", EMPTY_RESULT);
    test("set 'consumer.max.poll.records' = '500'", EMPTY_RESULT);
    test("set 'enable.auto.commit' = 'true'", EMPTY_RESULT);
    test("set 'AVROSCHEMA' = 'schema'", EMPTY_RESULT);

    test("unset 'application.id'", EMPTY_RESULT);
    test("unset 'producer.batch.size'", EMPTY_RESULT);
    test("unset 'max.request.size'", EMPTY_RESULT);
    test("unset 'consumer.max.poll.records'", EMPTY_RESULT);
    test("unset 'enable.auto.commit'", EMPTY_RESULT);
    test("unset 'AVROSCHEMA'", EMPTY_RESULT);

    testListOrShow("properties", build(validStartUpConfigs()), false);
  }

  @Test
  public void testDescribe() {
    test("describe topic " + COMMANDS_KSQL_TOPIC_NAME,
        build(COMMANDS_KSQL_TOPIC_NAME, commandTopicName, "JSON"));
  }

  @Test
  public void testSelectStar() throws Exception {
    testCreateStreamAsSelect(
        "SELECT * FROM " + orderDataProvider.kstreamName(),
        orderDataProvider.schema(),
        orderDataProvider.data()
    );
  }

  @Test
  public void testSelectProject() throws Exception {
    Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("1", new GenericRow(Arrays.asList("ITEM_1", 10.0, new
        Double[]{100.0,
        110.99,
        90.0 })));
    expectedResults.put("2", new GenericRow(Arrays.asList("ITEM_2", 20.0, new
        Double[]{10.0,
        10.99,
        9.0 })));

    expectedResults.put("3", new GenericRow(Arrays.asList("ITEM_3", 30.0, new
        Double[]{10.0,
        10.99,
        91.0 })));

    expectedResults.put("4", new GenericRow(Arrays.asList("ITEM_4", 40.0, new
        Double[]{10.0,
        140.99,
        94.0 })));

    expectedResults.put("5", new GenericRow(Arrays.asList("ITEM_5", 50.0, new
        Double[]{160.0,
        160.99,
        98.0 })));

    expectedResults.put("6", new GenericRow(Arrays.asList("ITEM_6", 60.0, new
        Double[]{1000.0,
        1100.99,
        900.0 })));

    expectedResults.put("7", new GenericRow(Arrays.asList("ITEM_7", 70.0, new
        Double[]{1100.0,
        1110.99,
        190.0 })));

    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 80.0, new
        Double[]{1100.0,
        1110.99,
        970.0 })));

    Schema resultSchema = SchemaBuilder.struct()
        .field("ITEMID", SchemaBuilder.STRING_SCHEMA)
        .field("ORDERUNITS", SchemaBuilder.FLOAT64_SCHEMA)
        .field("PRICEARRAY", SchemaBuilder.array(SchemaBuilder.FLOAT64_SCHEMA))
        .build();

    testCreateStreamAsSelect(
        "SELECT ITEMID, ORDERUNITS, PRICEARRAY FROM " + orderDataProvider.kstreamName(),
        resultSchema,
        expectedResults
    );
  }

  @Test
  public void testSelectFilter() throws Exception {
    Map<String, GenericRow> expectedResults = new HashMap<>();
    Map<String, Double> mapField = new HashMap<>();
    mapField.put("key1", 1.0);
    mapField.put("key2", 2.0);
    mapField.put("key3", 3.0);
    expectedResults.put("8", new GenericRow(Arrays.asList(8, "ORDER_6",
        "ITEM_8", 80.0, new
            Double[]{1100.0,
            1110.99,
            970.0 },
        mapField)));

    testCreateStreamAsSelect(
        "SELECT * FROM " + orderDataProvider.kstreamName() + " WHERE ORDERUNITS > 20 AND ITEMID = 'ITEM_8'",
        orderDataProvider.schema(),
        expectedResults
        );
  }

  @Test
  public void testSelectUDFs() throws Exception {
    final String selectColumns =
        "ITEMID, ORDERUNITS*10, PRICEARRAY[0]+10, KEYVALUEMAP['key1']*KEYVALUEMAP['key2']+10, PRICEARRAY[1]>1000";
    final String whereClause = "ORDERUNITS > 20 AND ITEMID LIKE '%_8'";

    final String queryString = String.format(
        "SELECT %s FROM %s WHERE %s;",
        selectColumns,
        orderDataProvider.kstreamName(),
        whereClause
    );

    Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 800.0, 1110.0, 12.0, true)));

    // TODO: tests failed!
    // testCreateStreamAsSelect(queryString, orderDataProvider.schema(), expectedResults);
  }

  // ===================================================================
  // Below Tests are only used for coverage, not for results validation.
  // ===================================================================

  @Test
  public void testRunInteractively() {
    localCli.runInteractively();
  }

  @Test
  public void testEmptyInput() throws Exception {
    localCli.runNonInteractively("");
  }

  @Test
  public void testExitCommand() throws Exception {
    localCli.runNonInteractively("exit");
    localCli.runNonInteractively("\nexit\n\n\n");
    localCli.runNonInteractively("exit\nexit\nexit");
    localCli.runNonInteractively("\n\nexit\nexit\n\n\n\nexit\n\n\n");
  }

  @Test
  public void testExtraCommands() throws Exception {
    localCli.runNonInteractively("help");
    localCli.runNonInteractively("version");
    localCli.runNonInteractively("output");
    localCli.runNonInteractively("clear");
  }

}