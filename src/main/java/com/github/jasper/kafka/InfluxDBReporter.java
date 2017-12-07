package com.github.jasper.kafka;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import com.yammer.metrics.stats.Snapshot;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;


public class InfluxDBReporter extends AbstractPollingReporter implements MetricProcessor<InfluxDBReporter.Context>  {

    /**
     * Enables the console reporter for the default metrics registry, and causes it to print to
     * influx with the specified period.
     *
     * @param period the period between successive outputs
     * @param address the address of InfluxDB
     * @param database the database to store the metric
     * @param retentionPolicy  the retentionPolicy to store the metric
     * @param username  the username to write into InfluxDB
     * @param password  the password to write into InfluxDB
     * @param consistency  consistency of write into InfluxDB, available value: one, any, all, quorum
     * @param tags custom tags
     * @param unit   the time unit of {@code period}
     */
    public static void enable(long period, String address, String database, String retentionPolicy, String username, String password, String consistency, String tags, TimeUnit unit) {
        enable(Metrics.defaultRegistry(), period, address, database, retentionPolicy, username, password, consistency, tags, unit);
    }

    /**
     * Enables the console reporter for the given metrics registry, and causes it to print to influx
     * with the specified period and unrestricted output.
     *
     * @param metricsRegistry the metrics registry
     * @param period          the period between successive outputs
     * @param address the address of InfluxDB
     * @param database the database to store the metric
     * @param retentionPolicy  the retentionPolicy to store the metric
     * @param username  the username to write into InfluxDB
     * @param password  the password to write into InfluxDB
     * @param consistency  consistency of write into InfluxDB, available value: one, any, all, quorum
     * @param tags custom tags
     * @param unit            the time unit of {@code period}
     */
    public static void enable(MetricsRegistry metricsRegistry, long period, String address, String database, String retentionPolicy, String username, String password, String consistency, String tags, TimeUnit unit) {
        final InfluxDBReporter reporter = new InfluxDBReporter(metricsRegistry,
                address,
                database,
                retentionPolicy,
                username,
                password,
                consistency,
                tags,
                MetricPredicate.ALL, Clock.defaultClock());
        reporter.start(period, unit);
    }


    private static final Logger LOG = LoggerFactory.getLogger(InfluxDBReporter.class);

    private static final MetricPredicate DEFAULT_METRIC_PREDICATE = MetricPredicate.ALL;

//    protected final VirtualMachineMetrics vm = VirtualMachineMetrics.getInstance();
//    public boolean printVMMetrics = true;

    private static final Map<String, InfluxDB.ConsistencyLevel> ConsistencyLevelMap = new HashMap<String, InfluxDB.ConsistencyLevel>(){{
        put("all", InfluxDB.ConsistencyLevel.ALL);
        put("any", InfluxDB.ConsistencyLevel.ANY);
        put("one", InfluxDB.ConsistencyLevel.ONE);
        put("quorum", InfluxDB.ConsistencyLevel.QUORUM);
    }};

    private BatchPoints batchPoints;

//    private String address;
    private String database;
    private String retentionPolicy;
//    private String username;
//    private String password;
    private InfluxDB.ConsistencyLevel consistencyLevel;
    private Map<String, String> tags;

    private InfluxDB influxDBclient;

    private Clock clock;

    private final MetricPredicate predicate;

    private Context context;

    /**
     * simple constructor，for the default metrics registry
     * @param address the address of InfluxDB
     * @param database the database to store the metric
     * @param retentionPolicy  the retentionPolicy to store the metric
     * @param username  the username to write into InfluxDB
     * @param password  the password to write into InfluxDB
     * @param consistency  consistency of write into InfluxDB, available value: one, any, all, quorum
     * @param tags custom tags
     */
    public InfluxDBReporter(String address, String database, String retentionPolicy, String username, String password, String consistency, String tags) {
        this(Metrics.defaultRegistry(), address,database,retentionPolicy,username,password,consistency,tags, MetricPredicate.ALL, Clock.defaultClock());
    }


