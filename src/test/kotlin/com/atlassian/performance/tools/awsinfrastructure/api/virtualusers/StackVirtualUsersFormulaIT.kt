package com.atlassian.performance.tools.awsinfrastructure.api.virtualusers

import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.SshKeyFormula
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.awsinfrastructure.IntegrationTestRuntime.taskWorkspace
import com.atlassian.performance.tools.awsinfrastructure.virtualusers.S3ResultsTransport
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.io.File
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val shadowJarName = "hello-world.jar"
private const val shadowJar = "/com/atlassian/performance/tools/awsinfrastructure/api/virtualusers/$shadowJarName"

class StackVirtualUsersFormulaIT {

    private val workspace = taskWorkspace.isolateTest(javaClass.simpleName)

    @Test
    fun shouldProvisionVirtualUsers() {
        val nonce = UUID.randomUUID().toString()
        val lifespan = Duration.ofMinutes(30)
        val keyFormula = SshKeyFormula(
            ec2 = aws.ec2,
            workingDirectory = workspace.directory,
            lifespan = lifespan,
            prefix = nonce
        )
        val virtualUsersFormula = StackVirtualUsersFormula.Builder(
            shadowJar = File(this.javaClass.getResource(shadowJar).toURI())
        ).build()

        val provisionedVirtualUsers = virtualUsersFormula.provision(
            investment = Investment(
                useCase = "Test VU provisioning",
                lifespan = lifespan
            ),
            shadowJarTransport = aws.virtualUsersStorage(nonce),
            resultsTransport = S3ResultsTransport(
                results = aws.resultsStorage(nonce)
            ),
            key = CompletableFuture.completedFuture(keyFormula.provision()),
            roleProfile = aws.shortTermStorageAccess(),
            aws = aws
        )

        val listFiles = provisionedVirtualUsers.virtualUsers.ssh.newConnection().use { it.execute("ls") }
        val files = listFiles.output
        assertThat(files, containsString(shadowJarName))
        val executeHelloWorld = provisionedVirtualUsers.virtualUsers.ssh.newConnection()
            .use { it.execute(" java -classpath $shadowJarName samples.HelloWorld") }
        val helloWorld = executeHelloWorld.output
        assertThat(helloWorld, equalTo("Hello, world!\n"))
        provisionedVirtualUsers.resource.release().get(1, TimeUnit.MINUTES)
    }
}