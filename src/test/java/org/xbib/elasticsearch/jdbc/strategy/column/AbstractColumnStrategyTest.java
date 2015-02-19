package org.xbib.elasticsearch.jdbc.strategy.column;

import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.indices.IndexMissingException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.xbib.elasticsearch.action.jdbc.state.get.GetStateAction;
import org.xbib.elasticsearch.action.jdbc.state.get.GetStateRequest;
import org.xbib.elasticsearch.action.jdbc.state.get.GetStateResponse;
import org.xbib.elasticsearch.jdbc.state.State;
import org.xbib.elasticsearch.jdbc.support.AbstractNodeTestHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

public abstract class AbstractColumnStrategyTest extends AbstractNodeTestHelper {

    private final static int SECONDS_TO_WAIT = 15;

    protected static ColumnSource source;

    protected static ColumnContext context;

    public abstract ColumnSource newSource();

    public abstract ColumnContext newContext();

    @BeforeMethod
    @Parameters({"starturl", "user", "password", "create"})
    public void beforeMethod(String starturl, String user, String password, @Optional String resourceName)
            throws Exception {
        startNodes();

        logger.info("nodes started");

        waitForYellow("1");
        source = newSource();
        source.setUrl(starturl)
                .setUser(user)
                .setPassword(password)
                .setLocale(Locale.getDefault())
                .setTimeZone(TimeZone.getDefault());
        context = newContext();
        context.setSource(source);
        source.setContext(context);
        logger.info("create table {}", resourceName);
        if (resourceName == null || "".equals(resourceName)) {
            return;
        }
        Connection connection = source.getConnectionForWriting();
        if (connection == null) {
            throw new IOException("no connection");
        }
        sqlScript(connection, resourceName);
        source.closeWriting();
    }

    @AfterMethod
    @Parameters({"stopurl", "user", "password", "delete"})
    public void afterMethod(String stopurl, String user, String password, @Optional String resourceName)
            throws Exception {

        logger.info("remove table {}", resourceName);
        if (resourceName == null || "".equals(resourceName)) {
            return;
        }
        // before dropping tables, open read connection must be closed to avoid hangs in mysql/postgresql
        logger.debug("closing reads...");
        source.closeReading();

        logger.debug("connecting for close...");
        Connection connection = source.getConnectionForWriting();
        if (connection == null) {
            throw new IOException("no connection");
        }
        logger.debug("cleaning...");
        // clean up tables
        sqlScript(connection, resourceName);
        logger.debug("closing writes...");
        source.closeWriting();

        // some driver can drop database by a magic 'stop' URL
        source = newSource();
        source.setUrl(stopurl)
                .setUser(user)
                .setPassword(password)
                .setLocale(Locale.getDefault())
                .setTimeZone(TimeZone.getDefault());
        try {
            logger.info("connecting to stop URL...");
            // activate stop URL
            source.getConnectionForWriting();
        } catch (Exception e) {
            // exception is expected, ignore
        }
        // close open write connection
        source.closeWriting();
        logger.info("stopped");

        // delete test index
        try {
            client("1").admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
            logger.info("index {} deleted", index);
        } catch (IndexMissingException e) {
            logger.warn(e.getMessage());
        }
        stopNodes();
    }

    protected void perform(String resource) throws Exception {
        create(resource);
        waitFor();
        waitForActive();
        waitForInactive();
    }

    protected void create(String resource) throws Exception {
        waitForYellow("1");
        byte[] b = Streams.copyToByteArray(getClass().getResourceAsStream(resource));
        Map<String, Object> map = XContentHelper.convertToMap(b, false).v2();
        XContentBuilder builder = jsonBuilder().map(map);
        logger.info("task = {}", builder.string());
        /*IndexRequest indexRequest = Requests.indexRequest("_river").type("my_jdbc_river").id("_meta")
                .source(builder.string());
        client("1").index(indexRequest).actionGet();
        client("1").admin().indices().prepareRefresh("_river").execute().actionGet();*/
        logger.info("task is created");
    }

    public void waitFor() throws Exception {
        waitFor(client("1"), "my_task", SECONDS_TO_WAIT);
        logger.info("task is up");
    }

    public void waitForActive() throws Exception {
        waitForActive(client("1"), "my_task", SECONDS_TO_WAIT);
        logger.info("task is active");
    }

