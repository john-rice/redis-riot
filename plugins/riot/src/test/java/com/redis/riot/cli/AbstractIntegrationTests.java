package com.redis.riot.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.json.JacksonJsonObjectReader;
import org.springframework.batch.item.json.JsonItemReader;
import org.springframework.batch.item.json.builder.JsonItemReaderBuilder;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.util.FileCopyUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.redis.lettucemod.api.StatefulRedisModulesConnection;
import com.redis.lettucemod.api.sync.RedisModulesCommands;
import com.redis.lettucemod.search.CreateOptions;
import com.redis.lettucemod.search.Document;
import com.redis.lettucemod.search.Field;
import com.redis.lettucemod.search.SearchResults;
import com.redis.lettucemod.search.Suggestion;
import com.redis.lettucemod.search.SuggetOptions;
import com.redis.lettucemod.search.TextField.PhoneticMatcher;
import com.redis.lettucemod.timeseries.MRangeOptions;
import com.redis.lettucemod.timeseries.RangeResult;
import com.redis.lettucemod.timeseries.TimeRange;
import com.redis.lettucemod.util.RedisModulesUtils;
import com.redis.riot.core.GeneratorImport;
import com.redis.riot.core.ReplicationMode;
import com.redis.riot.file.resource.XmlItemReader;
import com.redis.riot.file.resource.XmlItemReaderBuilder;
import com.redis.riot.file.resource.XmlObjectReader;
import com.redis.spring.batch.KeyValue;
import com.redis.spring.batch.gen.DataType;
import com.redis.spring.batch.gen.GeneratorItemReader;
import com.redis.spring.batch.util.KeyComparison;
import com.redis.spring.batch.util.KeyComparison.Status;
import com.redis.spring.batch.util.KeyComparisonItemReader;
import com.redis.testcontainers.RedisServer;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.GeoArgs;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisGeoCommands;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisServerCommands;
import io.lettuce.core.api.sync.RedisSetCommands;
import io.lettuce.core.api.sync.RedisSortedSetCommands;
import io.lettuce.core.api.sync.RedisStreamCommands;
import io.lettuce.core.cluster.SlotHash;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.ParseResult;

@SuppressWarnings("unchecked")
public abstract class AbstractIntegrationTests extends AbstractRiotTests {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final int BEER_CSV_COUNT = 2410;

    public static final int BEER_JSON_COUNT = 216;

    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration COMPARE_TIMEOUT = Duration.ofSeconds(3);

    private static final int DEFAULT_NOTIFICATION_QUEUE_CAPACITY = 100000;

    private static Path tempDir;

    protected static String name(Map<String, String> beer) {
        return beer.get("name");
    }

    protected static String style(Map<String, String> beer) {
        return beer.get("style");
    }

    protected static double abv(Map<String, String> beer) {
        return Double.parseDouble(beer.get("abv"));
    }

    @BeforeAll
    public void setupFiles() throws IOException {
        tempDir = Files.createTempDirectory(getClass().getName());
    }

    protected List<String> testImport(String filename, String pattern, int count) throws Exception {
        execute(filename);
        RedisKeyCommands<String, String> sync = connection.sync();
        List<String> keys = sync.keys(pattern);
        Assertions.assertEquals(count, keys.size());
        return keys;
    }

    private AbstractRedisClient targetClient;

    private StatefulRedisModulesConnection<String, String> targetConnection;

    @BeforeAll
    void setupTarget() {
        RedisServer target = getTargetRedisServer();
        target.start();
        targetClient = client(target);
        targetConnection = RedisModulesUtils.connection(targetClient);
    }

    @AfterAll
    void teardownTarget() {
        targetConnection.close();
        targetClient.shutdown();
        targetClient.getResources().shutdown();
        getTargetRedisServer().close();
    }

    protected abstract RedisServer getTargetRedisServer();

    @BeforeEach
    void flushAllTarget() throws InterruptedException {
        targetConnection.sync().flushall();
        RedisModulesCommands<String, String> sync = targetConnection.sync();
        awaitEquals(() -> 0L, sync::dbsize);
    }

