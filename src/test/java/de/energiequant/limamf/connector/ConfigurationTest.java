package de.energiequant.limamf.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
    @Test
    void testCreateFromDefaults_always_doesNotFail() {
        // arrange

        // act
        ThrowingCallable action = () -> Configuration.createFromDefaults();

        // assert
        assertThatCode(action).doesNotThrowAnyException();
    }

    @Test
    void testCreateFromDefaults_always_hasNoAcceptedDisclaimer() {
        // arrange
        Configuration config = Configuration.createFromDefaults();

        // act
        Optional<String> result = config.getAcceptedDisclaimer();

        // assert
        assertThat(result).isEmpty();
    }
}
