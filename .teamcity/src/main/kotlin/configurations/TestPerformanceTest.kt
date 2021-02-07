/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package configurations

import common.Os
import common.applyPerformanceTestSettings
import common.buildToolGradleParameters
import common.checkCleanM2
import common.gradleWrapper
import common.individualPerformanceTestArtifactRules
import jetbrains.buildServer.configs.kotlin.v2019_2.AbsoluteId
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildStep
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildSteps
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
import model.CIBuildModel
import model.Stage

class TestPerformanceTest(model: CIBuildModel, stage: Stage) : BaseGradleBuildType(model, stage, init = {
    val os = Os.LINUX
    val testProject = "smallJavaMultiProject"

    fun BuildSteps.gradleStep(tasks: List<String>) {
        gradleWrapper {
            name = "GRADLE_RUNNER"
            gradleParams = (
                tasks +
                    buildToolGradleParameters(isContinue = false)
                ).joinToString(separator = " ")
        }
    }

    fun BuildSteps.adHocPerformanceTest(tests: List<String>) {
        gradleStep(listOf(
            "-PperformanceBaselines=force-defaults",
            "clean",
            "performance:${testProject}PerformanceAdHocTest",
            tests.map { """--tests "$it"""" }.joinToString(" "),
            """--warmups 2 --runs 2 --checks none""",
            """"-PtestJavaHome=${os.individualPerformanceTestJavaHome()}""""
        ))
    }

    id = AbsoluteId("${model.projectId}_TestPerformanceTest")
    name = "Test performance test tasks - Java8 Linux"
    description = "Tries to run an adhoc performance test without a database connection to verify this is still working"

    applyPerformanceTestSettings()
    artifactRules = individualPerformanceTestArtifactRules

    steps {
        script {
            name = "KILL_GRADLE_PROCESSES"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = os.killAllGradleProcesses
        }
        adHocPerformanceTest(listOf(
            "org.gradle.performance.regression.java.JavaIDEModelPerformanceTest.get IDE model for IDEA",
            "org.gradle.performance.regression.java.JavaUpToDatePerformanceTest.up-to-date assemble (parallel true)",
            "org.gradle.performance.regression.corefeature.TaskAvoidancePerformanceTest.help with lazy and eager tasks"
        ))

        checkCleanM2(os)
    }

    applyDefaultDependencies(model, this, true)
})