    @Override
    protected void configureCommand(Object command) {
        super.configureCommand(command);
        if (command instanceof ReplicateCommand) {
            ReplicateCommand replicationCommand = (ReplicateCommand) command;
            replicationCommand.targetRedisClientArgs.uriArgs.uri = getTargetRedisServer().getRedisURI();
            if (replicationCommand.mode == ReplicationMode.LIVE || replicationCommand.mode == ReplicationMode.LIVEONLY) {
                replicationCommand.readerArgs.setIdleTimeout(DEFAULT_IDLE_TIMEOUT.toMillis());
                replicationCommand.readerArgs.setNotificationQueueCapacity(DEFAULT_NOTIFICATION_QUEUE_CAPACITY);
            }
        }
    }

    private String replace(String file) {
        return file.replace("/tmp", tempDir.toString());
    }

    protected Path tempFile(String filename) throws IOException {
        Path path = tempDir.resolve(filename);
        if (Files.exists(path)) {
            Files.delete(path);
        }
        return path;
    }

    protected <T> List<T> readAll(AbstractItemCountingItemStreamItemReader<T> reader) throws Exception {
        reader.open(new ExecutionContext());
        List<T> records = new ArrayList<>();
        T record;
        while ((record = reader.read()) != null) {
            records.add(record);
        }
        reader.close();
        return records;
    }

    @Test
    void fileImportFW() throws Exception {
        testImport("file-import-fw", "account:*", 5);
        RedisHashCommands<String, String> hash = connection.sync();
        Map<String, String> account101 = hash.hgetall("account:101");
        // Account LastName FirstName Balance CreditLimit AccountCreated Rating
        // 101 Reeves Keanu 9315.45 10000.00 1/17/1998 A
        Assertions.assertEquals("Reeves", account101.get("LastName"));
        Assertions.assertEquals("Keanu", account101.get("FirstName"));
        Assertions.assertEquals("A", account101.get("Rating"));
    }

    @Test
    void fileImportCSV() throws Exception {
        testImport("file-import-csv", "beer:*", BEER_CSV_COUNT);
    }

    @Test
    void fileImportCSVSkipLines() throws Exception {
        testImport("file-import-csv-skiplines", "beer:*", BEER_CSV_COUNT - 10);
    }

    @Test
    void fileImportCSVMax() throws Exception {
        testImport("file-import-csv-max", "beer:*", 12);
    }

    @Test
    void fileImportPSV() throws Exception {
        testImport("file-import-psv", "sample:*", 3);
    }

    @Test
    void fileImportTSV() throws Exception {
        testImport("file-import-tsv", "sample:*", 4);
    }

    @Test
    void fileImportType() throws Exception {
        testImport("file-import-type", "sample:*", 3);
    }

    @Test
    void fileImportExclude() throws Exception {
        execute("file-import-exclude");
        RedisHashCommands<String, String> sync = connection.sync();
        Map<String, String> beer1036 = sync.hgetall("beer:1036");
        Assertions.assertEquals("Lower De Boom", name(beer1036));
        Assertions.assertEquals("American Barleywine", style(beer1036));
        Assertions.assertEquals("368", beer1036.get("brewery_id"));
        Assertions.assertFalse(beer1036.containsKey("row"));
        Assertions.assertFalse(beer1036.containsKey("ibu"));
    }

    @Test
    void fileImportInclude() throws Exception {
        execute("file-import-include");
        RedisHashCommands<String, String> sync = connection.sync();
        Map<String, String> beer1036 = sync.hgetall("beer:1036");
        Assertions.assertEquals(3, beer1036.size());
        Assertions.assertEquals("Lower De Boom", name(beer1036));
        Assertions.assertEquals("American Barleywine", style(beer1036));
        Assertions.assertEquals(0.099, abv(beer1036));
    }

    @Test
    void fileImportFilter() throws Exception {
        testImport("file-import-filter", "beer:*", 424);
    }

