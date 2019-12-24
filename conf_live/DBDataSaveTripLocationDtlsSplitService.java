package cgm.kafka.services;

import cgm.kafka.kafka.utils.DbUpdate;
import cgm.kafka.master.MasterProperty;
import cgm.kafka.model.TripDataMaster;
import cgm.kafka.model.TripLocationDtls;
import cgm.kafka.redis.RedisProvider;
import cgm.kafka.serializer.GsonUtil;
import cgm.kafka.serializer.Serializer;
import cgm.kafka.table.Operations;
import cgm.kafka.table.PostgreSqlConnect;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import ncommunity.utils.date.DateParser;
import ncommunity.utils.file.FileTool;
import ncommunity.utils.string.RandomStringGenerator;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Predicate;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static cgm.kafka.kafka.utils.Constants.*;

public class DBDataSaveTripLocationDtlsSplitService {
    static Properties prop = null;
    static Properties dbProperties = null;

    private static String dbConnection;
    private static String dbUser;
    private static String dbPass;
    private static Connection connection = null;
    private static String tripStateTopic = null;
//    private static Jedis jedis = null;
    static Properties redisProperties = null;
    private static RedisProvider redisProvider = null;
    private static Map<Integer, Date> tripDateMap = new HashMap<>();


    public static void main(String[] args) {

        try {
            prop = MasterProperty.getServiceClassProperty(DBDataSaveTripLocationDtlsSplitService.class);
            dbProperties = MasterProperty.getCommonProperty(DB_KEYWORD);
            redisProperties = MasterProperty.getCommonProperty(REDIS_KEYWORD);
            /*args = new String[]{"1"};*/
        } catch (Exception e) {
            e.printStackTrace();
        }

        final String clientTopicPrefix = dbProperties.getProperty("TOPIC.clientTopicPrefix");
        final String redisTripDataMasterKey = redisProperties.getProperty(TRIP_DATA_MASTER);


        boolean isPrintInputTableLog = Boolean.valueOf(prop.getProperty(logPropPrefix + "printInputDataLog"));
        boolean isPrintOutputTableLog = Boolean.valueOf(prop.getProperty(logPropPrefix + "printOutputDataLog"));

        dbConnection = dbProperties.getProperty(dbPropPrefix + "dbConnection");
        dbUser = dbProperties.getProperty(dbPropPrefix + "dbUser");
        dbPass = dbProperties.getProperty(dbPropPrefix + "dbPass");

        final String fromTopic = clientTopicPrefix + prop.getProperty(TRIP_STATE_TOPIC);
        final String fromTopicEven = clientTopicPrefix + prop.getProperty(TRIP_STATE_EVEN_TOPIC);
        final String fromTopicOdd = clientTopicPrefix + prop.getProperty(TRIP_STATE_ODD_TOPIC);

        StreamsBuilder builder = new StreamsBuilder();
        Properties streamsConfig = new Properties();

        String finalTopicName = fromTopic;
        if (args != null && args.length > 0) {
            try {
                if (Integer.parseInt(args[0]) % 2 != 0) {
                    finalTopicName = fromTopicOdd;
                    streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "application.id1"));
                    streamsConfig.put(StreamsConfig.STATE_DIR_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "state.dir1"));
                } else {
                    finalTopicName = fromTopicEven;
                    streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "application.id2"));
                    streamsConfig.put(StreamsConfig.STATE_DIR_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "state.dir2"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                finalTopicName = fromTopic;
                streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "application.id1"));
                streamsConfig.put(StreamsConfig.STATE_DIR_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "state.dir1"));
            }
        } else {
            finalTopicName = fromTopic;
            streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "application.id1"));
            streamsConfig.put(StreamsConfig.STATE_DIR_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "state.dir1"));
        }

        streamsConfig.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, prop.getProperty(streamPropPrefix + "bootstrap.servers"));
        streamsConfig.put(StreamsConfig.CLIENT_ID_CONFIG, clientTopicPrefix + prop.getProperty(streamPropPrefix + "client.id"));
        streamsConfig.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), prop.getProperty(streamPropPrefix + "consumer.auto.offset.reset"));
        streamsConfig.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, prop.getProperty(streamPropPrefix + "commit.interval.ms"));
        streamsConfig.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfig.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        //streamsConfig.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, prop.getProperty(streamPropPrefix + "default.timestamp.extractor"));
        streamsConfig.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, prop.getProperty(streamPropPrefix + "replication.factor"));

        boolean CLIENT_RANDOM_ID = prop.getProperty(clientPropPrefix + "clientRandomId", "false").equalsIgnoreCase("true");

        if (CLIENT_RANDOM_ID) {
            streamsConfig.setProperty(StreamsConfig.CLIENT_ID_CONFIG, streamsConfig.getProperty(StreamsConfig.CLIENT_ID_CONFIG) + RandomStringGenerator.generateRandomString(6, RandomStringGenerator.Mode.ALPHANUMERIC));
        }

        redisProvider = new RedisProvider(true, redisProperties.getProperty(REDIS_HOST), Integer.parseInt(redisProperties.getProperty(REDIS_PORT)), redisProperties.getProperty(REDIS_AUTH), Integer.parseInt(redisProperties.getProperty(DATABASE_ID_2)), (int) TimeUnit.SECONDS.toMillis(Integer.parseInt(redisProperties.getProperty(CONNECTION_TIME_OUT))), Boolean.parseBoolean(redisProperties.getProperty(DISTROY_CACHING_ON_CONTEXT_UNLOAD)), Integer.parseInt(redisProperties.getProperty(EXPIRY_TIME)), Integer.parseInt(redisProperties.getProperty(REDIS_REPORT_MAX_LIMIT)));