    /**
     * Creates a new {@link AbstractPollingReporter} instance.
     **/
    public InfluxDBReporter(MetricsRegistry metricsRegistry, String address, String database, String retentionPolicy, String username, String password, String consistency, String tags, MetricPredicate predicate, Clock clock) {
        super(metricsRegistry, "influx-reporter");
//        this.address = address;
        this.database = database;
        this.retentionPolicy = retentionPolicy;
//        this.username = username;
//        this.password = password;
        this.consistencyLevel = getConsistencyLevel(consistency);
        this.tags = formatTags(tags);
        this.influxDBclient = InfluxDBFactory.connect(address, username, password);
        this.clock = clock;
        this.predicate = predicate;
        this.context = new Context() {
            @Override
            public long getTime() {
                return InfluxDBReporter.this.clock.time();
            }
        };
    }


    @Override
    public void run() {
        try {
            this.batchPoints = BatchPoints
                    .database(this.database)
                    .retentionPolicy(this.retentionPolicy)
                    .consistency(this.consistencyLevel)
                    .build();
            printRegularMetrics(context);
            this.influxDBclient.write(batchPoints);
        } catch (Exception e) {
            LOG.error("Cannot send metrics to InfluxDB {}", e);
        }
    }

    void addPoint(Point point){
        batchPoints.point(point);
    }


    private void printRegularMetrics(final Context context) {
        for (Map.Entry<String, SortedMap<MetricName, Metric>> entry : getMetricsRegistry().groupedMetrics(DEFAULT_METRIC_PREDICATE).entrySet()) {
            for (Map.Entry<MetricName, Metric> subEntry : entry.getValue().entrySet()) {
                final MetricName metricName = subEntry.getKey();
                final Metric metric = subEntry.getValue();
                if (predicate.matches(metricName, metric)) {
                    try {
                        metric.processWith(this, subEntry.getKey(), context);
                    } catch (Exception ignored) {
                        LOG.error("Error printing regular metrics:", ignored);
                    }
                }
            }
        }
    }


    public void processGauge(MetricName name, Gauge<?> gauge, Context context) throws Exception {

        Point.Builder pointbuilder = buildMetricsPoint(name, context);
        pointbuilder.tag("metric_type", "gague");

        Object fieldValue = gauge.value();
        String fieldName = "value";
        // Long Interger transfer Float in case of schema conflict
        if (fieldValue instanceof Float)
            pointbuilder.addField(fieldName, (Float) fieldValue);
        else if (fieldValue instanceof Double)
            pointbuilder.addField(fieldName, (Double) fieldValue);
        else if (fieldValue instanceof Long)
            pointbuilder.addField(fieldName, Float.valueOf(((Long) fieldValue).toString()));
        else if (fieldValue instanceof Integer)
            pointbuilder.addField(fieldName, Float.valueOf(((Integer) fieldValue).toString()));
        else if (fieldValue instanceof String)
            pointbuilder.addField(fieldName, (String) fieldValue);
        else
            return;
        addPoint(pointbuilder.build());
    }

    @Override
    public void processCounter(MetricName metricName, Counter counter, Context context) throws Exception {

        Point.Builder pointbuilder = buildMetricsPoint(metricName, context);
        pointbuilder.tag("metric_type", "counter");

        pointbuilder.addField("count", counter.count());
        addPoint(pointbuilder.build());

    }


    @Override
    public void processMeter(MetricName metricName, Metered meter, Context context) throws Exception {

        Point.Builder pointbuilder = buildMetricsPoint(metricName, context);
        pointbuilder.tag("metric_type", "meter");
        pointbuilder.tag("eventType", meter.eventType());


        pointbuilder.addField("count", meter.count());
        pointbuilder.addField("meanRate", meter.meanRate());
        pointbuilder.addField("1MinuteRate", meter.oneMinuteRate());
        pointbuilder.addField("5MinuteRate", meter.fiveMinuteRate());
        pointbuilder.addField("15MinuteRate", meter.fifteenMinuteRate());


        addPoint(pointbuilder.build());

    }


