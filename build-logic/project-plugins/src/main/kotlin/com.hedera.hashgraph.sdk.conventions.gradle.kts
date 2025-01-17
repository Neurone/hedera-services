/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

plugins {
    id("java-library")
    id("com.hedera.hashgraph.java")
    id("com.gorylenko.gradle-git-properties")
}

group = "com.swirlds"

javaModuleDependencies { versionsFromConsistentResolution(":swirlds-platform-core") }

configurations.getByName("mainRuntimeClasspath") {
    extendsFrom(configurations.getByName("internal"))
}

gitProperties { keys = listOf("git.build.version", "git.commit.id", "git.commit.id.abbrev") }

// !!! Remove the following once 'test' tasks are allowed to run in parallel ===
val allProjects =
    rootProject.subprojects
        .map { it.name }
        .filter {
            it !in
                listOf(
                    "swirlds",
                    "swirlds-benchmarks",
                    "swirlds-sign-tool"
                ) // these are application/benchmark projects
        }
        .sorted()
val myIndex = allProjects.indexOf(name)

if (myIndex > 0) {
    val predecessorProject = allProjects[myIndex - 1]
    tasks.test {
        mustRunAfter(":$predecessorProject:test")
        mustRunAfter(":$predecessorProject:hammerTest")
    }
    tasks.named("hammerTest") {
        mustRunAfter(tasks.test)
        mustRunAfter(":$predecessorProject:hammerTest")
    }
}
