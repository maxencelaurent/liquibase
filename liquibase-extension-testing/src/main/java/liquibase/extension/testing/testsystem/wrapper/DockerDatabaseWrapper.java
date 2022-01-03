package liquibase.extension.testing.testsystem.wrapper;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.*;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.extension.testing.testsystem.TestSystem;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.util.ISO8601Utils;

import java.sql.Connection;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.*;
import java.util.function.Consumer;

public class DockerDatabaseWrapper extends DatabaseWrapper {

    private final JdbcDatabaseContainer container;
    private final TestSystem testSystem;

    private static Set<String> alreadyRunningContainerIds = new HashSet<>();
    private Connection connection;

    public DockerDatabaseWrapper(JdbcDatabaseContainer container, TestSystem testSystem) {
        this.container = container;
        this.testSystem = testSystem;
        int[] ports = testSystem.getTestSystemProperty("ports", value -> {
            if (value == null) {
                return null;
            }
            final String[] portStrings = String.valueOf(value).split("\\s*,\\s*");

            int[] returnValue = new int[portStrings.length];
            for (int i = 0; i < portStrings.length; i++) {
                returnValue[i] = Integer.parseInt(portStrings[i]);
            }

            return returnValue;
        }, false);

        if (ports != null) {
            List<PortBinding> portBindings = new ArrayList<>();
            for (int port : ports) {
                portBindings.add(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(port)));
            }

            container.withCreateContainerCmdModifier((Consumer<CreateContainerCmd>) cmd ->
                    cmd.withHostConfig(new HostConfig().withPortBindings(portBindings))
            );
        }
    }

    @Override
    public void start(boolean keepRunning) throws Exception {
        if (container.isRunning()) {
            return;
        }

        final DockerClient dockerClient = container.getDockerClient();
        Container runningContainer = null;
        for (Container container : dockerClient.listContainersCmd().exec()) {
            final String containerTestSystem = container.getLabels().get("org.liquibase.testsystem");
            if (containerTestSystem != null && containerTestSystem.equals(testSystem.getDefinition())) {
                runningContainer = container;
                break;
            }
        }

        if (runningContainer == null) {
            container.withReuse(keepRunning);
        } else {
            container.withReuse(true);
        }

        container.withLabel("org.liquibase.testsystem", testSystem.getDefinition());
        container.start();

//        if (newlyStarted()) { //it just started
//            newlyStarted.add(container.getContainerId());
//            final String initScript = getProperty("init.script", String.class);
//            if (initScript != null) {
//                final Connection connection;
//                final String initUsername = getProperty("init.username", String.class);
//
//                if (initUsername == null) {
//                    connection = openConnection();
//                } else {
//                    connection = openConnection(initUsername, getProperty("init.password", String.class));
//                }
//
//                final Statement statement = connection.createStatement();
//                for (String sql : StringUtil.splitSQL(initScript, ";")) {
//                    statement.execute(sql);
//                }
//            }
//        }
    }

    @Override
    public String getUrl() {
        return container.getJdbcUrl();
    }

    public JdbcDatabaseContainer getContainer() {
        return container;
    };

    @Override
    public String getUsername() {
        return container.getUsername();
    }

    private boolean newlyStarted() {
        if (!alreadyRunningContainerIds.add(container.getContainerId())) {
            return false;
        }

        try {
            final Date started = ISO8601Utils.parse(container.getCurrentContainerInfo().getCreated(), new ParsePosition(0));

            return new Date().getTime() - started.getTime() < 60 * 1000;
        } catch (ParseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }
}