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

package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.node.app.service.token.impl.validators.TokenAttributesValidator.IMMUTABILITY_SENTINEL_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.CryptoCreateWithAliasConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.test.framework.config.TestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoCreateValidatorTest {
    private CryptoCreateValidator subject;
    private TokensConfig tokensConfig;
    private LedgerConfig ledgerConfig;
    private EntitiesConfig entitiesConfig;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private ReadableAccountStore accountStore;

    private Configuration configuration;

    private TestConfigBuilder testConfigBuilder;

    @BeforeEach
    void setUp() {
        subject = new CryptoCreateValidator();
        testConfigBuilder = HederaTestConfigBuilder.create()
                .withValue("cryptoCreateWithAlias.enabled", true)
                .withValue("ledger.maxAutoAssociations", 5000)
                .withValue("entities.limitTokenAssociations", false)
                .withValue("tokens.maxPerAccount", 1000);
    }

    @Test
    void permitsHollowAccountCreationWithSentinelKey() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(IMMUTABILITY_SENTINEL_KEY)
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        final var aliasConfig = configuration.getConfigData(CryptoCreateWithAliasConfig.class);

        subject = new CryptoCreateValidator();

        assertDoesNotThrow(() -> subject.validateKeyAliasAndEvmAddressCombinations(
                typicalHollowAccountCreation, attributeValidator, aliasConfig, accountStore, true));
    }

    @Test
    void doesNotPermitHollowAccountCreationWithNonSentinelEmptyKey() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(Key.newBuilder().keyList(KeyList.newBuilder().keys(IMMUTABILITY_SENTINEL_KEY)))
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        final var aliasConfig = configuration.getConfigData(CryptoCreateWithAliasConfig.class);

        subject = new CryptoCreateValidator();

        assertThrows(
                HandleException.class,
                () -> subject.validateKeyAliasAndEvmAddressCombinations(
                        typicalHollowAccountCreation, attributeValidator, aliasConfig, accountStore, true));
    }

    @Test
    void doesNotPermitSentinelEmptyKeyIfNotHollowCreation() {
        final var typicalHollowAccountCreation = CryptoCreateTransactionBody.newBuilder()
                .alias(Bytes.wrap(CommonUtils.unhex("abababababababababababababababababababab")))
                .key(IMMUTABILITY_SENTINEL_KEY)
                .build();
        configuration = testConfigBuilder.getOrCreateConfig();
        final var aliasConfig = configuration.getConfigData(CryptoCreateWithAliasConfig.class);

        subject = new CryptoCreateValidator();

        assertThrows(
                HandleException.class,
                () -> subject.validateKeyAliasAndEvmAddressCombinations(
                        typicalHollowAccountCreation, attributeValidator, aliasConfig, accountStore, false));
    }

    @Test
    void checkTooManyAutoAssociations() {
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertTrue(subject.tooManyAutoAssociations(5001, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(3000, ledgerConfig, entitiesConfig, tokensConfig));
    }

    @Test
    void checkDiffTooManyAutoAssociations() {
        testConfigBuilder = testConfigBuilder.withValue("entities.limitTokenAssociations", true);
        configuration = testConfigBuilder.getOrCreateConfig();
        getConfigs(configuration);
        assertTrue(subject.tooManyAutoAssociations(1001, ledgerConfig, entitiesConfig, tokensConfig));
        assertFalse(subject.tooManyAutoAssociations(999, ledgerConfig, entitiesConfig, tokensConfig));
    }

    private void getConfigs(Configuration configuration) {
        tokensConfig = configuration.getConfigData(TokensConfig.class);
        ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        entitiesConfig = configuration.getConfigData(EntitiesConfig.class);
    }
}
