package plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.jenkinsci.test.acceptance.Matchers.hasContent;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URISyntaxException;
import java.net.URL;

import org.codehaus.plexus.util.FileUtils;
import org.jenkinsci.test.acceptance.docker.Docker;
import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.ForemanContainer;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.credentials.CredentialsPage;
import org.jenkinsci.test.acceptance.plugins.foreman_node_sharing.ForemanSharedNodeCloudPageArea;
import org.jenkinsci.test.acceptance.plugins.ssh_credentials.SshPrivateKeyCredential;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.Jenkins;
import org.jenkinsci.test.acceptance.po.JenkinsConfig;
import org.jenkinsci.test.acceptance.po.LabelExpressionAxis;
import org.jenkinsci.test.acceptance.po.MatrixBuild;
import org.jenkinsci.test.acceptance.po.MatrixProject;
import org.jenkinsci.test.acceptance.po.MatrixRun;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Inject;

/**
 * Acceptance Test Harness Test for Foreman Node Sharing Plugin.
 *
 */
@WithPlugins("foreman-node-sharing")
@WithDocker
public class ForemanNodeSharingPluginTest extends AbstractJUnitTest {
    @Inject private DockerContainerHolder<ForemanContainer> dockerForeman;
    @Inject private DockerContainerHolder<JavaContainer> docker1;

    private ForemanContainer foreman = null;
    private JavaContainer sshslave1 = null;
    private ForemanSharedNodeCloudPageArea cloud = null;
    private String labelExpression1 = "label1 aix";
    private String labelExpression2 = "label2";
    private String jobLabelExpression1 = "label1 && aix";
    private String jobLabelExpression2 = labelExpression2;

    private static final int FOREMAN_CLOUD_INIT_WAIT = 180;
    private static final int PROVISION_TIMEOUT = 240;
    private static final String DEFAULTJOBSLEEPTIME = "15";

    /**
     * Setup instance before each test.
     * @throws Exception if occurs.
     */
    @Before public void setUp() throws Exception {
        foreman = dockerForeman.get();
        sshslave1 = docker1.get();

        CredentialsPage c = new CredentialsPage(jenkins, "_");
        c.open();

        final SshPrivateKeyCredential sc = c.add(SshPrivateKeyCredential.class);
        sc.username.set("test");
        sc.selectEnterDirectly().privateKey.set(sshslave1.getPrivateKeyString());
        c.create();

        //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Mock object.
        elasticSleep(10000);

        if (populateForeman(foreman.getUrl().toString()+"/api/v2", sshslave1.getCid(),
                sshslave1.getIpAddress(), labelExpression1, "1") != 0) {
            throw new Exception("failed to populate foreman");
        }
        if (populateForeman(foreman.getUrl().toString()+"/api/v2", "dummy",
                "9.9.9.9", labelExpression2, "2") != 0) {
            throw new Exception("failed to populate foreman");
        }

        jenkins.configure();
        cloud = addCloud(jenkins.getConfigPage());
        //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Mock object.
        elasticSleep(10000);

    }

    /**
     * Test loss of connection to Foreman
     * - 2 jobs are scheduled to be built for the
     * same label.
     * - First one starts, and we stop the Foreman instance
     * - Once it finishes, we restart the container after a
     *    small sleep to ensure that the release() has failed
     *    and that we are going to dispose() the resource eventually
     * - We wait until Second job has completed successfully.
     * - If the disposer is not working,
     *    the last build will never complete.
     */
    @Test
    public void testLoseForemanConnection() throws Exception {
        jenkins.save();
        FreeStyleJob job1 = createAndConfigureJob(jobLabelExpression1);
        FreeStyleJob job2 = createAndConfigureJob(jobLabelExpression1);

        Build b1 = job1.scheduleBuild();
        Build b2 = job2.scheduleBuild();
        b1.waitUntilStarted();

        Docker.cmd("stop").add(foreman.getCid())
                .popen().verifyOrDieWith("Failed to stop " + foreman.getCid());

        b1.waitUntilFinished(PROVISION_TIMEOUT);
        elasticSleep(10000);

        Docker.cmd("start").add(foreman.getCid())
                .popen().verifyOrDieWith("Failed to start " + foreman.getCid());
        elasticSleep(10000);
        b2.waitUntilFinished(PROVISION_TIMEOUT).shouldSucceed();
    }

