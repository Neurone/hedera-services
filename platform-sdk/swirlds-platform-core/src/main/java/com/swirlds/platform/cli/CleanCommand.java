/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.swirlds.platform.cli;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.config.EventConfig;
import com.swirlds.common.config.StateConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.DefaultConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine;

@CommandLine.Command(
        name = "clean",
        mixinStandardHelpOptions = true,
        description = "Delete all files generated by running the platform.")
@SubcommandOf(PlatformCli.class)
public final class CleanCommand extends AbstractCommand {
    /** The path to the sdk directory */
    private Path sdkPath;

    /** Set the path to the sdk directory */
    @SuppressWarnings("unused") // used by picocli
    @CommandLine.Parameters(description = "the path to the sdk directory")
    private void setSdkPath(final Path sdkPath) {
        this.sdkPath = dirMustExist(sdkPath.toAbsolutePath());
    }

    @Override
    public Integer call() throws Exception {
        clean(sdkPath);
        return 0;
    }

    /**
     * Delete all files generated by running the platform.
     *
     * @param sdkPath
     * 		the path to the sdk directory
     * @throws IOException
     * 		if an IO error occurs
     */
    public static void clean(@NonNull final Path sdkPath) throws IOException {
        Objects.requireNonNull(sdkPath);

        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(
                List.of(sdkPath.resolve("config.txt"), sdkPath.resolve("settings.txt")));

        // delete all logs
        FileUtils.deleteFiles(sdkPath, ".log");
        // metrics
        FileUtils.deleteFiles(sdkPath, ".csv");
        // metricsDoc is written to user.dir, deleteFiles() will look for it in sdkPath
        FileUtils.deleteFiles(sdkPath, "metricsDoc.tsv");
        // settings used
        FileUtils.deleteFiles(sdkPath, "settingsUsed.txt");
        // address books
        FileUtils.deleteDirectory(sdkPath.resolve(
                configuration.getConfigData(AddressBookConfig.class).addressBookDirectory()));
        // saved states, PCES & recycle bin
        // (the latter two are saved in the saved state directory, so deleting the saved state directory will delete
        // them)
        FileUtils.deleteDirectory(
                sdkPath.resolve(configuration.getConfigData(StateConfig.class).savedStateDirectory()));
        // event streams
        FileUtils.deleteDirectory(
                sdkPath.resolve(configuration.getConfigData(EventConfig.class).eventsLogDir()));
    }
}