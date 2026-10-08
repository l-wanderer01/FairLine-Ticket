package com.example.fairline.comparison;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/** Real, unchanged JdbcReservationStore; SQL timing proxies add observation only.
 * Closed-loop workers, no artificial sleep while holding locks, isolated database.
 */
class RdbLockContentionTest {
    static final Path OUT = Path.of("build/reports/lock-contention");
    final Queue<Long> response = new ConcurrentLinkedQueue<>();
    final Queue<Long> lockSql = new ConcurrentLinkedQueue<>();
    final Queue<Long> insertSql = new ConcurrentLinkedQueue<>();
    final Queue<Long> poolWait = new ConcurrentLinkedQueue<>();
    volatile boolean recording;
    record Scenario(String name, int clients, boolean spread, boolean sameSeat) {}
    record Observation(long time, int active, int waiting, int blockerEdges, int poolPending) {}
    record Cpu(long time, long usage, long throttled) {}

    @Test void measureRealRowLocksAndCpuUnderIncreasingConcurrency() throws Exception {
        int seconds = Integer.parseInt(System.getenv("RDB_METRICS_SECONDS"));
        int repeats = Integer.parseInt(System.getenv("RDB_METRICS_REPEATS"));
        assertThat(seconds).isBetween(2, 120); assertThat(repeats).isBetween(1, 10);
        Files.createDirectories(OUT);
        List<String> summary = new ArrayList<>();
        summary.add("repeat,scenario,clients,seconds,requests,held,conflicts,limits,errors,rps,mean_ms,p95_ms,p99_ms,lock_sql_mean_ms,lock_sql_p95_ms,scope_insert_mean_ms,pool_acquire_mean_ms,avg_lock_waiters,max_lock_waiters,lock_active_sample_pct,avg_blocker_edges,db_cpu_one_core_pct,db_cpu_two_core_pct,db_cpu_peak_one_core_pct,cpu_throttled_ms,observer_samples");
        try(HikariDataSource load = pool("seat-lock-load",32); HikariDataSource observer = pool("seat-lock-observer",1)) {
            JdbcTemplate setup = new JdbcTemplate(load);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(load);
            Files.writeString(OUT.resolve("environment.txt"), setup.queryForObject("SELECT version()",String.class)
                    +"\nfsync="+setup.queryForObject("SHOW fsync",String.class)
                    +"\nsynchronous_commit="+setup.queryForObject("SHOW synchronous_commit",String.class)
                    +"\nload_pool_max=32\nobserver_pool_max=1\nobserver_period_ms=20\n");
            JdbcReservationStore store = new JdbcReservationStore(timed(load));
            // Warm production JDBC and JVM, not an injected lock-hold duration.
            for(int i=0;i<500;i++) store.hold(1,1,i,"warm-"+i);
            List<Scenario> cases=List.of(new Scenario("shared_schedule",1,false,false),
                    new Scenario("shared_schedule",8,false,false),new Scenario("shared_schedule",32,false,false),
                    new Scenario("spread_schedules",32,true,false),new Scenario("same_seat",32,false,true));
            try {
                for(int repeat=1;repeat<=repeats;repeat++) {
                    List<Scenario> order=new ArrayList<>(cases);
                    if(repeat%2==0) Collections.reverse(order);
                    for(Scenario scenario:order) {
                        String line = run(repeat,scenario,seconds,store,setup,new JdbcTemplate(observer),load);
                        summary.add(line); Files.write(OUT.resolve("results.csv"),summary);
                        System.out.println(line);
                    }
                }
            } finally { Files.write(OUT.resolve("results.csv"),summary); }
        }
    }
    HikariDataSource pool(String name,int size) {
        HikariConfig c=new HikariConfig(); c.setJdbcUrl(System.getenv("RDB_METRICS_DB_URL"));
        c.setUsername(System.getenv("RDB_METRICS_DB_USER"));c.setPassword(System.getenv("RDB_METRICS_DB_PASSWORD"));
        c.setMaximumPoolSize(size);c.setMinimumIdle(size);c.setConnectionTimeout(10000);
        c.addDataSourceProperty("ApplicationName",name);return new HikariDataSource(c);
    }
    DataSource timed(HikariDataSource source) {
        return new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                long start=System.nanoTime();Connection c=super.getConnection();
                if(recording)poolWait.add(System.nanoTime()-start);
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)-> {
                    Object result=invoke(method,c,args);
                    if(method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        Queue<Long> target=sql.startsWith("SELECT active_count") ? lockSql : sql.startsWith("INSERT INTO comparison_scope") ? insertSql : null;
                        if(target!=null) {
                            PreparedStatement statement=(PreparedStatement)result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)-> {
                                if(m.getName().startsWith("execute")) {
                                    long t=System.nanoTime();try{return invoke(m,statement,a);}
                                    finally {if(recording)target.add(System.nanoTime()-t);}
                                }
                                return invoke(m,statement,a);
                            });
                        }
                    }
                    return result;
                });
            }
        };
    }
    static Object invoke(Method m,Object receiver,Object[] args) throws Throwable {
        try{return m.invoke(receiver,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    String run(int repeat,Scenario s,int seconds,JdbcReservationStore store,JdbcTemplate setup,JdbcTemplate observer,HikariDataSource load) throws Exception {
        recording=false;response.clear();lockSql.clear();insertSql.clear();poolWait.clear();
        // Dedicated lock_metrics DB only; never connects to existing comparison or app DB.
        setup.execute("TRUNCATE comparison_hold, comparison_queue, comparison_token, comparison_scope");
        long concert=10000+repeat;
        for(int i=0;i<(s.spread?s.clients:1);i++) setup.update("INSERT INTO comparison_scope(concert_id,schedule_id) VALUES (?,?)",concert,i+1);
        AtomicLong sequence=new AtomicLong();LongAdder held=new LongAdder(),conflicts=new LongAdder(),limits=new LongAdder(),errors=new LongAdder();
        Queue<Throwable> failures=new ConcurrentLinkedQueue<>();
        CountDownLatch ready=new CountDownLatch(s.clients),start=new CountDownLatch(1);
        AtomicLong deadline=new AtomicLong();AtomicBoolean monitorRunning=new AtomicBoolean(true);
        List<Observation> observations=Collections.synchronizedList(new ArrayList<>());
        List<Cpu> cpu=Collections.synchronizedList(new ArrayList<>());
        ExecutorService workers=Executors.newFixedThreadPool(s.clients);
        ExecutorService monitors=Executors.newFixedThreadPool(2);
        List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<s.clients;i++) {int client=i; jobs.add(workers.submit(()-> {
            ready.countDown();start.await();
            while(System.nanoTime()<deadline.get()) {
                long id=sequence.incrementAndGet();long t=System.nanoTime();
                try {
                    var result=store.hold(concert,s.spread?client+1:1,s.sameSeat?1:id,"user-"+id);
                    switch(result){case HELD->held.increment();case ALREADY_HELD->conflicts.increment();case LIMIT_EXCEEDED->limits.increment();}
                } catch(Throwable e){errors.increment();failures.add(e);}
                finally {response.add(System.nanoTime()-t);}
            }
            return null;
        }));}
        assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
        cpu.add(cpu());
        long began=System.nanoTime();deadline.set(began+TimeUnit.SECONDS.toNanos(seconds));recording=true;
        Future<?> dbMonitor=monitors.submit(()-> {
            try {
                while(monitorRunning.get()) {
                    Map<String,Object> row=observer.queryForMap("""
                      SELECT count(*) FILTER (WHERE state='active') AS active,
                        count(*) FILTER (WHERE wait_event_type='Lock') AS waiting,
                        COALESCE(sum(cardinality(pg_blocking_pids(pid))) FILTER (WHERE wait_event_type='Lock'),0) AS edges
                      FROM pg_stat_activity WHERE application_name='seat-lock-load'
                      """);
                    observations.add(new Observation(System.nanoTime(),((Number)row.get("active")).intValue(),
                            ((Number)row.get("waiting")).intValue(),((Number)row.get("edges")).intValue(),load.getHikariPoolMXBean().getThreadsAwaitingConnection()));
                    Thread.sleep(20);
                }
            }catch(Exception e){throw new RuntimeException(e);}
        });
        Future<?> cpuMonitor=monitors.submit(()-> {
            try{while(monitorRunning.get()){Thread.sleep(1000);if(monitorRunning.get())cpu.add(cpu());}}
            catch(Exception e){throw new RuntimeException(e);}
        });
        start.countDown();
        try {
            for(Future<?> job:jobs)job.get(seconds+35,TimeUnit.SECONDS);
        } finally {
            monitorRunning.set(false);recording=false;workers.shutdownNow();
            assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        }
        long ended=System.nanoTime();
        // Sample before joining sleeping monitors: do not include up to 1s of idle tail in CPU average.
        cpu.add(cpu());
        dbMonitor.get(10,TimeUnit.SECONDS);cpuMonitor.get(10,TimeUnit.SECONDS);
        monitors.shutdown();assertThat(monitors.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        String name=String.format("run-%02d-%s-%02d",repeat,s.name,s.clients);
        // Independent observations, not request-correlated pairs. Preserve distributions for audit.
        try(var writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(OUT.resolve(name+"-timings.csv.gz"))),
                java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write("metric,elapsed_ms\n");
            writeTimings(writer,"response",response);writeTimings(writer,"lock_select",lockSql);
            writeTimings(writer,"scope_insert",insertSql);writeTimings(writer,"pool_acquire",poolWait);
        }
        List<String> waitLines=new ArrayList<>(List.of("elapsed_ms,active,lock_waiters,blocker_edges,pool_pending"));
        for(Observation o:observations)waitLines.add(String.format(Locale.ROOT,"%.3f,%d,%d,%d,%d",(o.time-began)/1e6,o.active,o.waiting,o.blockerEdges,o.poolPending));
        Files.write(OUT.resolve(name+"-wait.csv"),waitLines);
        List<String> cpuLines=new ArrayList<>(List.of("elapsed_ms,usage_usec,throttled_usec"));
        for(Cpu c:cpu)cpuLines.add(String.format(Locale.ROOT,"%.3f,%d,%d",(c.time-began)/1e6,c.usage,c.throttled));
        Files.write(OUT.resolve(name+"-cpu.csv"),cpuLines);
        assertThat(failures).as("SQL/timeouts must not be counted as legitimate conflicts").isEmpty();
        assertThat(limits.sum()).isZero();
        long count=setup.queryForObject("SELECT count(*) FROM comparison_hold",Long.class);
        assertThat(count).isEqualTo(held.sum());
        if(s.sameSeat){assertThat(held.sum()).isEqualTo(1);assertThat(store.owner(concert,1,1)).isNotNull();}
        else {assertThat(held.sum()).isEqualTo(response.size());assertThat(conflicts.sum()).isZero();}
        assertThat(lockSql).hasSize(response.size());assertThat(poolWait).hasSize(response.size());
        assertThat(observations).isNotEmpty();
        long activeSum=observations.stream().mapToLong(Observation::active).sum();
        long waitingSum=observations.stream().mapToLong(Observation::waiting).sum();
        Cpu first=cpu.getFirst(),last=cpu.getLast();
        double corePct=100.0*(last.usage-first.usage)/((last.time-first.time)/1000.0);
        double peak=0;for(int i=1;i<cpu.size();i++){Cpu a=cpu.get(i-1),b=cpu.get(i);peak=Math.max(peak,100.0*(b.usage-a.usage)/((b.time-a.time)/1000.0));}
        return String.format(Locale.ROOT,"%d,%s,%d,%.3f,%d,%d,%d,%d,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.2f,%.3f,%.2f,%.2f,%.2f,%.3f,%d",repeat,s.name,s.clients,(ended-began)/1e9,response.size(),held.sum(),conflicts.sum(),limits.sum(),errors.sum(),response.size()*1e9/(ended-began),mean(response),pct(response,.95),pct(response,.99),mean(lockSql),pct(lockSql,.95),mean(insertSql),mean(poolWait),waitingSum/(double)observations.size(),observations.stream().mapToInt(Observation::waiting).max().orElse(0),activeSum==0?0:100.0*waitingSum/activeSum,observations.stream().mapToInt(Observation::blockerEdges).average().orElse(0),corePct,corePct/2,peak,(last.throttled-first.throttled)/1000.0,observations.size());
    }
    Cpu cpu() throws Exception {
        ProcessBuilder builder=new ProcessBuilder("docker","--host=unix:///var/run/docker.sock","exec",System.getenv("RDB_METRICS_CONTAINER"),"cat","/sys/fs/cgroup/cpu.stat");
        for(String key:List.of("DOCKER_HOST","DOCKER_CONTEXT","DOCKER_TLS","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH"))builder.environment().remove(key);
        Process p=builder.start();String value=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        Map<String,Long> fields=new HashMap<>();for(String line:value.split("\n")){String[] pair=line.split(" ");fields.put(pair[0],Long.parseLong(pair[1]));}
        return new Cpu(System.nanoTime(),fields.get("usage_usec"),fields.getOrDefault("throttled_usec",0L));
    }
    static double mean(Collection<Long> values){return values.stream().mapToLong(Long::longValue).average().orElse(0)/1e6;}
    static double pct(Collection<Long> values,double fraction){var sorted=values.stream().sorted().toList();return sorted.get((int)Math.ceil(sorted.size()*fraction)-1)/1e6;}
    static void writeTimings(java.io.Writer writer,String metric,Collection<Long> values) throws java.io.IOException {
        for(long value:values)writer.write(metric+","+String.format(Locale.ROOT,"%.6f",value/1e6)+"\n");
    }
}