    /**
     * Test a matrix job
     */
    @Test
    @WithPlugins("matrix-project")
    public void testMatrixJob() {
        jenkins.save();
        MatrixProject job = jenkins.jobs.create(MatrixProject.class);
        job.configure();
        LabelExpressionAxis a = job.addAxis(LabelExpressionAxis.class);
        a.values.set("label1");

        job.addUserAxis("X", "1 2 3 4 5");
        job.save();

        MatrixBuild b = job.startBuild().waitUntilFinished(240).as(MatrixBuild.class);
        for (MatrixRun config: b.getConfigurations()) {
        	config.shouldSucceed();
        }

    }

    /**
     * Test the connection and check version.
     * @throws IOException if occurs.
     */
    @Test
    public void testConnection() throws IOException {
        System.out.println(foreman.getIpAddress());
        cloud.testConnection();
        waitFor(driver, hasContent("Foreman version is"), FOREMAN_CLOUD_INIT_WAIT);
    }

    /**
     * Verify that compatible host checker works.
     * @throws IOException if occurs.
     */
    @Test
    public void testCheckForCompatible() throws IOException {
        cloud.checkForCompatibleHosts();
        waitFor(driver, hasContent(sshslave1.getCid()), FOREMAN_CLOUD_INIT_WAIT);
    }

    /**
     * Test that we can provision, build and release.
     * @throws Exception if occurs.
     */
    @Test
    public void testProvision() throws Exception {
        jenkins.save();

        FreeStyleJob job1 = createAndConfigureJob(jobLabelExpression1);
        FreeStyleJob job2 = createAndConfigureJob(jobLabelExpression2);

        Build b1 = job1.scheduleBuild();
        job2.scheduleBuild();
        b1.waitUntilFinished(PROVISION_TIMEOUT);

    }

    /**
     * Test that we can provision after a restart, build and release.
     * @throws Exception if occurs.
     */
    @Test
    public void testProvisionAfterRestart() throws Exception {
        jenkins.save();

        FreeStyleJob job1 = createAndConfigureJob(jobLabelExpression1);
        FreeStyleJob job2 = createAndConfigureJob(jobLabelExpression2);

        Build b = job1.scheduleBuild();
        job2.scheduleBuild();
        jenkins.restart();
        b.waitUntilFinished(PROVISION_TIMEOUT);

    }

    /**
     * Create and configure Test job with sleep time
     * @return FreeStyleJob.
     */
    private FreeStyleJob createAndConfigureJob(String label, String sleepTime) {
        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class);
        job.setLabelExpression(label);
        job.addShellStep("sleep " + sleepTime);
        job.save();
        return job;
    }

    /**
     * Create and configure Test job.
     * @return FreeStyleJob.
     */
    private FreeStyleJob createAndConfigureJob(String label) {
        return createAndConfigureJob(label, DEFAULTJOBSLEEPTIME);
    }
    /**
     * Populate Foreman using hammer script.
     * @param server Foreman server url.
     * @param hostToCreate host name for creation.
     * @param labels list of labels to add to host.
     * @param hostID id of host.
     * @return exit code of script execution.
     * @throws URISyntaxException if occurs.
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    private int populateForeman(String server, String hostToCreate, String ipAddress,
                                String labels, String hostID) throws
        URISyntaxException, IOException, InterruptedException {

        URL script =
                ForemanNodeSharingPluginTest.class.getClassLoader()
                .getResource("foreman_node_sharing_plugin/setup.sh");
        File tempScriptFile = File.createTempFile("setup", ".sh");
        tempScriptFile.setExecutable(true);
        FileUtils.copyURLToFile(script, tempScriptFile);

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", tempScriptFile.getAbsolutePath(),
                server,
                hostToCreate,
                ipAddress,
                labels,
                hostID);

        pb.directory(tempScriptFile.getParentFile());
        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        pb.redirectInput(Redirect.INHERIT);
        Process p = pb.start();
        return p.waitFor();
    }

    /**
     * Add cloud to Jenkins Config.
     * @param config Jenkins Configuration Page.
     * @return a ForemanCloudPageArea.
     * @throws IOException if occurs.
     */
    private ForemanSharedNodeCloudPageArea addCloud(JenkinsConfig config) throws IOException {
        return config.addCloud(ForemanSharedNodeCloudPageArea.class)
                .name(Jenkins.createRandomName())
                .url(foreman.getUrl().toString() + "/api/")
                .user("admin")
                .password("changeme")
                .setCredentials("test");
    }

}