    @Test
    void fileImportRegex() throws Exception {
        execute("file-import-regex");
        RedisHashCommands<String, String> sync = connection.sync();
        Map<String, String> airport1 = sync.hgetall("airport:1");
        Assertions.assertEquals("Pacific", airport1.get("region"));
        Assertions.assertEquals("Port_Moresby", airport1.get("city"));
    }

    @Test
    void fileImportGlob() throws Exception {
        execute("file-import-glob", this::executeImportGlob);
        RedisKeyCommands<String, String> sync = connection.sync();
        List<String> keys = sync.keys("beer:*");
        Assertions.assertEquals(BEER_CSV_COUNT, keys.size());
    }

    private int executeImportGlob(ParseResult parseResult) {

        FileImportCommand command = command(parseResult);
        try {
            Path dir = Files.createTempDirectory("import-glob");
            FileCopyUtils.copy(getClass().getClassLoader().getResourceAsStream("files/beers1.csv"),
                    Files.newOutputStream(dir.resolve("beers1.csv")));
            FileCopyUtils.copy(getClass().getClassLoader().getResourceAsStream("files/beers2.csv"),
                    Files.newOutputStream(dir.resolve("beers2.csv")));
            File file = new File(command.files.get(0));
            command.files = Arrays.asList(dir.resolve(file.getName()).toString());
        } catch (IOException e) {
            throw new RuntimeException("Could not configure import-glob", e);
        }
        return ExitCode.OK;
    }

    @Test
    void fileImportGeoadd() throws Exception {
        execute("file-import-geoadd");
        RedisGeoCommands<String, String> sync = connection.sync();
        Set<String> results = sync.georadius("airportgeo", -21, 64, 200, GeoArgs.Unit.mi);
        Assertions.assertTrue(results.contains("18"));
        Assertions.assertTrue(results.contains("19"));
        Assertions.assertTrue(results.contains("11"));
    }

    @Test
    void fileImportGeoProcessor() throws Exception {
        execute("file-import-geo-processor");
        RedisHashCommands<String, String> sync = connection.sync();
        Map<String, String> airport3469 = sync.hgetall("airport:18");
        Assertions.assertEquals("-21.9405994415,64.1299972534", airport3469.get("location"));
    }

    @Test
    void fileImportProcess() throws Exception {
        testImport("file-import-process", "event:*", 568);
        RedisHashCommands<String, String> hash = connection.sync();
        Map<String, String> event = hash.hgetall("event:248206");
        Instant date = Instant.ofEpochMilli(Long.parseLong(event.get("EpochStart")));
        Assertions.assertTrue(date.isBefore(Instant.now()));
    }

    @Test
    void fileImportProcessElvis() throws Exception {
        testImport("file-import-process-elvis", "beer:*", BEER_CSV_COUNT);
        Map<String, String> beer1436 = connection.sync().hgetall("beer:1436");
        Assertions.assertEquals("10", beer1436.get("ibu"));
    }

    @Test
    void fileImportMultiCommands() throws Exception {
        execute("file-import-multi-commands");
        RedisKeyCommands<String, String> sync = connection.sync();
        List<String> beers = sync.keys("beer:*");
        Assertions.assertEquals(BEER_CSV_COUNT, beers.size());
        for (String beer : beers) {
            Map<String, String> hash = ((RedisHashCommands<String, String>) sync).hgetall(beer);
            Assertions.assertTrue(hash.containsKey("name"));
            Assertions.assertTrue(hash.containsKey("brewery_id"));
        }
        RedisSetCommands<String, String> set = connection.sync();
        Set<String> breweries = set.smembers("breweries");
        Assertions.assertEquals(558, breweries.size());
    }

    @Test
    void fileImportBad() throws Exception {
        Assertions.assertEquals(0, execute("file-import-bad"));
    }

    @Test
    void fileImportGCS() throws Exception {
        testImport("file-import-gcs", "beer:*", 4432);
        Map<String, String> beer1 = connection.sync().hgetall("beer:1");
        Assertions.assertEquals("Hocus Pocus", name(beer1));
    }