//        jedis = redisProvider.getConnection();

        String tripLocationDetailsTableName = dbProperties.getProperty("tripLocationDetailsTableName");

        System.out.println("ApplicationID : " + streamsConfig.getProperty(StreamsConfig.APPLICATION_ID_CONFIG) + "  \nClientId : " + streamsConfig.getProperty(StreamsConfig.CLIENT_ID_CONFIG));

        connection = PostgreSqlConnect.connectToDatabaseOrDie(dbConnection, dbUser, dbPass);

        KStream<String, TripDataMaster> sourceDataStream = builder.stream(finalTopicName, Consumed.with(Serializer.getSerde(String.class), Serializer.getSerde(TripDataMaster.class)));

        if (isPrintInputTableLog) {
            sourceDataStream.foreach(new ForeachAction<String, TripDataMaster>() {
                @Override
                public void apply(String s, TripDataMaster tripDataMaster) {
                    System.out.println("============Recived Data from trip state:" + GsonUtil.getGson1().toJson(tripDataMaster));
                }
            });
        }

        sourceDataStream.filter(new Predicate<String, TripDataMaster>() {
            @Override
            public boolean test(String key, TripDataMaster tripDataMaster) {
                if (tripDataMaster != null) {
                    if (tripDataMaster.getVehicleDataSummaryLast() != null && tripDataMaster.getVehicleDataSummaryLast().getDateTime() != null) {
                        if (tripDateMap.containsKey(tripDataMaster.getTripDetails().getTripid())) {
                            return tripDataMaster.getVehicleDataSummaryLast().getDateTime().getTime() >= tripDateMap.get(tripDataMaster.getTripDetails().getTripid()).getTime();
                        } else {
                            return true;
                        }
                    }else {
                        return true;
                    }
                }
                return false;
            }
        }).foreach(new ForeachAction<String, TripDataMaster>() {
            @Override
            public void apply(String key, TripDataMaster tripDataMaster) {
                try {
                    if (tripDataMaster != null) {
                        tripDateMap.put(tripDataMaster.getTripDetails().getTripid(), tripDataMaster.getVehicleDataSummaryLast().getDateTime());
/*

                        if (tripDataMaster.getTripDetails().getTripid() != 0) {
                            jedis.set(redisTripDataMasterKey + "_" + String.valueOf(tripDataMaster.getTripDetails().getTripid()), GsonUtil.getGson1().toJson(tripDataMaster));
                        }
*/

                        if (connection != null) {

                            Map<String, TripLocationDtls> tripLocationDtlsMap = tripDataMaster.getTripLocationDtlsMap();
                            if (tripLocationDtlsMap != null && tripLocationDtlsMap.size() > 0) {
                                tripLocationDtlsMap.forEach(new BiConsumer<String, TripLocationDtls>() {
                                    @Override
                                    public void accept(String s, TripLocationDtls tripLocationDtls) {
                                        int numOfRecordsUpdatedTripDetails = 0;

                                        String allColumnNames = getDbUpdateColumn(tripLocationDtls, TripLocationDtls.class);

                                        if (tripLocationDtls.getActualdistance() == 0d && tripLocationDtls.getVialocation() != tripDataMaster.getTripDetails().getFromstid()) {
                                            allColumnNames = allColumnNames.replace(",actualdistance", "");
                                        }

                                        numOfRecordsUpdatedTripDetails = Operations.updateAsJson(connection, tripLocationDetailsTableName, tripLocationDtls, allColumnNames, "WHERE tripid = " + tripLocationDtls.getTripid() + " AND vialocation = " + tripLocationDtls.getVialocation());

                                        if (numOfRecordsUpdatedTripDetails > 0) {
                                            System.out.println("Recored for trip id " + tripDataMaster.getTripDetails().getTripid() + " is updated in trip location Details " + GsonUtil.getGson1().toJson(tripLocationDtls));
                                        }
                                    }
                                });

                            }
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });


        KafkaStreams streams = new KafkaStreams(builder.build(), streamsConfig);
        streams.cleanUp();
        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("In shutdownhook.....");
                try {
                    streams.close();
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }));


        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        exec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {

                String t = "[" + DateParser.DateToString(new Date(), "yyyy-MM-dd HH:mm:ss") + "]" + streams.state().toString();
                System.out.println(t + " || DBDataSaveTripLocationDtlsService");

                try {
                    Path path = FileTool.getPath("service-logs/DBDataSaveTripLocationDtlsService-" + DateParser.DateToString(new Date(), "yyyy-MM-dd") + ".txt", true);
                    Files.write(path, Collections.singletonList(t), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception e) {
                    e.printStackTrace();
                    //exception handling left as an exercise for the reader
                }
            }
        }, 0, 5, TimeUnit.MINUTES);


    }

    private static <T> String getDbUpdateColumn(T masterObj, Class<?> className) {

        List<String> jsonKeysList = new JsonParser().parse(GsonUtil.getGsonWithNull().toJson(masterObj)).getAsJsonObject().entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList());
        ArrayList<Field> fieldArrayList = new ArrayList<>(Arrays.asList(className.getDeclaredFields()));
        Class<?> superclassName = className.getSuperclass();
        if (superclassName != null) {
            fieldArrayList.addAll(Arrays.asList(superclassName.getDeclaredFields()));
        }
        fieldArrayList.stream().filter(field ->
                field.getAnnotation(DbUpdate.class) == null)
                .forEach(field ->
                        jsonKeysList.remove(field.getDeclaredAnnotation(SerializedName.class)
                                .value()));
        return String.join(",", jsonKeysList);
    }


}