    public void waitForInactive() throws Exception {
        waitForInactive(client("1"), "my_task", SECONDS_TO_WAIT);
        logger.info("task is inactive");
    }

    protected Map<String,Object> taskSettings(String resource)
            throws IOException {
        InputStream in = getClass().getResourceAsStream(resource);
        return XContentHelper.convertToMap(Streams.copyToByteArray(in), false).v2();
    }

    private void sqlScript(Connection connection, String resourceName) throws Exception {
        InputStream in = getClass().getResourceAsStream(resourceName);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String sql;
        while ((sql = br.readLine()) != null) {

            try {
                logger.trace("executing {}", sql);
                Statement p = connection.createStatement();
                p.execute(sql);
                p.close();
            } catch (SQLException e) {
                // ignore
                logger.error(sql + " failed. Reason: " + e.getMessage());
            } finally {
                connection.commit();
            }
        }
        br.close();
    }

    public static State waitFor(Client client, String name, int seconds)
            throws InterruptedException, IOException {
        GetStateRequest stateRequest = new GetStateRequest()
                .setName(name);
        GetStateResponse stateResponse = client.admin().cluster()
                .execute(GetStateAction.INSTANCE, stateRequest).actionGet();
        logger.info("waitFor {}", name);
        while (seconds-- > 0 && stateResponse.exists(name)) {
            Thread.sleep(1000L);
            try {
                stateResponse = client.admin().cluster().execute(GetStateAction.INSTANCE, stateRequest).actionGet();
                logger.info("waitFor state={}", stateResponse.getState());
            } catch (IndexMissingException e) {
                logger.warn("index missing");
            }
        }
        if (seconds < 0) {
            throw new IOException("timeout waiting for task");
        }
        return stateResponse.getState();
    }

    public static State waitForActive(Client client, String name, int seconds) throws InterruptedException, IOException {
        long now = System.currentTimeMillis();
        GetStateRequest stateRequest = new GetStateRequest()
                .setName(name);
        GetStateResponse stateResponse = client.admin().cluster()
                .execute(GetStateAction.INSTANCE, stateRequest).actionGet();
        State state = stateResponse.getState();
        long t0 = state != null ? state.getLastActiveBegin().getMillis() : 0L;
        logger.info("waitForActive: now={} t0={} t0<now={} state={}",
                now, t0, t0 < now, state);
        while (seconds-- > 0 && t0 == 0 && t0 < now) {
            Thread.sleep(1000L);
            try {
                stateResponse = client.admin().cluster().execute(GetStateAction.INSTANCE, stateRequest).actionGet();
                state = stateResponse.getState();
                t0 = state != null ? state.getLastActiveBegin().getMillis() : 0L;
            } catch (IndexMissingException e) {
                //
            }
            logger.info("waitForActive: now={} t0={} t0<now={} state={}",
                    now, t0, t0 < now, state);
        }
        if (seconds < 0) {
            throw new IOException("timeout waiting for active task");
        }
        return state;
    }

    public static State waitForInactive(Client client, String name, int seconds) throws InterruptedException, IOException {
        long now = System.currentTimeMillis();
        GetStateRequest stateRequest = new GetStateRequest()
                .setName(name);
        GetStateResponse stateResponse = client.admin().cluster()
                .execute(GetStateAction.INSTANCE, stateRequest).actionGet();
        State state = stateResponse.getState();
        long t0 = state != null ? state.getLastActiveBegin().getMillis() : 0L;
        long t1 = state != null ? state.getLastActiveEnd().getMillis() : 0L;
        logger.info("waitForInactive: now={} t0<now={} t1-t0<=0={} state={}",
                now, t0 < now, t1 - t0 <= 0L, state);
        while (seconds-- > 0 && t0 < now && t1 - t0 <= 0L) {
            Thread.sleep(1000L);
            try {
                stateResponse = client.admin().cluster().execute(GetStateAction.INSTANCE, stateRequest).actionGet();
                state = stateResponse.getState();
                t0 = state != null ? state.getLastActiveBegin().getMillis() : 0L;
                t1 = state != null ? state.getLastActiveEnd().getMillis() : 0L;
            } catch (IndexMissingException e) {
                //
            }
            logger.info("waitForInactive: now={} t0<now={} t1-t0<=0={} state={}",
                    now, t0 < now, t1 - t0 <= 0L, state);
        }
        if (seconds < 0) {
            throw new IOException("timeout waiting for inactive task");
        }
        return state;
    }
}