    @Override
    public void processHistogram(MetricName metricName, Histogram histogram, Context context) throws Exception {
        final Snapshot snapshot = histogram.getSnapshot();

        Point.Builder pointbuilder = buildMetricsPoint(metricName, context);
        pointbuilder.tag("metric_type", "histogram");

        pointbuilder.addField("max", histogram.max());
        pointbuilder.addField("mean", histogram.mean());
        pointbuilder.addField("min", histogram.min());
        pointbuilder.addField("stddev", histogram.max());
        pointbuilder.addField("sum", histogram.sum());

        pointbuilder.addField("median", snapshot.getMedian());
        pointbuilder.addField("p75", snapshot.get75thPercentile());
        pointbuilder.addField("p95", snapshot.get95thPercentile());
        pointbuilder.addField("p98", snapshot.get98thPercentile());
        pointbuilder.addField("p99", snapshot.get99thPercentile());
        pointbuilder.addField("p999", snapshot.get999thPercentile());

        addPoint(pointbuilder.build());

    }

    public void processTimer(MetricName metricName, Timer timer, Context context) throws Exception {
        final Snapshot snapshot = timer.getSnapshot();

        Point.Builder pointbuilder = buildMetricsPoint(metricName, context);
        pointbuilder.tag("metric_type", "timer");


        pointbuilder.addField("count", timer.count());
        pointbuilder.addField("meanRate", timer.meanRate());
        pointbuilder.addField("1MinuteRate", timer.oneMinuteRate());
        pointbuilder.addField("5MinuteRate", timer.fiveMinuteRate());
        pointbuilder.addField("15MinuteRate", timer.fifteenMinuteRate());


        pointbuilder.addField("max", timer.max());
        pointbuilder.addField("mean", timer.mean());
        pointbuilder.addField("min", timer.min());
        pointbuilder.addField("stddev", timer.max());
        pointbuilder.addField("sum", timer.sum());

        pointbuilder.addField("median", snapshot.getMedian());
        pointbuilder.addField("p75", snapshot.get75thPercentile());
        pointbuilder.addField("p95", snapshot.get95thPercentile());
        pointbuilder.addField("p98", snapshot.get98thPercentile());
        pointbuilder.addField("p99", snapshot.get99thPercentile());
        pointbuilder.addField("p999", snapshot.get999thPercentile());


        addPoint(pointbuilder.build());
    }

    Point.Builder buildMetricsPoint(MetricName metricName, Context context) {

        Point.Builder pointbuilder = Point.measurement(metricName.getName())
                .time(context.getTime(), TimeUnit.MILLISECONDS)
                .tag(this.tags)
                .tag("group", metricName.getGroup())
                .tag("type", metricName.getType());

        if (metricName.hasScope()) {
            String scope = metricName.getScope();
            List<String> scopes = Arrays.asList(scope.split("\\."));
            if (scopes.size() % 2 == 0) {
                Iterator<String> iterator = scopes.iterator();
                while (iterator.hasNext()) {
                    pointbuilder.tag(iterator.next(), iterator.next());
                }
            } else pointbuilder.tag("scope", scope);
        }
        return pointbuilder;
    }

    InfluxDB.ConsistencyLevel getConsistencyLevel(String consistencyLevel) {
        return ConsistencyLevelMap.get(consistencyLevel);
    }

    Map<String, String> formatTags(String tags) {
       Map<String, String> formatedTags = new HashMap<String, String>();
        String[] kvsArr = tags.split(",");
       for(String kvs: kvsArr) {
          String[] kvArr = kvs.split("=");
          formatedTags.put(kvArr[0], kvArr[1]);
       }
       return formatedTags;
    }

    public interface Context {

        long getTime();
    }
}