    @Test
    void fileImportS3() throws Exception {
        testImport("file-import-s3", "beer:*", 4432);
        Map<String, String> beer1 = connection.sync().hgetall("beer:1");
        Assertions.assertEquals("Hocus Pocus", name(beer1));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void fileDumpImport() throws Exception {
        List<KeyValue> records = exportToJsonFile();
        RedisServerCommands<String, String> sync = connection.sync();
        sync.flushall();
        execute("dump-import", this::executeFileDumpImport);
        awaitEquals(records::size, () -> Math.toIntExact(sync.dbsize()));
    }

    private int executeFileDumpImport(ParseResult parseResult) {
        FileDumpImportCommand command = command(parseResult);
        command.files = command.files.stream().map(this::replace).collect(Collectors.toList());
        return ExitCode.OK;
    }

    private int executeFileDumpExport(ParseResult parseResult) {
        FileDumpExportCommand command = command(parseResult);
        command.file = replace(command.file);
        return ExitCode.OK;
    }

    @Test
    @Disabled("Needs update")
    void fileImportJSONElastic() throws Exception {
        execute("file-import-json-elastic");
        RedisKeyCommands<String, String> sync = connection.sync();
        Assertions.assertEquals(2, sync.keys("estest:*").size());
        Map<String, String> doc1 = ((RedisHashCommands<String, String>) sync).hgetall("estest:doc1");
        Assertions.assertEquals("ruan", doc1.get("_source.name"));
        Assertions.assertEquals("3", doc1.get("_source.articles[1]"));
    }

    @Test
    void fileImportJSON() throws Exception {
        testImport("file-import-json", "beer:*", BEER_JSON_COUNT);
        Map<String, String> beer1 = connection.sync().hgetall("beer:1");
        Assertions.assertEquals("Hocus Pocus", beer1.get("name"));
    }

    @Test
    void fileImportXML() throws Exception {
        testImport("file-import-xml", "trade:*", 3);
        Map<String, String> trade1 = connection.sync().hgetall("trade:1");
        Assertions.assertEquals("XYZ0001", trade1.get("isin"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void fileExportJSON() throws Exception {
        List<KeyValue> records = exportToJsonFile();
        RedisServerCommands<String, String> sync = connection.sync();
        Assertions.assertEquals(sync.dbsize(), records.size());
    }

    @SuppressWarnings("rawtypes")
    @Test
    @Disabled("Needs update")
    void fileExportJSONGz() throws Exception {
        Path file = tempFile("beers.json.gz");
        execute("file-import-json");
        execute("file-export-json-gz", this::executeFileDumpExport);
        JsonItemReaderBuilder<Map> builder = new JsonItemReaderBuilder<>();
        builder.name("json-file-reader");
        FileSystemResource resource = new FileSystemResource(file);
        builder.resource(new InputStreamResource(new GZIPInputStream(resource.getInputStream()), resource.getDescription()));
        JacksonJsonObjectReader<Map> objectReader = new JacksonJsonObjectReader<>(Map.class);
        objectReader.setMapper(new ObjectMapper());
        builder.jsonObjectReader(objectReader);
        JsonItemReader<Map> reader = builder.build();
        List<Map> records = readAll(reader);
        RedisKeyCommands<String, String> sync = connection.sync();
        Assertions.assertEquals(sync.keys("beer:*").size(), records.size());
    }

    @SuppressWarnings("rawtypes")
    private List<KeyValue> exportToJsonFile() throws Exception {
        String filename = "file-export-json";
        Path file = tempFile("redis.json");
        generate(filename);
        execute(filename, this::executeFileDumpExport);
        JsonItemReaderBuilder<KeyValue> builder = new JsonItemReaderBuilder<>();
        builder.name("json-data-structure-file-reader");
        builder.resource(new FileSystemResource(file));
        JacksonJsonObjectReader<KeyValue> objectReader = new JacksonJsonObjectReader<>(KeyValue.class);
        objectReader.setMapper(new ObjectMapper());
        builder.jsonObjectReader(objectReader);
        JsonItemReader<KeyValue> reader = builder.build();
        return readAll(reader);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void fileExportXml() throws Exception {
        String filename = "file-export-xml";
        generate(filename);
        Path file = tempFile("redis.xml");
        execute(filename, this::executeFileDumpExport);
        XmlItemReaderBuilder<KeyValue> builder = new XmlItemReaderBuilder<>();
        builder.name("xml-file-reader");
        builder.resource(new FileSystemResource(file));
        XmlObjectReader<KeyValue> xmlObjectReader = new XmlObjectReader<>(KeyValue.class);
        xmlObjectReader.setMapper(new XmlMapper());
        builder.xmlObjectReader(xmlObjectReader);
        XmlItemReader<KeyValue> reader = builder.build();
        List<KeyValue> records = readAll(reader);
        RedisModulesCommands<String, String> sync = connection.sync();
        Assertions.assertEquals(sync.dbsize(), records.size());
        for (KeyValue<String> record : records) {
            String key = record.getKey();
            switch (record.getType()) {
                case KeyValue.HASH:
                    Assertions.assertEquals(record.getValue(), sync.hgetall(key));
                    break;
                case KeyValue.STRING:
                    Assertions.assertEquals(record.getValue(), sync.get(key));
                    break;
                default:
                    break;
            }
        }
    }

    @Test
    void fileImportJSONGzip() throws Exception {
        testImport("file-import-json-gz", "beer:*", BEER_JSON_COUNT);
    }

    @Test
    void fileImportSugadd() throws Exception {
        assertExecutionSuccessful(execute("file-import-sugadd"));
        List<Suggestion<String>> suggestions = connection.sync().ftSugget("names", "Bea",
                SuggetOptions.builder().withPayloads(true).build());
        Assertions.assertEquals(5, suggestions.size());
        Assertions.assertEquals("American Blonde Ale", suggestions.get(0).getPayload());
    }

    @Test
    void fileImportElasticJSON() throws Exception {
        assertExecutionSuccessful(execute("file-import-json-elastic-jsonset"));
        RedisModulesCommands<String, String> sync = connection.sync();
        Assertions.assertEquals(2, sync.keys("elastic:*").size());
        ObjectMapper mapper = new ObjectMapper();
        String doc1 = sync.jsonGet("elastic:doc1");
        String expected = "[{\"_index\":\"test-index\",\"_type\":\"docs\",\"_id\":\"doc1\",\"_score\":1,\"_source\":{\"name\":\"ruan\",\"age\":30,\"articles\":[\"1\",\"3\"]}}]";
        Assertions.assertEquals(mapper.readTree(expected), mapper.readTree(doc1));
    }

    @Test
    void fakerHash() throws Exception {
        List<String> keys = testImport("faker-import-hset", "person:*", 1000);
        Map<String, String> person = connection.sync().hgetall(keys.get(0));
        Assertions.assertTrue(person.containsKey("firstName"));
        Assertions.assertTrue(person.containsKey("lastName"));
        Assertions.assertTrue(person.containsKey("address"));
    }

    @Test
    void fakerSet() throws Exception {
        execute("faker-import-sadd");
        RedisSetCommands<String, String> sync = connection.sync();
        Set<String> names = sync.smembers("got:characters");
        Assertions.assertTrue(names.size() > 10);
        for (String name : names) {
            Assertions.assertFalse(name.isEmpty());
        }
    }

    @Test
    void fakerZset() throws Exception {
        execute("faker-import-zadd");
        RedisKeyCommands<String, String> sync = connection.sync();
        List<String> keys = sync.keys("leases:*");
        Assertions.assertTrue(keys.size() > 100);
        String key = keys.get(0);
        Assertions.assertTrue(((RedisSortedSetCommands<String, String>) sync).zcard(key) > 0);
    }

    @Test
    void fakerStream() throws Exception {
        execute("faker-import-xadd");
        RedisStreamCommands<String, String> sync = connection.sync();
        List<StreamMessage<String, String>> messages = sync.xrange("teststream:1", Range.unbounded());
        Assertions.assertTrue(messages.size() > 0);
    }

    @Test
    @Disabled("Needs update")
    void fakerInfer() throws Exception {
        String INDEX = "beerIdx";
        String FIELD_ID = "id";
        String FIELD_ABV = "abv";
        String FIELD_NAME = "name";
        String FIELD_STYLE = "style";
        String FIELD_OUNCES = "ounces";
        connection.sync().ftCreate(INDEX, CreateOptions.<String, String> builder().prefix("beer:").build(),
                Field.tag(FIELD_ID).sortable().build(), Field.text(FIELD_NAME).sortable().build(),
                Field.text(FIELD_STYLE).matcher(PhoneticMatcher.ENGLISH).sortable().build(),
                Field.numeric(FIELD_ABV).sortable().build(), Field.numeric(FIELD_OUNCES).sortable().build());
        execute("faker-import-infer");
        SearchResults<String, String> results = connection.sync().ftSearch(INDEX, "*");
        Assertions.assertEquals(1000, results.getCount());
        Document<String, String> doc1 = results.get(0);
        Assertions.assertNotNull(doc1.get(FIELD_ABV));
    }

    @Test
    @Disabled("Flaky test")
    void fakerTsAdd() throws Exception {
        execute("faker-import-tsadd");
        Assertions.assertEquals(10, connection.sync().tsRange("ts:gen", TimeRange.unbounded()).size());
    }

    @Test
    void fakerTsAddWithOptions() throws Exception {
        execute("faker-import-tsadd-options");
        List<RangeResult<String, String>> results = connection.sync().tsMrange(TimeRange.unbounded(),
                MRangeOptions.<String, String> filters("character1=Einstein").build());
        Assertions.assertFalse(results.isEmpty());
    }

    @Test
    void generateTypes() throws Exception {
        execute("generate");
        Assertions.assertEquals(Math.min(GeneratorImport.DEFAULT_COUNT, GeneratorItemReader.DEFAULT_KEY_RANGE.getMax()),
                connection.sync().dbsize());
    }

    @Test
    void replicate() throws Throwable {
        String filename = "replicate";
        generate(filename);
        Assertions.assertTrue(connection.sync().dbsize() > 0);
        execute(filename);
    }

    @Test
    void replicateDryRun() throws Throwable {
        String filename = "replicate-dry-run";
        generate(filename);
        Assertions.assertTrue(connection.sync().dbsize() > 0);
        execute(filename);
        Assertions.assertEquals(0, targetConnection.sync().dbsize());
    }

    @Test
    void replicateHLL() throws Throwable {
        String key = "crawled:20171124";
        String value = "http://www.google.com/";
        connection.sync().pfadd(key, value);
        Assertions.assertEquals(0, execute("replicate-hll"));
        awaitCompare();
    }

    private void awaitCompare() {
        Awaitility.await().timeout(COMPARE_TIMEOUT).until(this::compare);
    }

    @Test
    void replicateKeyProcessor() throws Throwable {
        String filename = "replicate-key-processor";
        GeneratorItemReader generator = generator();
        generator.setMaxItemCount(200);
        generator.setTypes(DataType.HASH);
        generate(filename, DEFAULT_BATCH_SIZE, generator);
        Long sourceSize = connection.sync().dbsize();
        Assertions.assertTrue(sourceSize > 0);
        execute(filename);
        Assertions.assertEquals(sourceSize, targetConnection.sync().dbsize());
        Assertions.assertEquals(connection.sync().hgetall("gen:1"), targetConnection.sync().hgetall("0:gen:1"));
    }

    @Test
    void replicateKeyExclude() throws Throwable {
        String filename = "replicate-key-exclude";
        int goodCount = 200;
        GeneratorItemReader generator = generator();
        generator.setMaxItemCount(goodCount);
        generator.setTypes(DataType.HASH);
        generate(filename, generator);
        GeneratorItemReader generator2 = generator();
        int badCount = 100;
        generator2.setMaxItemCount(badCount);
        generator2.setKeyspace("bad");
        generator2.setTypes(DataType.HASH);
        generate(filename + "-2", generator2);
        Assertions.assertEquals(badCount, connection.sync().keys("bad:*").size());
        execute(filename);
        Assertions.assertEquals(goodCount, targetConnection.sync().keys("gen:*").size());
    }

    @Test
    void replicateLiveKeyExclude() throws Throwable {
        int goodCount = 200;
        int badCount = 100;
        String filename = "replicate-live-key-exclude";
        connection.sync().configSet("notify-keyspace-events", "AK");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            GeneratorItemReader generator = generator();
            generator.setMaxItemCount(goodCount);
            generator.setTypes(DataType.HASH);
            GeneratorItemReader generator2 = generator();
            generator2.setMaxItemCount(badCount);
            generator2.setTypes(DataType.HASH);
            generator2.setKeyspace("bad");
            try {
                generate(filename, generator);
                generate(filename + "-2", generator2);
            } catch (Exception e) {
                log.error("Could not generate data", e);
            }
        }, 500, TimeUnit.MILLISECONDS);
        execute(filename);
        awaitUntil(() -> connection.sync().keys("bad:*").size() == badCount);
        awaitUntil(() -> targetConnection.sync().keys("gen:*").size() == goodCount);
    }

    @Test
    void replicateLive() throws Exception {
        runLiveReplication("replicate-live");
    }

    @Test
    void replicateLiveKeySlot() throws Exception {
        String filename = "replicate-live-keyslot";
        connection.sync().configSet("notify-keyspace-events", "AK");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        GeneratorItemReader generator = generator();
        generator.setMaxItemCount(300);
        executor.schedule(() -> {
            try {
                generate(filename, 1, generator);
            } catch (Exception e) {
                log.error("Could not generate data", e);
            }
        }, 500, TimeUnit.MILLISECONDS);
        execute(filename);
        List<String> keys = targetConnection.sync().keys("*");
        for (String key : keys) {
            int slot = SlotHash.getSlot(key);
            Assertions.assertTrue(slot >= 0 && slot <= 8000);
        }
    }

    @Test
    void replicateDs() throws Throwable {
        String filename = "replicate-ds";
        GeneratorItemReader generator = generator();
        generator.setMaxItemCount(12000);
        generate(filename, DEFAULT_BATCH_SIZE, generator);
        Assertions.assertTrue(connection.sync().dbsize() > 0);
        execute(filename);
    }

    protected void runLiveReplication(String filename) throws Exception {
        connection.sync().configSet("notify-keyspace-events", "AK");
        generate(filename, DEFAULT_BATCH_SIZE, generator(3000));
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            GeneratorItemReader generator = generator(3500);
            generator.setCurrentItemCount(3000);
            generator.setMaxItemCount(3500);
            try {
                run(filename + "-generate-live", 1, generator, structWriter(client));
            } catch (Exception e) {
                log.error("Could not generate data", e);
            }
        }, 500, TimeUnit.MILLISECONDS);
        execute(filename);
        awaitCompare();
    }

    protected KeyComparisonItemReader comparisonReader() {
        KeyComparisonItemReader reader = new KeyComparisonItemReader(client, targetClient);
        reader.getLeft().setJobRepository(jobRepository);
        reader.setTtlTolerance(Duration.ofMillis(100));
        return reader;
    }

    protected boolean compare() throws JobExecutionException {
        if (connection.sync().dbsize().equals(0L)) {
            log.info("Source database is empty");
            return false;
        }
        if (!connection.sync().dbsize().equals(targetConnection.sync().dbsize())) {
            log.info("Source and target databases have different sizes");
            return false;
        }
        KeyComparisonItemReader reader = comparisonReader();
        SynchronizedListItemWriter<KeyComparison> writer = new SynchronizedListItemWriter<>();
        run("compare-" + id(), DEFAULT_BATCH_SIZE, reader, writer);
        if (writer.getWrittenItems().isEmpty()) {
            log.info("No comparison items were written");
            return false;
        }
        for (KeyComparison comparison : writer.getWrittenItems()) {
            if (comparison.getStatus() != Status.OK) {
                log.error(String.format("Key %s has status %s", comparison.getSource().getKey(), comparison.getStatus()));
                return false;
            }
        }
        return true;
    }

}
